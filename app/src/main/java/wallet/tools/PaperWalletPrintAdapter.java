package wallet.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;

import java.io.FileOutputStream;

import wallet.main.R;
import wallet.qr.QrCodeGenerator;

/** Simple single-page paper-wallet print layout. */
final class PaperWalletPrintAdapter extends android.print.PrintDocumentAdapter {
    private final Context context;
    private final String address;
    private final String privateText;
    private final boolean addressVisible;
    private final boolean privateVisible;
    private android.graphics.pdf.PdfDocument document;

    PaperWalletPrintAdapter(Context context, String address, String privateText,
                            boolean addressVisible, boolean privateVisible, boolean mainnet) {
        this.context = context;
        this.address = address;
        this.privateText = privateText;
        this.addressVisible = addressVisible;
        this.privateVisible = privateVisible;
    }

    @Override
    public void onLayout(android.print.PrintAttributes oldAttributes,
                          android.print.PrintAttributes newAttributes,
                          android.os.CancellationSignal cancellationSignal,
                          LayoutResultCallback callback, Bundle extras) {
        if (cancellationSignal.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        document = new android.graphics.pdf.PdfDocument();
        callback.onLayoutFinished(new android.print.PrintDocumentInfo.Builder("bitcoin-paper-wallet.pdf")
                .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build(), true);
    }

    @Override
    public void onWrite(android.print.PageRange[] pages,
                        android.os.ParcelFileDescriptor destination,
                        android.os.CancellationSignal cancellationSignal,
                        WriteResultCallback callback) {
        if (cancellationSignal.isCanceled()) {
            callback.onWriteCancelled();
            return;
        }

        Bitmap addressQr = addressVisible && !TextUtils.isEmpty(address)
                ? QrCodeGenerator.generate(address, 700) : null;
        Bitmap privateQr = privateVisible && !TextUtils.isEmpty(privateText)
                ? QrCodeGenerator.generate(privateText, 700) : null;

        // The printed text uses the same ink as the generated QR code. No UI color is hardcoded.
        Bitmap referenceQr = addressQr != null ? addressQr : privateQr;
        int inkColor = referenceQr != null
                ? findQrInk(referenceQr)
                : resolveThemeColor(android.R.attr.textColorPrimary, android.graphics.Color.BLACK);
        int paperColor = referenceQr != null
                ? findQrPaper(referenceQr)
                : resolveThemeColor(android.R.attr.colorBackground, android.graphics.Color.WHITE);

        android.graphics.pdf.PdfDocument.PageInfo pageInfo =
                new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create();
        android.graphics.pdf.PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(paperColor);
        canvas.drawColor(paperColor);

        // Header: deliberately minimal, matching the supplied reference.
        paint.setColor(inkColor);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(21);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(context.getString(R.string.paper_wallet_print_title), 36, 58, paint);

        String addressType = detectAddressType(address);
        String privateType = detectPrivateType(privateText);
        drawSection(canvas, paint, context.getString(R.string.paper_wallet_print_public_key_format, addressType),
                addressVisible ? address : null, addressQr, 96, 112, inkColor);

        drawRule(canvas, paint, inkColor, 36, 407, 559, 407, 0.9f);

        drawSection(canvas, paint, context.getString(R.string.paper_wallet_print_private_key_format, privateType),
                privateVisible ? privateText : null, privateQr, 435, 451, inkColor);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(9.5f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(context.getString(R.string.paper_wallet_print_warning),
                297.5f, 790, paint);

        document.finishPage(page);
        try (FileOutputStream output = new FileOutputStream(destination.getFileDescriptor())) {
            document.writeTo(output);
            callback.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
        } catch (Exception e) {
            callback.onWriteFailed(e.getMessage());
        } finally {
            document.close();
            document = null;
            if (addressQr != null) addressQr.recycle();
            if (privateQr != null) privateQr.recycle();
        }
    }

    private void drawSection(Canvas canvas, Paint paint, String label, String value,
                             Bitmap qr, float labelY, float qrTop, int inkColor) {
        paint.setColor(inkColor);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(11.5f);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(label, 36, labelY, paint);

        if (TextUtils.isEmpty(value) || qr == null) {
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(10);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(context.getString(R.string.paper_wallet_print_hidden),
                    297.5f, qrTop + 104, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        // Large centered QR, with the same proportions as the reference image.
        float qrSize = 220;
        float qrLeft = (595f - qrSize) / 2f;
        paint.setFilterBitmap(false);
        canvas.drawBitmap(qr, null,
                new RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize), paint);

        // Print the complete value on one centered line. Shrink only if the device font requires it.
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.CENTER);
        float textSize = 10.5f;
        while (textSize > 7.0f) {
            paint.setTextSize(textSize);
            if (paint.measureText(value) <= 523f) break;
            textSize -= 0.5f;
        }
        canvas.drawText(value, 297.5f, qrTop + qrSize + 22, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    /** Detect the address type from the actual address being printed. */
    private String detectAddressType(String address) {
        if (TextUtils.isEmpty(address)) return context.getString(R.string.paper_wallet_print_type_unknown);
        String value = address.trim().toLowerCase(java.util.Locale.US);
        if (value.startsWith("bc1p") || value.startsWith("tb1p")) return "P2TR";
        if (value.startsWith("bc1q") || value.startsWith("tb1q")) return "P2WPKH/P2WSH";
        if (value.startsWith("3") || value.startsWith("2")) return "P2SH";
        if (value.startsWith("1") || value.startsWith("m") || value.startsWith("n")) return "P2PKH";
        return context.getString(R.string.paper_wallet_print_type_unknown);
    }

    /** Detect the private-key encoding actually stored in the paper wallet. */
    private String detectPrivateType(String privateText) {
        if (TextUtils.isEmpty(privateText)) return context.getString(R.string.paper_wallet_print_type_unknown);
        String value = privateText.trim();
        if (value.startsWith("6P")) return "BIP38";
        if (value.startsWith("5") || value.startsWith("K") || value.startsWith("L")
                || value.startsWith("9") || value.startsWith("c")) return "WIF";
        return context.getString(R.string.paper_wallet_print_type_unknown);
    }

    private static void drawRule(Canvas canvas, Paint paint, int color,
                                 float startX, float startY, float stopX, float stopY, float width) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        canvas.drawLine(startX, startY, stopX, stopY, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private static int findQrInk(Bitmap bitmap) {
        if (bitmap != null) {
            int stepX = Math.max(1, bitmap.getWidth() / 80);
            int stepY = Math.max(1, bitmap.getHeight() / 80);
            for (int y = 0; y < bitmap.getHeight(); y += stepY) {
                for (int x = 0; x < bitmap.getWidth(); x += stepX) {
                    int color = bitmap.getPixel(x, y);
                    if (android.graphics.Color.alpha(color) > 0 &&
                            android.graphics.Color.red(color) < 128 &&
                            android.graphics.Color.green(color) < 128 &&
                            android.graphics.Color.blue(color) < 128) {
                        return color;
                    }
                }
            }
        }
        return 0xFF000000;
    }

    private static int findQrPaper(Bitmap bitmap) {
        if (bitmap != null) {
            int color = bitmap.getPixel(0, 0);
            if (android.graphics.Color.alpha(color) > 0) return color;
        }
        return android.graphics.Color.WHITE;
    }

    private int resolveThemeColor(int attribute, int fallback) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                try {
                    return context.getResources().getColor(value.resourceId);
                } catch (Exception ignored) { }
            }
            return value.data;
        }
        return fallback;
    }
}
