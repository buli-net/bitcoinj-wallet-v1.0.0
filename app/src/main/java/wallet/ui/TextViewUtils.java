package wallet.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
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
     * Uses Android's native middle ellipsis. The full text remains the TextView
     * value; only its visual representation is shortened when it does not fit.
     *
     * No TextWatcher, posted relayout, or replacement span is used here. This
     * keeps dynamic values stable when their contents are refreshed repeatedly.
     */
    public static void configureSelectableMiddleEllipsis(TextView view) {
        if (view == null) return;

        view.setSingleLine(true);
        view.setMaxLines(1);
        view.setHorizontallyScrolling(false);
        // Native middle ellipsis and selectable TextView do not work reliably together
        // on all Android versions. Keep the view non-selectable for stable rendering,
        // but preserve the expected copy action by copying the complete underlying
        // value on long press.
        view.setTextIsSelectable(false);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        view.setTransformationMethod(null);
        view.setOnLongClickListener(v -> {
            CharSequence value = view.getText();
            if (value == null || value.length() == 0) {
                return false;
            }
            ClipboardManager clipboard =
                    (ClipboardManager) view.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return false;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("B-Lite", value.toString()));
            return true;
        });
    }

    /**
     * Covers long identifier-like values that are already present in a view
     * hierarchy. Views whose data is populated later should call
     * configureSelectableMiddleEllipsis() when they are bound.
     */
    public static void configureLongValueViews(View root) {
        if (root == null) return;
        configureLongValueViewTree(root);
    }

    private static void configureLongValueViewTree(View view) {
        if (view instanceof EditText) {
            return;
        }

        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            if (isLongValue(textView.getText())) {
                configureSelectableMiddleEllipsis(textView);
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

        // Legacy Bitcoin Base58 addresses are typically 26-35 characters.
        if (text.length() >= 26 && text.length() <= 35
                && text.matches("[1-9A-HJ-NP-Za-km-z]+")) {
            return true;
        }

        return !text.matches(".*\\s+.*")
                && text.matches("[1-9A-HJ-NP-Za-km-z]{26,}");
    }
}
