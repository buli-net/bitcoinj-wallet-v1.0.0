package wallet.contacts;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.base.Address;
import org.bitcoinj.kits.WalletAppKit;

import java.util.List;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

/** Local Bitcoin address book. No private keys are stored here. */
public final class AddressBookActivity extends AppCompatActivity {
    public static final String EXTRA_SELECTED_ADDRESS = "selected_address";

    private LinearLayout list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_address_book);
        Toolbar toolbar = findViewById(R.id.toolbar_address_book);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.address_book_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.addressBookList);
        findViewById(R.id.addAddressButton).setOnClickListener(v -> showAddDialog());
        render();
    }

    private void render() {
        list.removeAllViews();
        List<AddressBookStore.Entry> entries = AddressBookStore.load(this);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.address_book_empty);
            empty.setPadding(dp(12), dp(12), dp(12), dp(12));
            list.addView(empty);
            return;
        }
        for (AddressBookStore.Entry entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(params);

            TextView name = new TextView(this);
            name.setText(entry.name);
            name.setTextSize(16);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(name);

            TextView address = new TextView(this);
            address.setText(entry.address);
            address.setTextSize(13);
            address.setTypeface(android.graphics.Typeface.MONOSPACE);
            address.setTextIsSelectable(true);
            address.setSingleLine(true);
            address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(address);

            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(android.view.Gravity.END);
            Button select = new Button(this);
            select.setText(R.string.address_book_use);
            select.setOnClickListener(v -> selectAddress(entry.address));
            Button delete = new Button(this);
            delete.setText(R.string.address_book_delete);
            delete.setOnClickListener(v -> confirmDelete(entry));
            actions.addView(select);
            actions.addView(delete);
            row.addView(actions);
            list.addView(row);
        }
    }

    private void showAddDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), 0);

        EditText name = new EditText(this);
        name.setHint(R.string.address_book_name_hint);
        content.addView(name);

        EditText address = new EditText(this);
        address.setHint(R.string.address_book_address_hint);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        content.addView(address);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.address_book_add_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.address_book_save, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = name.getText().toString().trim();
            String value = address.getText().toString().trim();
            if (TextUtils.isEmpty(label) || TextUtils.isEmpty(value)) {
                Toast.makeText(this, R.string.address_book_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isValidAddress(value)) {
                Toast.makeText(this, R.string.address_book_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!AddressBookStore.add(this, label, value)) {
                Toast.makeText(this, R.string.address_book_duplicate, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            render();
        }));
        dialog.show();
    }

    private boolean isValidAddress(String value) {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) return false;
        try {
            Address.fromString(kit.params(), value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void confirmDelete(AddressBookStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.address_book_delete_title)
                .setMessage(entry.name + "\n" + entry.address)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.address_book_delete, (d, w) -> {
                    AddressBookStore.remove(this, entry.address);
                    render();
                }).show();
    }

    private void selectAddress(String address) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_SELECTED_ADDRESS, address));
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
