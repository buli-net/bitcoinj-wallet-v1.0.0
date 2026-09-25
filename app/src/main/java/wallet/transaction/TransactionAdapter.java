package wallet.transaction;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import wallet.main.R;
import wallet.model.TransactionItem;

/** Binds transaction rows to the wallet transaction list. */
public final class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.Holder> {

    private final List<TransactionItem> items;
    private final Set<String> expandedTxIds = new HashSet<>();

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

        holder.detailTxid.setText(item.detailTxid);
        holder.detailType.setText(item.detailType);
        holder.detailAmount.setText(item.detailAmount);
        holder.detailTime.setText(item.detailTime);
        holder.detailStatus.setText(item.detailStatus);
        holder.detailFee.setText(item.detailFee);
        holder.detailPeers.setText(item.detailPeers);
        holder.detailInputs.setText(item.detailInputs);
        holder.detailOutputs.setText(item.detailOutputs);
        holder.detailSize.setText(item.detailSize);
        holder.detailVersion.setText(item.detailVersion);
        holder.detailBlockHeight.setText(item.detailBlockHeight);

        boolean expanded = expandedTxIds.contains(item.txid);
        holder.details.setVisibility(expanded ? View.VISIBLE : View.GONE);

        holder.itemView.setOnClickListener(v -> toggleExpanded(holder.getAdapterPosition()));
    }

    private void toggleExpanded(int position) {
        if (position == RecyclerView.NO_POSITION || position >= items.size()) {
            return;
        }

        String txid = items.get(position).txid;
        if (!expandedTxIds.add(txid)) {
            expandedTxIds.remove(txid);
        }
        notifyItemChanged(position);
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
        final View details;
        final TextView detailTxid;
        final TextView detailType;
        final TextView detailAmount;
        final TextView detailTime;
        final TextView detailStatus;
        final TextView detailFee;
        final TextView detailPeers;
        final TextView detailInputs;
        final TextView detailOutputs;
        final TextView detailSize;
        final TextView detailVersion;
        final TextView detailBlockHeight;

        Holder(View view) {
            super(view);
            type = view.findViewById(R.id.tvTxType);
            amount = view.findViewById(R.id.tvTxAmount);
            time = view.findViewById(R.id.tvTxTime);
            confirmations = view.findViewById(R.id.tvTxConf);
            txid = view.findViewById(R.id.tvTxId);
            details = view.findViewById(R.id.txDetails);
            detailTxid = view.findViewById(R.id.tvTxDetailId);
            detailType = view.findViewById(R.id.tvTxDetailType);
            detailAmount = view.findViewById(R.id.tvTxDetailAmount);
            detailTime = view.findViewById(R.id.tvTxDetailTime);
            detailStatus = view.findViewById(R.id.tvTxDetailStatus);
            detailFee = view.findViewById(R.id.tvTxDetailFee);
            detailPeers = view.findViewById(R.id.tvTxDetailPeers);
            detailInputs = view.findViewById(R.id.tvTxDetailInputs);
            detailOutputs = view.findViewById(R.id.tvTxDetailOutputs);
            detailSize = view.findViewById(R.id.tvTxDetailSize);
            detailVersion = view.findViewById(R.id.tvTxDetailVersion);
            detailBlockHeight = view.findViewById(R.id.tvTxDetailBlockHeight);
        }
    }
}
