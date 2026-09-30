package wallet.transaction;

import android.content.Context;
import android.content.SharedPreferences;

public final class TransactionNotesStore {
    private static final String PREFS="transaction_notes";
    private static final String PREFIX="note_";
    private TransactionNotesStore() {}
    public static String get(Context c,String txid){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(PREFIX+txid,"");}
    public static void put(Context c,String txid,String note){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(PREFIX+txid,note).apply();}
    public static void remove(Context c,String txid){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(PREFIX+txid).apply();}
}
