/** Resolves text and divider colors from the active theme. */


package wallet.ui;

import android.content.Context;
import android.content.res.TypedArray;

public final class ThemeTextColors {

    private ThemeTextColors() {
    }

    public static int primary(Context context) {
        return resolve(context, android.R.attr.textColorPrimary);
    }

    public static int secondary(Context context) {
        return resolve(context, android.R.attr.textColorSecondary);
    }

    public static int accent(Context context) {
        return resolve(context, android.R.attr.colorAccent);
    }

    public static int primaryInverse(Context context) {
        return resolve(context, android.R.attr.textColorPrimaryInverse);
    }

    public static int divider(Context context) {
        return secondary(context);
    }

    private static int resolve(Context context, int attribute) {
        TypedArray values = context.getTheme()
                .obtainStyledAttributes(new int[]{attribute});

        try {
            return values.getColor(0, 0);
        } finally {
            values.recycle();
        }
    }
}
