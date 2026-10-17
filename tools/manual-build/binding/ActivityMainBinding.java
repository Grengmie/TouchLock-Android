package com.example.touchlock.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import com.example.touchlock.R;

/**
 * activity_main.xml 的视图绑定类（手工出包用的等价实现）。
 *
 * 正常用 Gradle 构建时由 AGP 的 viewBinding 自动生成，本项目还有一条手工出包流水线
 * （tools/manual-build/build.sh：aapt2 + javac + kotlinc + d8），需要自己提供这个类。
 * 字段集合、字段顺序与 AGP 生成的结果保持一致，方便日后对照。
 */
public final class ActivityMainBinding {

    public final ScrollView root;
    public final Button btnOpenAccessibilitySettings;
    public final Button btnSaveOverlayDuration;
    public final CheckBox cbKeepLockedAfterHide;
    public final EditText etOverlayDurationMs;
    public final TextView tvDescription;
    public final TextView tvOverlayDurationTitle;
    public final TextView tvServiceStatus;
    public final TextView tvServiceStatusHint;
    public final TextView tvTitle;

    private ActivityMainBinding(ScrollView root, Button btnOpenAccessibilitySettings,
            Button btnSaveOverlayDuration, CheckBox cbKeepLockedAfterHide, EditText etOverlayDurationMs,
            TextView tvDescription, TextView tvOverlayDurationTitle, TextView tvServiceStatus,
            TextView tvServiceStatusHint, TextView tvTitle) {
        this.root = root;
        this.btnOpenAccessibilitySettings = btnOpenAccessibilitySettings;
        this.btnSaveOverlayDuration = btnSaveOverlayDuration;
        this.cbKeepLockedAfterHide = cbKeepLockedAfterHide;
        this.etOverlayDurationMs = etOverlayDurationMs;
        this.tvDescription = tvDescription;
        this.tvOverlayDurationTitle = tvOverlayDurationTitle;
        this.tvServiceStatus = tvServiceStatus;
        this.tvServiceStatusHint = tvServiceStatusHint;
        this.tvTitle = tvTitle;
    }

    public static ActivityMainBinding inflate(LayoutInflater inflater) {
        return inflate(inflater, null, false);
    }

    public static ActivityMainBinding inflate(LayoutInflater inflater, ViewGroup parent, boolean attachToParent) {
        View rootView = inflater.inflate(R.layout.activity_main, parent, false);
        if (attachToParent && parent != null) {
            parent.addView(rootView);
        }
        return bind(rootView);
    }

    public static ActivityMainBinding bind(View rootView) {
        ScrollView root = (ScrollView) rootView;
        Button btnOpenAccessibilitySettings = (Button) rootView.findViewById(R.id.btnOpenAccessibilitySettings);
        Button btnSaveOverlayDuration = (Button) rootView.findViewById(R.id.btnSaveOverlayDuration);
        CheckBox cbKeepLockedAfterHide = (CheckBox) rootView.findViewById(R.id.cbKeepLockedAfterHide);
        EditText etOverlayDurationMs = (EditText) rootView.findViewById(R.id.etOverlayDurationMs);
        TextView tvDescription = (TextView) rootView.findViewById(R.id.tvDescription);
        TextView tvOverlayDurationTitle = (TextView) rootView.findViewById(R.id.tvOverlayDurationTitle);
        TextView tvServiceStatus = (TextView) rootView.findViewById(R.id.tvServiceStatus);
        TextView tvServiceStatusHint = (TextView) rootView.findViewById(R.id.tvServiceStatusHint);
        TextView tvTitle = (TextView) rootView.findViewById(R.id.tvTitle);
        return new ActivityMainBinding(root, btnOpenAccessibilitySettings, btnSaveOverlayDuration,
                cbKeepLockedAfterHide, etOverlayDurationMs, tvDescription, tvOverlayDurationTitle,
                tvServiceStatus, tvServiceStatusHint, tvTitle);
    }
}
