/**
 * Release documentation:
 * Release documentation for the base view contract.
 * Provides the common presenter attachment operation used by the application presentation layer.
 */

package wallet.base;

public interface BaseView<T extends BasePresenter> {
    void setPresenter(T presenter);
}
