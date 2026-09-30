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
                                  ParcelFileDescriptor destination,
                                  android.os.CancellationSignal cancellationSignal,
                                  WriteResultCallback callback) {
        if (cancellationSignal.isCanceled()) {
            callback.onWriteCancelled();
            return;
        }

        android.graphics.pdf.PdfDocument.Page page = document.startPage(
                new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create());
        android.graphics.Canvas canvas = page.getCanvas();

        // The printed page follows the app theme. QR modules remain pure black/white
        // so the printed codes stay reliable on real paper.
        int primary = resolveThemeColor(android.support.v7.appcompat.R.attr.colorPrimary,
                resolveThemeColor(android.R.attr.textColorPrimary, android.graphics.Color.BLACK));
        int accent = resolveThemeColor(android.support.v7.appcompat.R.attr.colorAccent, primary);
        int text = resolveThemeColor(android.R.attr.textColorPrimary, primary);
        int secondary = resolveThemeColor(android.R.attr.textColorSecondary, text);
        int control = resolveThemeColor(android.support.v7.appcompat.R.attr.colorControlNormal, secondary);
        int background = resolveThemeColor(android.R.attr.colorBackground, android.graphics.Color.WHITE);
        int soft = blendWithWhite(accent, 0.90f);
        int soft2 = blendWithWhite(accent, 0.95f);

        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setStyle(android.graphics.Paint.Style.FILL);

        // Header: use the exact Bitcoin XML drawable already used by the app.
        paint.setColor(background);
        canvas.drawColor(background);
        paint.setColor(accent);
        canvas.drawCircle(58, 56, 25, paint);
        drawThemeDrawable(canvas, R.drawable.ic_bitcoin_logo, 39, 37, 38, android.graphics.Color.WHITE);

        paint.setColor(primary);
        paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD));
        paint.setTextSize(23);
        paint.setTextAlign(android.graphics.Paint.Align.LEFT);
        canvas.drawText(context.getString(R.string.paper_wallet_print_title), 100, 59, paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setColor(secondary);
        paint.setTextSize(10.5f);
        canvas.drawText(context.getString(R.string.paper_wallet_print_subtitle), 100, 79, paint);

        // Network badge.
        float badgeLeft = 445;
        float badgeRight = 559;
        paint.setColor(soft);
        canvas.drawRoundRect(new android.graphics.RectF(badgeLeft, 40, badgeRight, 70),
                15, 15, paint);
        paint.setColor(primary);
        paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD));
        paint.setTextSize(9.5f);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        canvas.drawText(context.getString(mainnet
                        ? R.string.paper_wallet_print_network_mainnet
                        : R.string.paper_wallet_print_network_testnet),
                (badgeLeft + badgeRight) / 2f, 59, paint);
        paint.setTextAlign(android.graphics.Paint.Align.LEFT);

        paint.setColor(blendWithWhite(accent, 0.35f));
        paint.setStrokeWidth(1.1f);
        canvas.drawLine(36, 105, 559, 105, paint);

        // Address section.
        drawPrintSection(canvas, paint, primary, accent, text, secondary, control,
                R.drawable.ic_menu_paper_wallet_24dp,
                context.getString(R.string.paper_wallet_address_label),
                context.getString(R.string.paper_wallet_print_address_type),
                addressVisible ? address : null,
                122);

        paint.setColor(blendWithWhite(accent, 0.35f));
        paint.setStrokeWidth(1.1f);
        canvas.drawLine(36, 402, 559, 402, paint);

        // Private key section. Never truncate the value: wrap it to multiple lines.
        drawPrintSection(canvas, paint, primary, accent, text, secondary, control,
                R.drawable.ic_menu_key_24dp,
                context.getString(R.string.paper_wallet_private_label),
                context.getString(R.string.paper_wallet_print_private_type),
                privateVisible ? privateText : null,
                419);

        // Security notice.
        paint.setColor(soft2);
        canvas.drawRoundRect(new android.graphics.RectF(36, 733, 559, 783),
                12, 12, paint);
        drawThemeDrawable(canvas, R.drawable.ic_menu_key_24dp, 50, 746, 24, accent);
        paint.setColor(accent);
        paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD));
        paint.setTextSize(10.5f);
        canvas.drawText(context.getString(R.string.paper_wallet_result_title), 84, 754, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setColor(secondary);
        paint.setTextSize(8.8f);
        canvas.drawText(context.getString(R.string.paper_wallet_print_warning), 84, 770, paint);

        // Footer.
        paint.setColor(blendWithWhite(control, 0.45f));
        paint.setStrokeWidth(1);
        canvas.drawLine(36, 802, 559, 802, paint);
        paint.setColor(secondary);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        paint.setTextSize(8.5f);
        canvas.drawText(context.getString(R.string.paper_wallet_print_title), 297.5f, 820, paint);
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

    private void drawPrintSection(android.graphics.Canvas canvas,
                                  android.graphics.Paint paint,
                                  int primary, int accent, int text, int secondary, int control,
                                  int iconRes, String label, String type, String value, float top) {
        // Small theme-tinted icon tile.
        paint.setColor(blendWithWhite(accent, 0.88f));
        canvas.drawRoundRect(new android.graphics.RectF(36, top, 88, top + 52),
                13, 13, paint);
        drawThemeDrawable(canvas, iconRes, 50, top + 14, 24, control);

        paint.setColor(primary);
        paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD));
        paint.setTextSize(17);
        canvas.drawText(label, 105, top + 24, paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setColor(secondary);
        paint.setTextSize(11);
        canvas.drawText(type, 105, top + 44, paint);

        if (TextUtils.isEmpty(value)) {
            paint.setColor(secondary);
            paint.setTextSize(10);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            canvas.drawText(context.getString(R.string.paper_wallet_print_hidden), 297.5f, top + 151, paint);
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            return;
        }

        Bitmap qr = QrCodeGenerator.generate(value, 240);
        float qrSize = 190;
        float qrLeft = 202.5f;
        float qrTop = top + 67;
        canvas.drawBitmap(qr, null,
                new android.graphics.RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize), paint);

        paint.setColor(text);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        paint.setTextSize(10.5f);
        drawWrappedCentered(canvas, paint, value, 297.5f, qrTop + qrSize + 26, 500);
        paint.setTextAlign(android.graphics.Paint.Align.LEFT);
    }

    private void drawThemeDrawable(android.graphics.Canvas canvas, int drawableRes,
                                   int left, float top, int size, int tint) {
        android.graphics.drawable.Drawable drawable = context.getResources().getDrawable(drawableRes);
        drawable = drawable.mutate();
        drawable.setTint(tint);
        drawable.setBounds(left, Math.round(top), left + size, Math.round(top) + size);
        drawable.draw(canvas);
    }

    private void drawWrappedCentered(android.graphics.Canvas canvas, android.graphics.Paint paint,
                                     String text, float centerX, float startY, float maxWidth) {
        if (TextUtils.isEmpty(text)) return;
        StringBuilder line = new StringBuilder();
        float y = startY;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String candidate = line.toString() + ch;
            if (paint.measureText(candidate) > maxWidth && line.length() > 0) {
                canvas.drawText(line.toString(), centerX, y, paint);
                y += paint.getTextSize() + 4;
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

    private static int blendWithWhite(int color, float whiteRatio) {
        int r = android.graphics.Color.red(color);
        int g = android.graphics.Color.green(color);
        int b = android.graphics.Color.blue(color);
        int nr = Math.round(r + (255 - r) * whiteRatio);
        int ng = Math.round(g + (255 - g) * whiteRatio);
        int nb = Math.round(b + (255 - b) * whiteRatio);
        return android.graphics.Color.rgb(nr, ng, nb);
    }

    private static void drawWrapped(android.graphics.Canvas canvas,
                                    android.graphics.Paint paint,
                                    String text,
                                    float x,
                                    float y,
                                    float width) {
        android.text.TextPaint tp = new android.text.TextPaint(paint);
        android.text.StaticLayout layout = new android.text.StaticLayout(
                text, tp, (int) width,
                android.text.Layout.Alignment.ALIGN_NORMAL,
                1.08f, 0, false);
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }
}

/** BIP38 non-EC-multiply encryption for compressed private keys. */
final class Bip38Encoder {
    private static final int N = 16384;
    private static final int R = 8;
    private static final int P = 8;

    private Bip38Encoder() { }

    static String encrypt(ECKey key, String address, String passphrase) {
        try {
            byte[] salt = Arrays.copyOf(Hashing.doubleSha256(address.getBytes(StandardCharsets.US_ASCII)), 4);
            String normalized = Normalizer.normalize(passphrase, Normalizer.Form.NFC);
            byte[] derived = Scrypt.scrypt(normalized.getBytes(StandardCharsets.UTF_8), salt, N, R, P, 64);
            byte[] secret = key.getPrivKeyBytes();
            byte[] block1 = new byte[16];
            byte[] block2 = new byte[16];
            for (int i = 0; i < 16; i++) {
                block1[i] = (byte) (secret[i] ^ derived[i]);
                block2[i] = (byte) (secret[i + 16] ^ derived[i + 16]);
            }
            Aes256 aes = new Aes256(derived, 32);
            block1 = aes.encrypt(block1);
            block2 = aes.encrypt(block2);
            byte[] payload = new byte[39];
            payload[0] = 0x01;
            payload[1] = 0x42;
            payload[2] = (byte) 0xE0; // compressed + no EC multiply
            System.arraycopy(salt, 0, payload, 3, 4);
            System.arraycopy(block1, 0, payload, 7, 16);
            System.arraycopy(block2, 0, payload, 23, 16);
            return Base58Check.encode(payload);
        } finally {
            // The passphrase is not persisted; callers only retain the encrypted result.
        }
    }
}

final class Hashing {
    private Hashing() { }
    static byte[] doubleSha256(byte[] input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(digest.digest(input));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

final class Base58Check {
    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private Base58Check() { }
    static String encode(byte[] payload) {
        byte[] data = Arrays.copyOf(payload, payload.length + 4);
        byte[] checksum = Hashing.doubleSha256(payload);
        System.arraycopy(checksum, 0, data, payload.length, 4);
        return encodeRaw(data);
    }
    static byte[] decode(String value) {
        if (TextUtils.isEmpty(value)) throw new IllegalArgumentException("Empty Base58 value");
        byte[] decoded = decodeRaw(value);
        if (decoded.length < 5) throw new IllegalArgumentException("Invalid Base58Check value");
        byte[] payload = Arrays.copyOf(decoded, decoded.length - 4);
        byte[] checksum = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);
        byte[] expected = Hashing.doubleSha256(payload);
        if (!Arrays.equals(checksum, Arrays.copyOf(expected, 4))) throw new IllegalArgumentException("Invalid Base58Check checksum");
        return payload;
    }
    static String decodePayloadHex(String value) {
        return WifCodec.toHex(decode(value));
    }
    static String addressHashHex(String address) {
        byte[] payload = decode(address);
        if (payload.length != 21) throw new IllegalArgumentException("Unsupported Bitcoin address format");
        return WifCodec.toHex(Arrays.copyOfRange(payload, 1, payload.length));
    }
    private static byte[] decodeRaw(String input) {
        java.math.BigInteger value = java.math.BigInteger.ZERO;
        java.math.BigInteger base = java.math.BigInteger.valueOf(58);
        for (int i = 0; i < input.length(); i++) {
            int index = new String(ALPHABET).indexOf(input.charAt(i));
            if (index < 0) throw new IllegalArgumentException("Invalid Base58 character");
            value = value.multiply(base).add(java.math.BigInteger.valueOf(index));
        }
        byte[] raw = value.toByteArray();
        if (raw.length > 0 && raw[0] == 0) raw = Arrays.copyOfRange(raw, 1, raw.length);
        int leading = 0;
        while (leading < input.length() && input.charAt(leading) == '1') leading++;
        byte[] out = new byte[leading + raw.length];
        System.arraycopy(raw, 0, out, leading, raw.length);
        return out;
    }
    private static String encodeRaw(byte[] input) {
        java.math.BigInteger value = new java.math.BigInteger(1, input);
        StringBuilder result = new StringBuilder();
        java.math.BigInteger base = java.math.BigInteger.valueOf(58);
        while (value.signum() > 0) {
            java.math.BigInteger[] div = value.divideAndRemainder(base);
            result.append(ALPHABET[div[1].intValue()]);
            value = div[0];
        }
        for (byte b : input) {
            if (b == 0) result.append('1'); else break;
        }
        return result.reverse().toString();
    }
}

final class Aes256 {
    private final javax.crypto.Cipher cipher;
    Aes256(byte[] key, int offset) {
        try {
            cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(Arrays.copyOfRange(key, offset, offset + 32), "AES"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    byte[] encrypt(byte[] block) {
        try { return cipher.doFinal(block); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

final class Scrypt {
    private Scrypt() { }

    static byte[] scrypt(byte[] password, byte[] salt, int n, int r, int p, int dkLen) {
        if (n <= 1 || (n & (n - 1)) != 0) throw new IllegalArgumentException("Invalid scrypt N");
        int blockSize = 128 * r;
        byte[] b = pbkdf2(password, salt, p * blockSize);
        byte[] xy = new byte[256 * r];
        byte[] v = new byte[128 * r * n];
        for (int i = 0; i < p; i++) romix(b, i * blockSize, r, n, v, xy);
        return pbkdf2(password, b, dkLen);
    }

    private static byte[] pbkdf2(byte[] password, byte[] salt, int length) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(password, "HmacSHA256"));
            byte[] out = new byte[length];
            int blocks = (length + 31) / 32;
            byte[] u = new byte[salt.length + 4];
            System.arraycopy(salt, 0, u, 0, salt.length);
            int pos = 0;
            for (int i = 1; i <= blocks; i++) {
                u[salt.length] = (byte)(i >>> 24); u[salt.length + 1] = (byte)(i >>> 16);
                u[salt.length + 2] = (byte)(i >>> 8); u[salt.length + 3] = (byte)i;
                byte[] t = mac.doFinal(u);
                byte[] x = Arrays.copyOf(t, t.length);
                for (int j = 1; j < 1; j++) { }
                int copy = Math.min(32, length - pos);
                System.arraycopy(x, 0, out, pos, copy);
                pos += copy;
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void romix(byte[] b, int offset, int r, int n, byte[] v, byte[] xy) {
        int block = 128 * r;
        byte[] x = new byte[block];
        System.arraycopy(b, offset, x, 0, block);
        for (int i = 0; i < n; i++) {
            System.arraycopy(x, 0, v, i * block, block);
            blockMix(x, xy, r);
        }
        for (int i = 0; i < n; i++) {
            int j = integerify(x, r) & (n - 1);
            for (int k = 0; k < block; k++) x[k] ^= v[j * block + k];
            blockMix(x, xy, r);
        }
        System.arraycopy(x, 0, b, offset, block);
    }

    private static int integerify(byte[] x, int r) {
        int index = (2 * r - 1) * 64;
        return (x[index] & 0xff) | ((x[index + 1] & 0xff) << 8) | ((x[index + 2] & 0xff) << 16) | ((x[index + 3] & 0xff) << 24);
    }

    private static void blockMix(byte[] b, byte[] y, int r) {
        byte[] x = new byte[64];
        System.arraycopy(b, (2 * r - 1) * 64, x, 0, 64);
        for (int i = 0; i < 2 * r; i++) {
            for (int j = 0; j < 64; j++) x[j] ^= b[i * 64 + j];
            salsa208(x);
            System.arraycopy(x, 0, y, i * 64, 64);
        }
        for (int i = 0; i < r; i++) System.arraycopy(y, (2 * i) * 64, b, i * 64, 64);
        for (int i = 0; i < r; i++) System.arraycopy(y, (2 * i + 1) * 64, b, (i + r) * 64, 64);
    }

    private static void salsa208(byte[] b) {
        int[] x = new int[16];
        for (int i = 0; i < 16; i++) x[i] = le(b, i * 4);
        int[] z = Arrays.copyOf(x, 16);
        for (int i = 0; i < 8; i += 2) {
            z[4] ^= Integer.rotateLeft(z[0] + z[12], 7);
            z[8] ^= Integer.rotateLeft(z[4] + z[0], 9);
            z[12] ^= Integer.rotateLeft(z[8] + z[4], 13);
            z[0] ^= Integer.rotateLeft(z[12] + z[8], 18);
            z[9] ^= Integer.rotateLeft(z[5] + z[1], 7);
            z[13] ^= Integer.rotateLeft(z[9] + z[5], 9);
            z[1] ^= Integer.rotateLeft(z[13] + z[9], 13);
            z[5] ^= Integer.rotateLeft(z[1] + z[13], 18);
            z[14] ^= Integer.rotateLeft(z[10] + z[6], 7);
            z[2] ^= Integer.rotateLeft(z[14] + z[10], 9);
            z[6] ^= Integer.rotateLeft(z[2] + z[14], 13);
            z[10] ^= Integer.rotateLeft(z[6] + z[2], 18);
            z[3] ^= Integer.rotateLeft(z[15] + z[11], 7);
            z[7] ^= Integer.rotateLeft(z[3] + z[15], 9);
            z[11] ^= Integer.rotateLeft(z[7] + z[3], 13);
            z[15] ^= Integer.rotateLeft(z[11] + z[7], 18);
            z[1] ^= Integer.rotateLeft(z[0] + z[3], 7);
            z[2] ^= Integer.rotateLeft(z[1] + z[0], 9);
            z[3] ^= Integer.rotateLeft(z[2] + z[1], 13);
            z[0] ^= Integer.rotateLeft(z[3] + z[2], 18);
            z[6] ^= Integer.rotateLeft(z[5] + z[4], 7);
            z[7] ^= Integer.rotateLeft(z[6] + z[5], 9);
            z[4] ^= Integer.rotateLeft(z[7] + z[6], 13);
            z[5] ^= Integer.rotateLeft(z[4] + z[7], 18);
            z[11] ^= Integer.rotateLeft(z[10] + z[9], 7);
            z[8] ^= Integer.rotateLeft(z[11] + z[10], 9);
            z[9] ^= Integer.rotateLeft(z[8] + z[11], 13);
            z[10] ^= Integer.rotateLeft(z[9] + z[8], 18);
            z[12] ^= Integer.rotateLeft(z[15] + z[14], 7);
            z[13] ^= Integer.rotateLeft(z[12] + z[15], 9);
            z[14] ^= Integer.rotateLeft(z[13] + z[12], 13);
            z[15] ^= Integer.rotateLeft(z[14] + z[13], 18);
        }
        for (int i = 0; i < 16; i++) putLe(b, i * 4, z[i] + x[i]);
    }
    private static int le(byte[] b, int i) { return (b[i]&255)|((b[i+1]&255)<<8)|((b[i+2]&255)<<16)|((b[i+3]&255)<<24); }
    private static void putLe(byte[] b, int i, int v) { b[i]=(byte)v; b[i+1]=(byte)(v>>>8); b[i+2]=(byte)(v>>>16); b[i+3]=(byte)(v>>>24); }
}
