package wallet.history;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import wallet.main.R;
import wallet.model.TransactionItem;

/** Binds recent wallet transactions. */
public final class RecentTransactionAdapter
        extends RecyclerView.Adapter<RecentTransactionAdapter.Holder> {

    private final List<TransactionItem> items = new ArrayList<>();

    public void submitList(List<TransactionItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recent_transaction, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        TransactionItem item = items.get(position);
        holder.type.setText(item.type);
        holder.amount.setText(item.amount);
        holder.dateStatus.setText(holder.itemView.getContext().getString(
                R.string.transaction_date_status, item.time, item.confirmations));
        holder.txId.setText(holder.itemView.getContext().getString(
                R.string.transaction_tx_id, item.txid));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView type;
        final TextView amount;
        final TextView dateStatus;
        final TextView txId;

        Holder(View view) {
            super(view);
            type = view.findViewById(R.id.recentType);
            amount = view.findViewById(R.id.recentAmount);
            dateStatus = view.findViewById(R.id.recentDateStatus);
            txId = view.findViewById(R.id.recentTxId);
        }
    }
}
