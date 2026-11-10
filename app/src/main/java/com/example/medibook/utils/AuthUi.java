package com.example.medibook.utils;

import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Shared edge-to-edge and keyboard spacing for the scrollable authentication forms. */
public final class AuthUi {

    private AuthUi() {
    }

    public static void applyInsets(AppCompatActivity activity, View root) {
        WindowCompat.enableEdgeToEdge(activity.getWindow());
        WindowCompat.getInsetsController(activity.getWindow(), root)
                .setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(activity.getWindow(), root)
                .setAppearanceLightNavigationBars(true);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
