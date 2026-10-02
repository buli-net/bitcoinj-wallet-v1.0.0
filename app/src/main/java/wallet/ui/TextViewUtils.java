package wallet.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

/** Common TextView behavior for long values that must remain visually compact. */
public final class TextViewUtils {
    private static final int MIN_LONG_VALUE_LENGTH = 40;

    private TextViewUtils() {
    }

    public static void configureSelectableMiddleEllipsis(TextView view) {
        if (view == null) return;

        // Keep the full value in the TextView and use a non-selectable view so
        // Android can render middle ellipsis reliably. Long-press copies the
        // complete underlying value instead of the visually ellipsized text.
        view.setMaxLines(1);
        view.setHorizontallyScrolling(false);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        view.setOnLongClickListener(TextViewUtils::copyFullText);
    }

    /**
     * Covers long identifier-like values that are created dynamically, so a
     * newly added address/hash/path field gets the same display/copy behavior
     * without having to maintain another per-screen list.
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
            TextWatcher watcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (isLongValue(s)) {
                        configureSelectableMiddleEllipsis(textView);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            };
            textView.addTextChangedListener(watcher);

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
        String lower = text.toLowerCase(java.util.Locale.US);

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
            char c = text.charAt(i);
            if (Character.digit(c, 16) < 0) {
                hex = false;
                break;
            }
        }
        if (hex && (text.length() == 64 || text.length() == 66)) {
            return true;
        }

        return !text.matches(".*\\s+.*") && text.matches("[1-9A-HJ-NP-Za-km-z]{40,}");
    }

    private static boolean copyFullText(View source) {
        if (!(source instanceof TextView)) return false;
        CharSequence value = ((TextView) source).getText();
        if (TextUtils.isEmpty(value)) return false;

        ClipboardManager clipboard =
                (ClipboardManager) source.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return false;

        clipboard.setPrimaryClip(ClipData.newPlainText("text", value.toString()));
        return true;
    }
}
