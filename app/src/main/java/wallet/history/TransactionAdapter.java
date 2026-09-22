package wallet.history;

import wallet.main.R;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.List;

import wallet.utils.ThemeUtils;

public final class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.Holder> {
    private final List<TransactionItem> items;
    public TransactionAdapter(List<TransactionItem> items) { this.items = items; }
    @Override public Holder onCreateViewHolder(ViewGroup p, int t) {
        return new Holder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_transaction, p, false));
    }
    @Override public void onBindViewHolder(Holder h, int pos) {
        TransactionItem i = items.get(pos);
        h.type.setText(i.type);
        h.amount.setText(i.amount);
        h.amount.setTextColor(ThemeUtils.color(h.itemView.getContext(), android.R.attr.colorAccent));
        h.time.setText(i.time);
        h.conf.setText(i.confirmations);
        h.txid.setText(i.txid);
    }
    @Override public int getItemCount() { return items.size(); }
    static final class Holder extends RecyclerView.ViewHolder {
        TextView type, amount, time, conf, txid;
        Holder(View v) { super(v); type=v.findViewById(R.id.tvTxType); amount=v.findViewById(R.id.tvTxAmount); time=v.findViewById(R.id.tvTxTime); conf=v.findViewById(R.id.tvTxConf); txid=v.findViewById(R.id.tvTxId); }
    }
}
