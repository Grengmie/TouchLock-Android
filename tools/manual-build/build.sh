#!/usr/bin/env bash
#
# TouchLock 手工出包流水线（当前沙箱禁止创建 socket，Gradle / adb 都起不来，所以用它顶替）。
#
# 流程：aapt2 编译资源 -> aapt2 link（生成基础 APK 与 R.java）-> javac（R 类 + ViewBinding）
#      -> kotlinc（业务 Kotlin 代码）-> d8 -> 打进 dex -> zipalign -> apksigner 签名
#
# 用法：bash tools/manual-build/build.sh
# 产物：项目根目录的 TouchLock-debug.apk（沿用 TouchLock-debug.keystore 签名，可覆盖安装）
#
# 注意（踩过的坑，别改回去）：
# 1. 不合并依赖库清单，所以依赖库清单里声明的权限 / provider / receiver 都必须在本工程
#    AndroidManifest.xml 里显式补上（例如 androidx.core 的
#    DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION，缺了会让开启无障碍后服务崩溃）。
# 2. kotlinc 要用 -no-jdk：JBR 是 JDK 25，kotlin-compiler 1.9.22 解析不了它的版本号；
#    Android 编译本来也不该用 JDK 的 java.* 类（android.jar 里有）。
# 3. kotlin-compiler-embeddable 需要 trove4j 在 classpath 上，否则报 NoClassDefFoundError: gnu/trove/THashMap。
# 4. AAR 里的 jars/libs/*.jar（如 emoji2 的 repackaged.jar）也要一起 dex，否则会缺类。
set -euo pipefail

export JAVA_HOME=/snap/android-studio/247/jbr
export PATH="$JAVA_HOME/bin:$PATH"

SDK=/home/glz/Android/Sdk
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
PROJ=/home/glz/Documents/touchlock
M2=/home/glz/.gradle/caches/modules-2/files-2.1
TR=/home/glz/.gradle/caches/9.6.0/transforms
SRC="$PROJ/tools/manual-build"

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

WORK=$(mktemp -d /tmp/tlwork.XXXXXX)
echo "WORK=$WORK"
mkdir -p "$WORK/res" "$WORK/gen" "$WORK/classes_java" "$WORK/classes_kotlin" "$WORK/dex" "$WORK/jars"

# 依赖 AAR（Gradle transform 已解包，含 res/ 与 jars/classes.jar）
AAR_NAMES="activity-1.8.0 activity-ktx-1.8.0 annotation-experimental-1.3.0 appcompat-1.6.1
appcompat-resources-1.6.1 cardview-1.0.0 constraintlayout-2.1.4 coordinatorlayout-1.1.0
core-1.12.0 core-ktx-1.12.0 core-runtime-2.2.0 cursoradapter-1.0.0 customview-1.1.0
documentfile-1.0.0 drawerlayout-1.1.1 dynamicanimation-1.0.0 emoji2-1.2.0
emoji2-views-helper-1.2.0 fragment-1.3.6 interpolator-1.0.0 lifecycle-livedata-2.6.1
lifecycle-livedata-core-2.6.1 lifecycle-process-2.6.1 lifecycle-runtime-2.6.1
lifecycle-runtime-ktx-2.6.1 lifecycle-viewmodel-2.6.1 lifecycle-viewmodel-ktx-2.6.1
lifecycle-viewmodel-savedstate-2.6.1 loader-1.0.0 localbroadcastmanager-1.0.0 material-1.11.0
print-1.0.0 profileinstaller-1.3.0 recyclerview-1.1.0 savedstate-1.2.1 savedstate-ktx-1.2.1
startup-runtime-1.1.1 tracing-1.0.0 transition-1.2.0 vectordrawable-1.1.0
vectordrawable-animated-1.1.0 versionedparcelable-1.1.1 viewpager-1.0.0 viewpager2-1.0.0"

# 纯 jar 依赖（无资源）
JAR_SPECS="androidx.annotation/annotation-jvm:annotation-jvm-1.6.0.jar
androidx.collection/collection:collection-1.1.0.jar
androidx.arch.core/core-common:core-common-2.2.0.jar
androidx.lifecycle/lifecycle-common:lifecycle-common-2.6.1.jar
androidx.concurrent/concurrent-futures:concurrent-futures-1.1.0.jar
com.google.guava/listenablefuture:listenablefuture-1.0.jar
androidx.resourceinspection/resourceinspection-annotation:resourceinspection-annotation-1.0.1.jar
androidx.constraintlayout/constraintlayout-core:constraintlayout-core-1.0.4.jar
org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm:kotlinx-coroutines-core-jvm-1.6.4.jar
org.jetbrains.kotlinx/kotlinx-coroutines-android:kotlinx-coroutines-android-1.6.4.jar
org.jetbrains.kotlin/kotlin-stdlib:kotlin-stdlib-1.9.22.jar"

KOTLIN_COMPILER=$(find "$M2/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name "kotlin-compiler-embeddable-1.9.22.jar" | head -1)
KOTLIN_STDLIB=$(find "$M2/org.jetbrains.kotlin/kotlin-stdlib" -name "kotlin-stdlib-1.9.22.jar" | head -1)
TROVE4J=$(find "$M2/org.jetbrains.intellij.deps/trove4j" -name "trove4j-1.0.20200330.jar" | head -1)
ANNOTATIONS_JAR=$(find "$M2/org.jetbrains/annotations" -name "annotations-*.jar" ! -name "*-sources.jar" | head -1)
KOTLIN_SCRIPT_RUNTIME=$(find "$M2/org.jetbrains.kotlin/kotlin-script-runtime" -name "kotlin-script-runtime-1.9.22.jar" 2>/dev/null | head -1 || true)
KOTLIN_REFLECT=$(find "$M2/org.jetbrains.kotlin/kotlin-reflect" -name "kotlin-reflect-1.9*.jar" 2>/dev/null | head -1 || true)
KOTLIN_CLI_CP="$KOTLIN_COMPILER:$KOTLIN_STDLIB:$TROVE4J"
for extra_cp in "$ANNOTATIONS_JAR" "$KOTLIN_SCRIPT_RUNTIME" "$KOTLIN_REFLECT"; do
    if [ -n "$extra_cp" ]; then
        KOTLIN_CLI_CP="$KOTLIN_CLI_CP:$extra_cp"
    fi
done

find_aar() {
    local name="$1" dir
    for dir in "$TR"/*/transformed/"$name"; do
        if [ -d "$dir" ]; then
            echo "$dir"
            return 0
        fi
    done
    echo "找不到依赖 $name" >&2
    return 1
}

find_jar() {
    local module="$1" file="$2" path
    path=$(find "$M2/$module" -name "$file" ! -name "*-sources.jar" ! -name "*-javadoc.jar" | head -1)
    if [ -z "$path" ]; then
        echo "找不到依赖 jar $module/$file" >&2
        return 1
    fi
    echo "$path"
}

# ---------------------------------------------------------------- 1. 编译资源
echo "== 1/7 编译资源"
"$AAPT2" compile --dir "$PROJ/app/src/main/res" -o "$WORK/res/app.zip"

AAR_DIRS=()
RES_ZIPS=()
PKGS=()
for name in $AAR_NAMES; do
    dir=$(find_aar "$name")
    AAR_DIRS+=("$dir")
    if [ -d "$dir/res" ] && [ -n "$(ls -A "$dir/res")" ]; then
        "$AAPT2" compile --dir "$dir/res" -o "$WORK/res/$name.zip"
        RES_ZIPS+=("$WORK/res/$name.zip")
    fi
    if [ -s "$dir/R.txt" ]; then
        pkg=$(python3 -c "import re,sys;print(re.search(r'package=\"([^\"]+)\"',open(sys.argv[1]).read()).group(1))" "$dir/AndroidManifest.xml")
        PKGS+=("$pkg")
    fi
done
echo "   AAR 数量=${#AAR_DIRS[@]}，含资源 AAR=${#RES_ZIPS[@]}，R 包数量=${#PKGS[@]}"

# ---------------------------------------------------------------- 2. 生成带 package 的清单副本
echo "== 2/7 生成清单副本"
python3 - "$PROJ/app/src/main/AndroidManifest.xml" "$WORK/AndroidManifest.xml" <<'PY'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
if 'package=' not in text.split('>')[0]:
    text = re.sub(r'<manifest([^>]*)>',
                  lambda m: '<manifest' + m.group(1) + ' package="com.example.touchlock">',
                  text, count=1)
open(dst, "w", encoding="utf-8").write(text)
PY

# ---------------------------------------------------------------- 3. aapt2 link
echo "== 3/7 aapt2 link"
LINK_ARGS=()
for zip in "${RES_ZIPS[@]}"; do
    LINK_ARGS+=("-R" "$zip")
done
EXTRA=()
for pkg in "${PKGS[@]}"; do
    EXTRA+=("--extra-packages" "$pkg")
done
"$AAPT2" link \
    -o "$WORK/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$WORK/AndroidManifest.xml" \
    --min-sdk-version 26 \
    --target-sdk-version 26 \
    --version-code 3 \
    --version-name 1.0.2 \
    --auto-add-overlay \
    --java "$WORK/gen" \
    "${EXTRA[@]}" \
    "${LINK_ARGS[@]}" \
    "$WORK/res/app.zip"

REF_PKGS=$(for dir in "${AAR_DIRS[@]}"; do
    unzip -p "$dir/jars/classes.jar" '*.class' 2>/dev/null
done | grep -a -o '[a-zA-Z0-9_/]*/R\$[a-zA-Z0-9_]*' | sed 's#/R\$.*##' | sort -u | tr '\n' ' ')
echo "   库字节码引用的 R 包：$REF_PKGS"

# ---------------------------------------------------------------- 4. javac：R 类 + ViewBinding
echo "== 4/7 javac R 与 ViewBinding"
CP="$ANDROID_JAR"
for dir in "${AAR_DIRS[@]}"; do
    CP="$CP:$dir/jars/classes.jar"
done
for spec in $JAR_SPECS; do
    module="${spec%%:*}"
    file="${spec##*:}"
    CP="$CP:$(find_jar "$module" "$file")"
done

mapfile -t R_SOURCES < <(find "$WORK/gen" -name "*.java")
javac -nowarn -source 17 -target 17 -Xlint:none \
    -classpath "$CP" -d "$WORK/classes_java" \
    "${R_SOURCES[@]}" "$SRC/binding/ActivityMainBinding.java" "$SRC/binding/OverlayLockBinding.java"

# ---------------------------------------------------------------- 5. kotlinc
echo "== 5/7 kotlinc"
java -Xmx2g -cp "$KOTLIN_CLI_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-jdk -no-stdlib -no-reflect -nowarn -jvm-target 17 \
    -classpath "$CP:$WORK/classes_java" \
    -d "$WORK/classes_kotlin" \
    "$PROJ/app/src/main/java/com/example/touchlock/MainActivity.kt" \
    "$PROJ/app/src/main/java/com/example/touchlock/TouchLockAccessibilityService.kt" \
    "$PROJ/app/src/main/java/com/example/touchlock/KeyComboDetector.kt" \
    "$PROJ/app/src/main/java/com/example/touchlock/LockOverlayManager.kt" \
    "$PROJ/app/src/main/java/com/example/touchlock/TouchLockSettings.kt" \
    "$PROJ/app/src/main/java/com/example/touchlock/TouchLockApp.kt"

# ---------------------------------------------------------------- 6. d8
echo "== 6/7 d8"
jar cf "$WORK/jars/java.jar" -C "$WORK/classes_java" .
jar cf "$WORK/jars/kotlin.jar" -C "$WORK/classes_kotlin" .
D8_INPUTS=("$WORK/jars/java.jar" "$WORK/jars/kotlin.jar")
for dir in "${AAR_DIRS[@]}"; do
    D8_INPUTS+=("$dir/jars/classes.jar")
    for extra_jar in "$dir"/jars/libs/*.jar; do
        [ -f "$extra_jar" ] && D8_INPUTS+=("$extra_jar")
    done
done
for spec in $JAR_SPECS; do
    module="${spec%%:*}"
    file="${spec##*:}"
    D8_INPUTS+=("$(find_jar "$module" "$file")")
done
"$D8" --min-api 26 --lib "$ANDROID_JAR" --release --output "$WORK/dex" "${D8_INPUTS[@]}"
ls -la "$WORK/dex"

# ---------------------------------------------------------------- 7. 组装 + 对齐 + 签名
echo "== 7/7 组装 APK"
python3 - "$WORK/base.apk" "$WORK/dex" "$WORK/unaligned.apk" <<'PY'
import os, shutil, sys, zipfile
base, dexdir, out = sys.argv[1], sys.argv[2], sys.argv[3]
dexs = sorted((f for f in os.listdir(dexdir) if f.endswith(".dex")),
              key=lambda n: (len(n), n))
shutil.copyfile(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    for name in dexs:
        z.write(os.path.join(dexdir, name), name)
        print("   +", name)
PY
"$ZIPALIGN" -p -f 4 "$WORK/unaligned.apk" "$WORK/aligned.apk"
"$APKSIGNER" sign \
    --ks "$PROJ/TouchLock-debug.keystore" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$PROJ/TouchLock-debug.apk" \
    "$WORK/aligned.apk"
"$APKSIGNER" verify --print-certs "$PROJ/TouchLock-debug.apk" | head -5
echo "完成：$PROJ/TouchLock-debug.apk"
