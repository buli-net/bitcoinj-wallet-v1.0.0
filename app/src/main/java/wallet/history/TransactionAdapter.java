/** Binds transaction rows to the history RecyclerView. */


package wallet.history;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.List;

import wallet.main.R;

public final class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.Holder> {

    private final List<TransactionItem> items;

    public TransactionAdapter(List<TransactionItem> items) {
        this.items = items;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transaction, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        TransactionItem item = items.get(position);
        holder.type.setText(item.type);
        holder.amount.setText(item.amount);
        holder.time.setText(item.time);
        holder.confirmations.setText(item.confirmations);
        holder.txid.setText(item.txid);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView type;
        final TextView amount;
        final TextView time;
        final TextView confirmations;
        final TextView txid;

        Holder(View view) {
            super(view);
            type = view.findViewById(R.id.tvTxType);
            amount = view.findViewById(R.id.tvTxAmount);
            time = view.findViewById(R.id.tvTxTime);
            confirmations = view.findViewById(R.id.tvTxConf);
            txid = view.findViewById(R.id.tvTxId);
        }
    }
}
