package wallet.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v7.view.menu.MenuBuilder;
import android.view.Menu;
import android.view.MenuItem;

/** Theme-aware helpers shared by toolbar and menu UI. */
public final class ThemeUtils {

    private ThemeUtils() {
    }

    public static void tintMenuIcons(Menu menu, Context context) {
        if (menu instanceof MenuBuilder) {
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }

        int tintColor = resolveColorControlNormal(context);
        for (int i = 0; i < menu.size(); i++) {
            tintMenuItem(menu.getItem(i), tintColor);
        }
    }

    private static void tintMenuItem(MenuItem item, int tintColor) {
        Drawable icon = item.getIcon();
        if (icon != null) {
            Drawable wrapped = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(wrapped, tintColor);
            item.setIcon(wrapped);
        }

        if (item.hasSubMenu()) {
            for (int i = 0; i < item.getSubMenu().size(); i++) {
                tintMenuItem(item.getSubMenu().getItem(i), tintColor);
            }
        }
    }

    private static int resolveColorControlNormal(Context context) {
        TypedArray attributes = context.getTheme().obtainStyledAttributes(
                new int[]{android.support.v7.appcompat.R.attr.colorControlNormal});
        try {
            return attributes.getColor(0, 0xffffffff);
        } finally {
            attributes.recycle();
        }
    }
}
