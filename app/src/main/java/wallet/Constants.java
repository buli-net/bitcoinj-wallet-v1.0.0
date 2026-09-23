/**
 * Release documentation:
 * Release documentation for the wallet-wide runtime constants.
 * Defines the persistent wallet filename and the active Bitcoin network mode used by the application.
 * These values are intentionally centralized so release configuration is visible in one source file.
 */

package wallet;

public class Constants {
    public static final String WALLET_NAME = "users_wallet";

    public static boolean IS_PRODUCTION = true;
}
