/**
 * Release documentation:
 * Release documentation for the presenter lifecycle contract.
 * Defines the subscription lifecycle shared by presentation components that attach to an Android view.
 */

package wallet.base;

public interface BasePresenter {
    void subscribe();
    void unsubscribe();
}
