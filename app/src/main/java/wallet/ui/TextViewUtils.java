package wallet.ui;

import android.text.TextUtils;
import android.widget.TextView;

/** Common TextView behavior for selectable long data values. */
public final class TextViewUtils {
    private TextViewUtils() {
    }

    public static void configureSelectableMiddleEllipsis(TextView view) {
        if (view == null) return;
        view.setSingleLine(true);
        view.setMaxLines(1);
        view.setHorizontallyScrolling(true);
        view.setTextIsSelectable(true);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    }
}
