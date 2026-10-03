package wallet.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;

/** Common TextView behavior for long values that must remain visually compact. */
public final class TextViewUtils {
    private static final int MIN_LONG_VALUE_LENGTH = 26;

    private TextViewUtils() {
    }

    /**
     * Configures the dedicated selectable middle-ellipsis view. The view keeps
     * the complete underlying value and replaces only its visual middle span.
     */
    public static void configureSelectableMiddleEllipsis(TextView view) {
        if (!(view instanceof SelectableMiddleEllipsizedTextView)) {
            return;
        }
        SelectableMiddleEllipsizedTextView longValueView =
                (SelectableMiddleEllipsizedTextView) view;
        longValueView.configureMiddleEllipsis();
    }

    /**
     * Covers long identifier-like values that are already present in a view
     * hierarchy. The long-value fields in layouts use the dedicated selectable
     * view so selection/copy and visual compaction share one implementation.
     */
    public static void configureLongValueViews(View root) {
        if (root == null) return;
        configureLongValueViewTree(root);
    }

    private static void configureLongValueViewTree(View view) {
        if (view instanceof EditText) {
            return;
        }

        if (view instanceof SelectableMiddleEllipsizedTextView) {
            SelectableMiddleEllipsizedTextView textView =
                    (SelectableMiddleEllipsizedTextView) view;
            if (isLongValue(textView.getText())) {
                textView.configureMiddleEllipsis();
            }
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                configureLongValueViewTree(group.getChildAt(i));
            }
        }
    }

    private static boolean isLongValue(CharSequence value) {
        if (value == null || value.length() < MIN_LONG_VALUE_LENGTH) {
            return false;
        }
        if (value.toString().indexOf('\n') >= 0) {
            return false;
        }

        String text = value.toString();
        String lower = text.toLowerCase(Locale.US);

        if (lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1")
                || lower.startsWith("xpub") || lower.startsWith("tpub")
                || lower.startsWith("ypub") || lower.startsWith("zpub")
                || lower.startsWith("upub") || lower.startsWith("vpub")) {
            return true;
        }

        if (text.startsWith("/") || text.contains("/wallet")) {
            return true;
        }

        boolean hex = true;
        for (int i = 0; i < text.length(); i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                hex = false;
                break;
            }
        }
        if (hex && (text.length() == 64 || text.length() == 66)) {
            return true;
        }

        if (text.length() >= 26 && text.length() <= 35
                && text.matches("[1-9A-HJ-NP-Za-km-z]+")) {
            return true;
        }

        return !text.matches(".*\\s+.*")
                && text.matches("[1-9A-HJ-NP-Za-km-z]{26,}");
    }
}
