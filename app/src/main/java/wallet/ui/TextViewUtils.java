package wallet.ui;

import android.graphics.Paint;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Common TextView behavior for long values that must remain visually compact. */
public final class TextViewUtils {
    private static final int MIN_LONG_VALUE_LENGTH = 26;
    private static final Map<TextView, State> STATES = new WeakHashMap<>();

    private TextViewUtils() {
    }

    /**
     * Updates a long-value TextView only when its source value changed. If the
     * user is currently selecting text, the update is deferred so Android's
     * selection handles/action mode are not destroyed by a refresh.
     */
    public static void setTextIfChanged(TextView view, CharSequence value) {
        if (view == null || view.hasSelection()) {
            return;
        }

        State state;
        synchronized (STATES) {
            state = STATES.get(view);
        }

        if (state != null) {
            String next = value == null ? "" : value.toString();
            if (next.equals(state.sourceText)) {
                return;
            }
            state.sourceText = next;
            state.lastText = null;
            state.lastWidth = -1;
            view.setText(value == null ? "" : value);
            return;
        }

        CharSequence current = view.getText();
        if (!TextUtils.equals(current, value)) {
            view.setText(value);
        }
    }

    public static void configureSelectableMiddleEllipsis(TextView view) {
        if (view == null) return;

        view.setMaxLines(1);
        view.setHorizontallyScrolling(false);
        view.setTextIsSelectable(true);
        view.setEllipsize(null);
        view.setTransformationMethod(null);

        synchronized (STATES) {
            if (STATES.containsKey(view)) {
                // The view is already configured. Re-running apply() here on every
                // refresh would replace the Spannable and destroy an active Android
                // text selection. Data changes are handled by the TextWatcher below.
                return;
            }

            State state = new State();
            STATES.put(view, state);
            view.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (state.applying) {
                        return;
                    }

                    String newText = s == null ? "" : s.toString();
                    boolean textChanged = !newText.equals(state.sourceText);
                    boolean needsDisplayPass = !hasEllipsisSpan(s);

                    // A large part of the app refreshes its TextViews repeatedly even
                    // when the value did not change. Do not invalidate the rendered
                    // ellipsis in that case: replacing the text would also destroy an
                    // active Android selection/handles during a long-press.
                    if (textChanged) {
                        state.sourceText = newText;
                        state.lastWidth = -1;
                    }

                    if (textChanged || needsDisplayPass) {
                        scheduleApply(view, state);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                    // All work is scheduled from onTextChanged only when the source
                    // value really changed or the view lost its ellipsis span.
                }
            });
        }

        view.addOnLayoutChangeListener((v, left, top, right, bottom,
                                         oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                State state;
                synchronized (STATES) {
                    state = STATES.get(view);
                }
                if (state != null) {
                    state.lastWidth = -1;
                    scheduleApply(view, state);
                }
            }
        });

        State state;
        synchronized (STATES) {
            state = STATES.get(view);
        }

        String currentText = view.getText() == null ? "" : view.getText().toString();
        if (state.sourceText == null || !currentText.equals(state.sourceText)
                || !hasEllipsisSpan(view.getText())) {
            scheduleApply(view, state);
        }
    }

    /**
     * Covers long identifier-like values that are created dynamically, so a
     * newly added address/hash/path field gets the same display behavior.
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
        // They are shorter than TXIDs but are still long identifiers that must
        // use the same compact display/copy behavior.
        if (text.length() >= 26 && text.length() <= 35
                && text.matches("[1-9A-HJ-NP-Za-km-z]+")) {
            return true;
        }

        return !text.matches(".*\\s+.*") && text.matches("[1-9A-HJ-NP-Za-km-z]{26,}");
    }

    private static boolean hasEllipsisSpan(CharSequence text) {
        if (!(text instanceof Spanned)) {
            return false;
        }
        Spanned spanned = (Spanned) text;
        return spanned.getSpans(0, spanned.length(), EllipsisSpan.class).length > 0;
    }

    private static EllipsisSpan[] getEllipsisSpans(CharSequence text) {
        if (!(text instanceof Spanned)) {
            return new EllipsisSpan[0];
        }
        Spanned spanned = (Spanned) text;
        return spanned.getSpans(0, spanned.length(), EllipsisSpan.class);
    }

    /**
     * Coalesces repeated data/layout notifications into one ellipsis render per
     * UI frame. This keeps rapidly updating sync values live without repeatedly
     * replacing the TextView text during the same frame.
     */
    private static void scheduleApply(TextView view, State state) {
        if (view == null || state == null || state.applying || state.scheduled) {
            return;
        }
        state.scheduled = true;
        view.postOnAnimation(() -> {
            state.scheduled = false;
            if (!view.hasSelection()) {
                apply(view, state);
            }
        });
    }

    private static void apply(TextView view, State state) {
        if (view == null || state == null || state.applying) return;

        int width = view.getWidth()
                - view.getCompoundPaddingLeft()
                - view.getCompoundPaddingRight();
        String raw = view.getText() == null ? "" : view.getText().toString();

        if (width <= 0 || raw.length() < MIN_LONG_VALUE_LENGTH) {
            return;
        }

        if (raw.equals(state.lastText) && width == state.lastWidth) {
            return;
        }

        state.applying = true;
        try {
            state.sourceText = raw;

            boolean hasCurrentEllipsis = hasEllipsisSpan(view.getText());

            if (view.getPaint().measureText(raw) <= width) {
                // A previous layout pass may have created an ellipsis span while the
                // RecyclerView holder was still narrower. Once the real width is
                // available, restore the complete raw value instead of leaving that
                // stale span in place. This is the root cause of cases where an
                // address such as a normal 34-character Base58 address lost its
                // suffix even though the row had enough room.
                state.lastText = raw;
                state.lastWidth = width;
                if (hasCurrentEllipsis) {
                    view.setText(raw, TextView.BufferType.SPANNABLE);
                }
                return;
            }

            float ellipsisWidth = view.getPaint().measureText("…");
            if (ellipsisWidth >= width) {
                state.lastText = raw;
                state.lastWidth = width;
                if (hasCurrentEllipsis) {
                    view.setText(raw, TextView.BufferType.SPANNABLE);
                }
                return;
            }

            int[] range = findVisibleRange(view.getPaint(), raw, width - ellipsisWidth);
            if (range[0] == 0 && range[1] == raw.length()) {
                state.lastText = raw;
                state.lastWidth = width;
                if (hasCurrentEllipsis) {
                    view.setText(raw, TextView.BufferType.SPANNABLE);
                }
                return;
            }

            EllipsisSpan[] currentSpans = getEllipsisSpans(view.getText());
            if (currentSpans.length == 1) {
                // Compare the actual span range rather than only the raw string.
                // The same source text can legitimately need a different display
                // range after a RecyclerView/layout width change.
                Spanned current = (Spanned) view.getText();
                int spanStart = current.getSpanStart(currentSpans[0]);
                int spanEnd = current.getSpanEnd(currentSpans[0]);
                if (spanStart == range[0] && spanEnd == range[1]) {
                    state.lastText = raw;
                    state.lastWidth = width;
                    return;
                }
            }

            SpannableString display = new SpannableString(raw);
            display.setSpan(new EllipsisSpan(ellipsisWidth), range[0], range[1],
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            state.lastText = raw;
            state.lastWidth = width;
            view.setText(display, TextView.BufferType.SPANNABLE);
        } finally {
            state.applying = false;
        }
    }

    private static int[] findVisibleRange(Paint paint, String text, float available) {
        // Choose the longest visible prefix+suffix pair that fits, then prefer
        // the most balanced pair. The previous greedy walk could consume the
        // whole width with the prefix and leave no suffix at all for some
        // addresses, producing results such as "3AtmPRCET5G..." while other
        // addresses of the same length displayed correctly on both sides.
        int length = text.length();
        if (length <= 1 || available <= 0f) {
            return new int[]{0, length};
        }

        float[] prefixWidths = new float[length + 1];
        float[] suffixWidths = new float[length + 1];
        for (int i = 0; i < length; i++) {
            prefixWidths[i + 1] = prefixWidths[i]
                    + paint.measureText(text, i, i + 1);
            suffixWidths[i + 1] = suffixWidths[i]
                    + paint.measureText(text, length - i - 1, length - i);
        }

        int bestPrefix = 0;
        int bestSuffix = 0;
        int bestVisible = -1;
        float bestImbalance = Float.MAX_VALUE;

        for (int prefix = 0; prefix < length; prefix++) {
            for (int suffix = 0; suffix < length - prefix; suffix++) {
                if (prefix == 0 && suffix == 0) {
                    continue;
                }

                float totalWidth = prefixWidths[prefix] + suffixWidths[suffix];
                if (totalWidth > available) {
                    continue;
                }

                int visible = prefix + suffix;
                float imbalance = Math.abs(prefixWidths[prefix] - suffixWidths[suffix]);

                if (visible > bestVisible
                        || (visible == bestVisible && imbalance < bestImbalance)
                        || (visible == bestVisible && imbalance == bestImbalance
                        && prefix > bestPrefix)) {
                    bestVisible = visible;
                    bestPrefix = prefix;
                    bestSuffix = suffix;
                    bestImbalance = imbalance;
                }
            }
        }

        if (bestVisible <= 0 || bestPrefix + bestSuffix >= length) {
            return new int[]{0, length};
        }

        return new int[]{bestPrefix, length - bestSuffix};
    }

    private static final class State {
        String sourceText;
        String lastText;
        int lastWidth = -1;
        boolean applying;
        boolean scheduled;
    }

    private static final class EllipsisSpan extends ReplacementSpan {
        private final float width;

        EllipsisSpan(float width) {
            this.width = width;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            return Math.round(width);
        }

        @Override
        public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            canvas.drawText("…", x, y, paint);
        }
    }
}
