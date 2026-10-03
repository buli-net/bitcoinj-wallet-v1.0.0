package wallet.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * Single-line long-value TextView that keeps the complete value selectable while
 * replacing only the visual middle with an ellipsis when the value is too wide.
 *
 * The underlying text is never shortened. Selection/copy therefore operates on
 * the original value and Android's normal text-selection action mode remains in use.
 */
public class SelectableMiddleEllipsizedTextView extends AppCompatTextView {
    private static final int MIN_VISIBLE_CHARS_PER_SIDE = 2;

    private String fullText = "";
    private boolean applyingDisplay;
    private boolean initialized;
    private int lastDisplayWidth = -1;

    public SelectableMiddleEllipsizedTextView(Context context) {
        super(context);
        init();
    }

    public SelectableMiddleEllipsizedTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SelectableMiddleEllipsizedTextView(Context context, AttributeSet attrs,
                                               int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /** Applies the stable long-value configuration after inflation/binding. */
    public void configureMiddleEllipsis() {
        if (!initialized) {
            CharSequence current = getText();
            fullText = current == null ? "" : current.toString();
            initialized = true;
        }
        configureView();
        invalidateDisplay();
    }

    private void init() {
        CharSequence current = getText();
        fullText = current == null ? "" : current.toString();
        initialized = true;
        configureView();
    }

    private void configureView() {
        setSingleLine(true);
        setMaxLines(1);
        setHorizontallyScrolling(false);
        setEllipsize(null);
        setTextIsSelectable(true);
        setTransformationMethod(null);
        setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    }

    private void invalidateDisplay() {
        lastDisplayWidth = -1;
        postDisplayIfLaidOut();
    }

    private void postDisplayIfLaidOut() {
        if (getWidth() > 0) {
            applyDisplay();
        }
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        if (!initialized || applyingDisplay) {
            return;
        }

        fullText = text == null ? "" : text.toString();
        invalidateDisplay();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            invalidateDisplay();
        }
    }

    private void applyDisplay() {
        if (applyingDisplay) return;

        int width = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (width <= 0) return;

        String raw = fullText == null ? "" : fullText;
        if (width == lastDisplayWidth && getText() != null
                && raw.equals(getText().toString())) {
            return;
        }

        lastDisplayWidth = width;
        if (raw.length() == 0 || getPaint().measureText(raw) <= width) {
            setDisplayText(raw);
            return;
        }

        float ellipsisWidth = getPaint().measureText("…");
        if (ellipsisWidth >= width) {
            setDisplayText(raw);
            return;
        }

        int[] range = findVisibleRange(getPaint(), raw, width - ellipsisWidth);
        if (range[0] < MIN_VISIBLE_CHARS_PER_SIDE
                || range[1] > raw.length() - MIN_VISIBLE_CHARS_PER_SIDE
                || range[0] >= range[1]) {
            setDisplayText(raw);
            return;
        }

        SpannableString display = new SpannableString(raw);
        display.setSpan(new MiddleEllipsisSpan(ellipsisWidth), range[0], range[1],
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setDisplayText(display);
    }

    private void setDisplayText(CharSequence text) {
        applyingDisplay = true;
        try {
            super.setText(text, text instanceof Spanned
                    ? BufferType.SPANNABLE : BufferType.NORMAL);
        } finally {
            applyingDisplay = false;
        }
    }

    private static int[] findVisibleRange(Paint paint, String text, float available) {
        int length = text.length();
        if (length < MIN_VISIBLE_CHARS_PER_SIDE * 2 + 1) {
            return new int[]{0, length};
        }

        int bestPrefix = MIN_VISIBLE_CHARS_PER_SIDE;
        int bestSuffix = MIN_VISIBLE_CHARS_PER_SIDE;
        int bestVisible = bestPrefix + bestSuffix;
        float bestBalance = Float.MAX_VALUE;
        float bestUsed = 0f;

        for (int prefix = MIN_VISIBLE_CHARS_PER_SIDE;
             prefix <= length - MIN_VISIBLE_CHARS_PER_SIDE - 1; prefix++) {
            float prefixWidth = paint.measureText(text, 0, prefix);
            if (prefixWidth >= available) break;

            int low = MIN_VISIBLE_CHARS_PER_SIDE;
            int high = length - prefix - MIN_VISIBLE_CHARS_PER_SIDE;
            int bestSuffixForPrefix = -1;

            while (low <= high) {
                int suffix = (low + high) >>> 1;
                float suffixWidth = paint.measureText(text, length - suffix, length);
                if (prefixWidth + suffixWidth <= available) {
                    bestSuffixForPrefix = suffix;
                    low = suffix + 1;
                } else {
                    high = suffix - 1;
                }
            }

            if (bestSuffixForPrefix < MIN_VISIBLE_CHARS_PER_SIDE) continue;

            float suffixWidth = paint.measureText(text,
                    length - bestSuffixForPrefix, length);
            int visible = prefix + bestSuffixForPrefix;
            float used = prefixWidth + suffixWidth;
            float balance = Math.abs(prefixWidth - suffixWidth);

            if (visible > bestVisible
                    || (visible == bestVisible && balance < bestBalance)
                    || (visible == bestVisible && balance == bestBalance && used > bestUsed)) {
                bestPrefix = prefix;
                bestSuffix = bestSuffixForPrefix;
                bestVisible = visible;
                bestBalance = balance;
                bestUsed = used;
            }
        }

        return new int[]{bestPrefix, length - bestSuffix};
    }

    private static final class MiddleEllipsisSpan extends ReplacementSpan {
        private final float width;

        MiddleEllipsisSpan(float width) {
            this.width = width;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            return Math.round(width);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            canvas.drawText("…", x, y, paint);
        }
    }
}
