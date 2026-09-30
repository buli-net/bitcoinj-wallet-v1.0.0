package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persistent names and address registry for imported WIF wallets. */
public final class ImportedWalletStore {
    private static final String PREFS = "imported_wif_wallets";
    private static final String PREFIX = "name.";
    private static final String KEY_ADDRESSES = "addresses";

    private ImportedWalletStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getName(Context context, String address, int fallbackIndex) {
        if (address == null || address.trim().isEmpty()) {
            return context.getString(R.string.imported_wallet_default_name);
        }
        String cleanAddress = address.trim();
        String value = prefs(context).getString(PREFIX + cleanAddress, null);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return context.getString(R.string.imported_wallet_default_name_numbered, Math.max(1, fallbackIndex));
    }

    /** Registers an imported address so the management screen can render it independently of UI state. */
    public static void register(Context context, String address) {
        if (address == null || address.trim().isEmpty()) return;
        String cleanAddress = address.trim();
        Set<String> current = prefs(context).getStringSet(KEY_ADDRESSES, null);
        LinkedHashSet<String> updated = current == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(current);
        updated.add(cleanAddress);
        prefs(context).edit().putStringSet(KEY_ADDRESSES, updated).apply();
    }

    /** Returns the persisted imported-address registry in stable insertion order. */
    public static List<String> getAddresses(Context context) {
        Set<String> current = prefs(context).getStringSet(KEY_ADDRESSES, null);
        if (current == null || current.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(current);
    }

    public static void setName(Context context, String address, String name) {
        if (address == null || address.trim().isEmpty() || name == null) return;
        String cleanAddress = address.trim();
        String clean = name.trim();
        if (clean.isEmpty()) return;
        register(context, cleanAddress);
        prefs(context).edit().putString(PREFIX + cleanAddress, clean).apply();
    }

    public static void remove(Context context, String address) {
        if (address == null || address.trim().isEmpty()) return;
        String cleanAddress = address.trim();
        Set<String> current = prefs(context).getStringSet(KEY_ADDRESSES, null);
        LinkedHashSet<String> updated = current == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(current);
        updated.remove(cleanAddress);
        prefs(context).edit()
                .remove(PREFIX + cleanAddress)
                .putStringSet(KEY_ADDRESSES, updated)
                .apply();
    }
}
