package dev.ukanth.ufirewall.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CompoundButton;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.CompoundButtonCompat;

import dev.ukanth.ufirewall.R;

public final class ThemeHelper {

    private ThemeHelper() {}

    public static void applyTheme(Activity activity) {
        apply(activity);
    }

    public static void apply(Activity activity) {
        if (activity == null) return;
        activity.setTheme(R.style.AppDarkTheme);
        applySystemBars(activity, activity.getWindow());
    }

    public static Drawable defaultAndroidIcon(Context context) {
        if (context == null) return null;
        Drawable drawable = ContextCompat.getDrawable(context, R.drawable.ic_unknown);
        if (drawable == null) return null;
        drawable = DrawableCompat.wrap(drawable.mutate());
        DrawableCompat.setTint(drawable, G.defaultIconColor(context));
        return drawable;
    }

    private static void applySystemBars(Context context, Window window) {
        if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int systemBarColor = G.primaryDarkColor(context);
        window.setStatusBarColor(systemBarColor);
        window.setNavigationBarColor(systemBarColor);
    }

    static ColorStateList controlTint(Context context) {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{
                        G.accentColor(context),
                        G.textSecondaryColor(context)
                });
    }

    public static void tintCompoundButton(CompoundButton button, Context context) {
        if (button != null) {
            CompoundButtonCompat.setButtonTintList(button, controlTint(context));
        }
    }
}
