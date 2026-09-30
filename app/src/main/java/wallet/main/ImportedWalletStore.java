package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent names for imported WIF wallets. The private keys remain in bitcoinj Wallet. */
public final class ImportedWalletStore {
    private static final String PREFS = "imported_wif_wallets";
    private static final String PREFIX = "name.";

    private ImportedWalletStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getName(Context context, String address, int fallbackIndex) {
        if (address == null || address.trim().isEmpty()) {
            return context.getString(R.string.imported_wallet_default_name);
        }
        String value = prefs(context).getString(PREFIX + address.trim(), null);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return context.getString(R.string.imported_wallet_default_name_numbered, Math.max(1, fallbackIndex));
    }

    public static void setName(Context context, String address, String name) {
        if (address == null || address.trim().isEmpty() || name == null) return;
        String clean = name.trim();
        if (clean.isEmpty()) return;
        prefs(context).edit().putString(PREFIX + address.trim(), clean).apply();
    }

    public static void remove(Context context, String address) {
        if (address == null || address.trim().isEmpty()) return;
        prefs(context).edit().remove(PREFIX + address.trim()).apply();
    }
}
