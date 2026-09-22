package wallet.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;

/** Resolves colors from the active Android/AppCompat DayNight theme. */
public final class ThemeUtils {
    private ThemeUtils() { }

    public static int color(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) {
            return 0;
        }
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        if (value.resourceId != 0) {
            ColorStateList list = context.getResources().getColorStateList(value.resourceId);
            return list.getColorForState(new int[0], list.getDefaultColor());
        }
        return 0;
    }
}
