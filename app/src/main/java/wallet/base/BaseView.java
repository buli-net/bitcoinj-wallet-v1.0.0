/** Common view contract for presenter attachment. */


package wallet.base;

public interface BaseView<T extends BasePresenter> {
    void setPresenter(T presenter);
}
