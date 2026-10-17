package com.example.touchlock.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.example.touchlock.R;

/**
 * overlay_lock.xml 的视图绑定类（手工出包用的等价实现）。
 *
 * 正常用 Gradle 构建时由 AGP 的 viewBinding 自动生成，本项目还有一条手工出包流水线
 * （tools/manual-build/build.sh），需要自己提供这个类。
 * LockOverlayManager 用它来在自动隐藏时同时隐藏图标与提示文字。
 */
public final class OverlayLockBinding {

    public final FrameLayout root;
    public final ImageView ivLockIcon;
    public final TextView tvLockHint;

    private OverlayLockBinding(FrameLayout root, ImageView ivLockIcon, TextView tvLockHint) {
        this.root = root;
        this.ivLockIcon = ivLockIcon;
        this.tvLockHint = tvLockHint;
    }

    public static OverlayLockBinding inflate(LayoutInflater inflater) {
        return inflate(inflater, null, false);
    }

    public static OverlayLockBinding inflate(LayoutInflater inflater, ViewGroup parent, boolean attachToParent) {
        View rootView = inflater.inflate(R.layout.overlay_lock, parent, false);
        if (attachToParent && parent != null) {
            parent.addView(rootView);
        }
        return bind(rootView);
    }

    public static OverlayLockBinding bind(View rootView) {
        FrameLayout root = (FrameLayout) rootView;
        ImageView ivLockIcon = (ImageView) rootView.findViewById(R.id.ivLockIcon);
        TextView tvLockHint = (TextView) rootView.findViewById(R.id.tvLockHint);
        return new OverlayLockBinding(root, ivLockIcon, tvLockHint);
    }
}
