package wallet.ui;

import android.text.TextUtils;
import android.widget.TextView;

/** Common TextView behavior for long values that must remain visually compact. */
public final class TextViewUtils {
    private TextViewUtils() {
    }

    public static void configureSelectableMiddleEllipsis(TextView view) {
        if (view == null) return;
        // Keep the full value in the TextView so existing copy actions read the
        // original data, while the view renders it as one compact line.
        // Text selection is intentionally not enabled here because selectable
        // TextViews use a dynamic layout that can defeat middle ellipsizing.
        view.setMaxLines(1);
        view.setHorizontallyScrolling(false);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    }
}
