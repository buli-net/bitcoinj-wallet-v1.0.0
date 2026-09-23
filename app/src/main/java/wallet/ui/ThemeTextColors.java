package wallet.ui;

import android.content.Context;
import android.content.res.TypedArray;

/** Resolves text and divider colors from the active Android theme. */
public final class ThemeTextColors {

    private ThemeTextColors() {
    }

    public static int primary(Context context) {
        return resolve(context, android.R.attr.textColorPrimary, 0xff000000);
    }

    public static int secondary(Context context) {
        return resolve(context, android.R.attr.textColorSecondary, 0xff777777);
    }

    public static int divider(Context context) {
        return secondary(context);
    }

    private static int resolve(Context context, int attribute, int fallback) {
        TypedArray values = context.getTheme().obtainStyledAttributes(new int[]{attribute});
        try {
            return values.getColor(0, fallback);
        } finally {
            values.recycle();
        }
    }
}
