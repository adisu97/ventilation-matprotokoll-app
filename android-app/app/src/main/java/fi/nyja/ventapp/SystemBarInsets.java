package fi.nyja.ventapp;

import android.app.Activity;
import android.os.Build;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Keeps the top controls below the status bar when Android 15 draws edge to edge. */
public final class SystemBarInsets {
    private SystemBarInsets() {
    }

    public static void apply(Activity activity, int rootId) {
        if (Build.VERSION.SDK_INT < 35) {
            return;
        }
        final View root = activity.findViewById(rootId);
        if (root == null) {
            return;
        }
        ViewCompat.setOnApplyWindowInsetsListener(root, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat insets) {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            }
        });
        ViewCompat.requestApplyInsets(root);
    }
}
