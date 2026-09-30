package wallet.tools;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.crypto.ECKey;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;

import wallet.Constants;
import wallet.main.R;
import wallet.qr.QrCodeGenerator;

/** Creates an offline-style single-key paper wallet with QR codes. */
public final class PaperWalletActivity extends AppCompatActivity {

    private static final int FILE_REQUEST = 4107;
    private final SecureRandom secureRandom = new SecureRandom();
    private EditText importInput;
    private CheckBox bip38CheckBox;
    private Button generateButton;
    private Button importButton;
    private TextView addressValue;
    private TextView privateValue;
    private ImageView addressQr;
    private ImageView privateQr;
    private LinearLayout resultCard;
    private ImageButton addressCopyButton;
    private ImageButton addressEyeButton;
    private ImageButton addressHexButton;
    private ImageButton privateCopyButton;
    private ImageButton privateEyeButton;
    private ImageButton privateHexButton;
    private Button printButton;
    private Button exportButton;
    private String currentAddress;
    private String currentPrivateText;
    private boolean addressVisible = true;
    private boolean privateVisible = false;
    private static final int EXPORT_REQUEST = 4108;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_paper_wallet);
        Toolbar toolbar = findViewById(R.id.toolbar_paper_wallet);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.paper_wallet_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        importInput = findViewById(R.id.paperWalletInput);
        bip38CheckBox = findViewById(R.id.paperWalletBip38);
        generateButton = findViewById(R.id.paperWalletGenerateButton);
        importButton = findViewById(R.id.paperWalletImportButton);
        addressValue = findViewById(R.id.paperWalletAddress);
        privateValue = findViewById(R.id.paperWalletPrivateKey);
        addressQr = findViewById(R.id.paperWalletAddressQr);
        privateQr = findViewById(R.id.paperWalletPrivateQr);
        resultCard = findViewById(R.id.paperWalletResultCard);
        addressCopyButton = findViewById(R.id.paperWalletAddressCopy);
        addressEyeButton = findViewById(R.id.paperWalletAddressEye);
        addressHexButton = findViewById(R.id.paperWalletAddressHex);
        privateCopyButton = findViewById(R.id.paperWalletPrivateCopy);
        privateEyeButton = findViewById(R.id.paperWalletPrivateEye);
        privateHexButton = findViewById(R.id.paperWalletPrivateHex);
        printButton = findViewById(R.id.paperWalletPrintButton);
        exportButton = findViewById(R.id.paperWalletExportButton);

        generateButton.setOnClickListener(v -> createRandomWallet());
        importButton.setOnClickListener(v -> importWalletKey());
        findViewById(R.id.paperWalletScanButton).setOnClickListener(v -> new IntentIntegrator(this)
                .setPrompt(getString(R.string.paper_wallet_scan_prompt))
                .initiateScan());
        findViewById(R.id.paperWalletFileButton).setOnClickListener(v -> openInputFile());
        findViewById(R.id.paperWalletClearButton).setOnClickListener(v -> clearResult());
        addressCopyButton.setOnClickListener(v -> copyToClipboard(getString(R.string.paper_wallet_address_label), currentAddress));
        addressEyeButton.setOnClickListener(v -> toggleAddressVisibility());
        addressHexButton.setOnClickListener(v -> showHexDialog(getString(R.string.paper_wallet_address_hex_title), addressToHex(currentAddress)));
        privateCopyButton.setOnClickListener(v -> copyToClipboard(getString(R.string.paper_wallet_private_label), currentPrivateText));
        privateEyeButton.setOnClickListener(v -> togglePrivateVisibility());
        privateHexButton.setOnClickListener(v -> showPrivateHex());
        printButton.setOnClickListener(v -> printPaperWallet());
        exportButton.setOnClickListener(v -> exportText());
        resultCard.setVisibility(View.GONE);
        updateActionButtons();
    }

    private void createRandomWallet() {
        byte[] bytes = new byte[32];
        do {
            secureRandom.nextBytes(bytes);
        } while (!isValidSecret(bytes));
        try {
            ECKey key = ECKey.fromPrivate(Arrays.copyOf(bytes, bytes.length), true);
            renderKey(key);
        } catch (Exception error) {
            showError(error);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void importWalletKey() {
        String value = importInput.getText().toString().trim();
        if (TextUtils.isEmpty(value)) {
            Toast.makeText(this, R.string.paper_wallet_input_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (value.startsWith("6P")) {
            promptForImportedBip38(value);
            return;
        }
        new Thread(() -> {
            try {
                ECKey key = decodeInput(value);
                runOnUiThread(() -> renderKey(key));
            } catch (Exception error) {
                runOnUiThread(() -> showError(error));
            }
        }, "paper-wallet-import").start();
    }

    private ECKey decodeInput(String value) {
        if (value.startsWith("6P")) {
            throw new IllegalArgumentException(getString(R.string.paper_wallet_bip38_import_requires_passphrase));
        }
        NetworkParameters network = NetworkParameters.of(Constants.IS_PRODUCTION ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET);
        return DumpedPrivateKey.fromBase58(network, value).getKey();
    }

    private void renderKey(ECKey key) {
        if (key == null) {
            return;
        }
        try {
            NetworkParameters network = NetworkParameters.of(Constants.IS_PRODUCTION
                    ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET);
            String address = LegacyAddress.fromKey(network, key).toString();
            String privateText;
            if (bip38CheckBox.isChecked()) {
                if (!key.isCompressed()) {
                    Toast.makeText(this, R.string.paper_wallet_bip38_compressed_required, Toast.LENGTH_LONG).show();
                    return;
                }
                promptForBip38(key, address);
                return;
            } else {
                privateText = key.getPrivateKeyEncoded(network).toString();
            }
            showResult(address, privateText);
        } catch (Exception error) {
            showError(error);
        }
    }

    private void promptForBip38(ECKey key, String address) {
        EditText passphrase = new EditText(this);
        passphrase.setSingleLine(true);
        passphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passphrase.setHint(R.string.bip38_passphrase_hint);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.paper_wallet_bip38_title)
                .setMessage(R.string.paper_wallet_bip38_message)
                .setView(passphrase)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.paper_wallet_encrypt, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String password = passphrase.getText().toString();
            if (password.isEmpty()) {
                Toast.makeText(this, R.string.bip38_passphrase_required, Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            new Thread(() -> {
                try {
                    String encrypted = Bip38Encoder.encrypt(key, address, password);
                    runOnUiThread(() -> showResult(address, encrypted));
                } catch (Exception error) {
                    runOnUiThread(() -> showError(error));
                }
            }, "paper-wallet-bip38").start();
        }));
        dialog.show();
    }

    private void showResult(String address, String privateText) {
        currentAddress = address;
        currentPrivateText = privateText;
        addressVisible = true;
        privateVisible = false;
        updateValueVisibility();
        addressQr.setImageBitmap(QrCodeGenerator.generate(address, 520));
        privateQr.setImageBitmap(QrCodeGenerator.generate(privateText, 520));
        resultCard.setVisibility(View.VISIBLE);
        updateActionButtons();
    }

    private void clearResult() {
        importInput.setText("");
        currentAddress = null;
        currentPrivateText = null;
        addressValue.setText("");
        privateValue.setText("");
        addressQr.setImageDrawable(null);
        privateQr.setImageDrawable(null);
        addressQr.setVisibility(View.GONE);
        privateQr.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
        updateActionButtons();
    }

    private void updateValueVisibility() {
        addressValue.setText(currentAddress == null ? "" : (addressVisible ? currentAddress : "••••••••••••••••••••"));
        privateValue.setText(currentPrivateText == null ? "" : (privateVisible ? currentPrivateText : "••••••••••••••••••••"));
        addressQr.setVisibility(addressVisible && !TextUtils.isEmpty(currentAddress) ? View.VISIBLE : View.GONE);
        privateQr.setVisibility(privateVisible && !TextUtils.isEmpty(currentPrivateText) ? View.VISIBLE : View.GONE);
        addressEyeButton.setImageResource(addressVisible ? R.drawable.ic_visibility_18dp : R.drawable.ic_visibility_off_18dp);
        privateEyeButton.setImageResource(privateVisible ? R.drawable.ic_visibility_18dp : R.drawable.ic_visibility_off_18dp);
    }

    private void updateActionButtons() {
        boolean available = !TextUtils.isEmpty(currentAddress) && !TextUtils.isEmpty(currentPrivateText);
        addressCopyButton.setEnabled(available);
        addressEyeButton.setEnabled(available);
        addressHexButton.setEnabled(available);
        privateCopyButton.setEnabled(available);
        privateEyeButton.setEnabled(available);
        privateHexButton.setEnabled(available);
        printButton.setEnabled(available);
        exportButton.setEnabled(available);
    }

    private void toggleAddressVisibility() {
        addressVisible = !addressVisible;
        updateValueVisibility();
    }

    private void togglePrivateVisibility() {
        privateVisible = !privateVisible;
        updateValueVisibility();
    }

    private void copyToClipboard(String label, String value) {
        if (TextUtils.isEmpty(value)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, R.string.paper_wallet_copied, Toast.LENGTH_SHORT).show();
    }

    private void showHexDialog(String title, String hex) {
        if (TextUtils.isEmpty(hex)) return;
        TextView value = new TextView(this);
        value.setText(hex);
        value.setTextIsSelectable(true);
        value.setTextSize(12);
        value.setPadding(24, 8, 24, 8);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(value)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.paper_wallet_copy_hex, (d, w) -> copyToClipboard(title, hex))
                .show();
    }

    private void showPrivateHex() {
        if (TextUtils.isEmpty(currentPrivateText)) return;
        String title;
        String hex;
        if (currentPrivateText.startsWith("6P")) {
            title = getString(R.string.paper_wallet_bip38_hex_title);
            hex = Base58Check.decodePayloadHex(currentPrivateText);
        } else {
            title = getString(R.string.paper_wallet_private_hex_title);
            hex = WifCodec.privateKeyHex(currentPrivateText);
        }
        showHexDialog(title, hex);
    }

    private String addressToHex(String address) {
        if (TextUtils.isEmpty(address)) return "";
        return Base58Check.addressHashHex(address);
    }

    private void printPaperWallet() {
        if (TextUtils.isEmpty(currentAddress) || TextUtils.isEmpty(currentPrivateText)) return;
        android.print.PrintManager manager = (android.print.PrintManager) getSystemService(Context.PRINT_SERVICE);
        manager.print(getString(R.string.paper_wallet_print_job),
                new PaperWalletPrintAdapter(this, currentAddress, currentPrivateText,
                        addressVisible, privateVisible, Constants.IS_PRODUCTION), null);
    }

    private void exportText() {
        if (TextUtils.isEmpty(currentAddress) || TextUtils.isEmpty(currentPrivateText)) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "bitcoin-paper-wallet.txt");
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    private void writeExport(Uri uri) {
        new Thread(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalArgumentException(getString(R.string.paper_wallet_export_failed));
                String addressHex = addressToHex(currentAddress);
        String privateHex = currentPrivateText.startsWith("6P")
                ? Base58Check.decodePayloadHex(currentPrivateText)
                : WifCodec.privateKeyHex(currentPrivateText);
        String text = getString(R.string.paper_wallet_export_template, currentAddress, addressHex, currentPrivateText, privateHex);
                output.write(text.getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> Toast.makeText(this, R.string.paper_wallet_export_success, Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> showError(error));
            }
        }, "paper-wallet-export").start();
    }

    private void openInputFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            if (!TextUtils.isEmpty(scan.getContents())) {
                String value = scan.getContents().trim();
                if (value.startsWith("6P")) {
                    promptForImportedBip38(value);
                } else {
                    importInput.setText(value);
                    importInput.setSelection(importInput.length());
                }
            }
            return;
        }
        if (requestCode == FILE_REQUEST && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            readInputFile(data.getData());
        } else if (requestCode == EXPORT_REQUEST && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            writeExport(data.getData());
        }
    }

    private void promptForImportedBip38(String encrypted) {
        EditText passphrase = new EditText(this);
        passphrase.setSingleLine(true);
        passphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passphrase.setHint(R.string.bip38_passphrase_hint);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.paper_wallet_bip38_title)
                .setMessage(R.string.paper_wallet_bip38_import_message)
                .setView(passphrase)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.paper_wallet_unlock, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String password = passphrase.getText().toString();
            if (password.isEmpty()) {
                Toast.makeText(this, R.string.bip38_passphrase_required, Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            new Thread(() -> {
                try {
                    NetworkParameters network = NetworkParameters.of(Constants.IS_PRODUCTION ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET);
                    ECKey key = org.bitcoinj.crypto.BIP38PrivateKey.fromBase58(network, encrypted)
                            .decrypt(password);
                    runOnUiThread(() -> renderKey(key));
                } catch (org.bitcoinj.crypto.BIP38PrivateKey.BadPassphraseException error) {
                    runOnUiThread(() -> Toast.makeText(this, R.string.bip38_wrong_passphrase, Toast.LENGTH_LONG).show());
                } catch (Exception error) {
                    runOnUiThread(() -> showError(error));
                }
            }, "paper-wallet-bip38-import").start();
        }));
        dialog.show();
    }

    private void readInputFile(Uri uri) {
        new Thread(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalArgumentException(getString(R.string.paper_wallet_file_failed));
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 4096) throw new IllegalArgumentException(getString(R.string.paper_wallet_file_too_large));
                }
                String value = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
                runOnUiThread(() -> {
                    if (value.startsWith("6P")) {
                        promptForImportedBip38(value);
                    } else {
                        importInput.setText(value);
                        importInput.setSelection(importInput.length());
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError(error));
            }
        }, "paper-wallet-file").start();
    }

    private void showError(Exception error) {
        String message = error.getMessage();
        Toast.makeText(this, getString(R.string.paper_wallet_failed,
                message == null ? error.getClass().getSimpleName() : message), Toast.LENGTH_LONG).show();
    }

    private static boolean isValidSecret(byte[] value) {
        for (byte b : value) if (b != 0) return true;
        return false;
    }
}


final class WifCodec {
    private WifCodec() { }
    static String privateKeyHex(String wif) {
        byte[] payload = Base58Check.decode(wif);
        if (payload.length != 33 && payload.length != 34) throw new IllegalArgumentException("Invalid WIF");
        if (payload[0] != (Constants.IS_PRODUCTION ? (byte) 0x80 : (byte) 0xEF)) throw new IllegalArgumentException("Wrong network WIF");
        return toHex(Arrays.copyOfRange(payload, 1, 33));
    }
    static String toHex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}

final class PaperWalletPrintAdapter extends android.print.PrintDocumentAdapter {
    private final Context context;
    private final String address;
    private final String privateText;
    private final boolean addressVisible;
    private final boolean privateVisible;
    private final boolean mainnet;
    private android.graphics.pdf.PdfDocument document;

    PaperWalletPrintAdapter(Context context, String address, String privateText,
                            boolean addressVisible, boolean privateVisible, boolean mainnet) {
        this.context = context;
        this.address = address;
        this.privateText = privateText;
        this.addressVisible = addressVisible;
        this.privateVisible = privateVisible;
        this.mainnet = mainnet;
    }

    @Override public void onLayout(android.print.PrintAttributes oldAttributes,
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

    @Override public void onWrite(android.print.PageRange[] pages,
                                   android.os.ParcelFileDescriptor destination,
                                   android.os.CancellationSignal cancellationSignal,
                                   WriteResultCallback callback) {
        if (cancellationSignal.isCanceled()) {
            callback.onWriteCancelled();
            return;
        }

        android.graphics.pdf.PdfDocument.PageInfo pageInfo =
                new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create();
        android.graphics.pdf.PdfDocument.Page page = document.startPage(pageInfo);
        android.graphics.Canvas canvas = page.getCanvas();

        // Keep the paper layout deliberately simple. All UI colors come from the app theme.
        int textColor = resolveThemeColor(android.R.attr.textColorPrimary, android.graphics.Color.BLACK);
        int secondaryColor = resolveThemeColor(android.R.attr.textColorSecondary, textColor);
        int lineColor = resolveThemeColor(android.support.v7.appcompat.R.attr.colorControlNormal, secondaryColor);
        int primaryColor = resolveThemeColor(android.support.v7.appcompat.R.attr.colorPrimary, textColor);

        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        paint.setColor(android.graphics.Color.WHITE);
        canvas.drawColor(android.graphics.Color.WHITE);

        // Title.
        paint.setColor(primaryColor);
        paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD));
        paint.setTextSize(22);
        paint.setTextAlign(android.graphics.Paint.Align.LEFT);
        canvas.drawText(context.getString(R.string.paper_wallet_print_title), 36, 58, paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setColor(secondaryColor);
        paint.setTextSize(9.5f);
        canvas.drawText(context.getString(R.string.paper_wallet_print_subtitle), 36, 77, paint);

        paint.setColor(lineColor);
        paint.setStrokeWidth(1);
        canvas.drawLine(36, 96, 559, 96, paint);

        // Address section: text + QR only, with no decorative icons.
        drawSimpleSection(canvas, paint,
                context.getString(R.string.paper_wallet_print_address_type),
                addressVisible ? address : null,
                120, 112);

        paint.setColor(lineColor);
        paint.setStrokeWidth(1);
        canvas.drawLine(36, 421, 559, 421, paint);

        // Private key section: text + QR only, with no decorative icons.
        drawSimpleSection(canvas, paint,
                context.getString(R.string.paper_wallet_print_private_type),
                privateVisible ? privateText : null,
                445, 437);

        // Simple security warning, no icon.
        paint.setColor(secondaryColor);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(9.5f);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        canvas.drawText(context.getString(R.string.paper_wallet_print_warning),
                297.5f, 790, paint);
        paint.setTextAlign(android.graphics.Paint.Align.LEFT);

        document.finishPage(page);
        try (FileOutputStream output = new FileOutputStream(destination.getFileDescriptor())) {
            document.writeTo(output);
            callback.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
        } catch (Exception e) {
            callback.onWriteFailed(e.getMessage());
        } finally {
            document.close();
            document = null;
        }
    }

    private void drawSimpleSection(android.graphics.Canvas canvas,
                                   android.graphics.Paint paint,
                                   String type,
                                   String value,
                                   float labelY,
                                   float qrTop) {
        int textColor = resolveThemeColor(android.R.attr.textColorPrimary, android.graphics.Color.BLACK);
        int secondaryColor = resolveThemeColor(android.R.attr.textColorSecondary, textColor);

        paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD));
        paint.setColor(textColor);
        paint.setTextSize(10.5f);
        paint.setTextAlign(android.graphics.Paint.Align.LEFT);
        canvas.drawText(type, 36, labelY, paint);

        if (TextUtils.isEmpty(value)) {
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
            paint.setColor(secondaryColor);
            paint.setTextSize(10);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            canvas.drawText(context.getString(R.string.paper_wallet_print_hidden),
                    297.5f, qrTop + 105, paint);
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            return;
        }

        Bitmap qr = QrCodeGenerator.generate(value, 520);
        float qrSize = 190;
        float qrLeft = (595f - qrSize) / 2f;
        canvas.drawBitmap(qr, null,
                new android.graphics.RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize), paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setColor(textColor);
        paint.setTextSize(9.5f);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        drawWrappedCentered(canvas, paint, value, 297.5f, qrTop + qrSize + 20, 523, 13);
        paint.setTextAlign(android.graphics.Paint.Align.LEFT);
    }

    private void drawWrappedCentered(android.graphics.Canvas canvas,
                                     android.graphics.Paint paint,
                                     String value,
                                     float centerX,
                                     float startY,
                                     float maxWidth,
                                     float lineHeight) {
        if (TextUtils.isEmpty(value)) return;
        StringBuilder line = new StringBuilder();
        float y = startY;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            String candidate = line.toString() + ch;
            if (paint.measureText(candidate) > maxWidth && line.length() > 0) {
                canvas.drawText(line.toString(), centerX, y, paint);
                y += lineHeight;
                line.setLength(0);
            }
            line.append(ch);
        }
        if (line.length() > 0) {
            canvas.drawText(line.toString(), centerX, y, paint);
        }
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
