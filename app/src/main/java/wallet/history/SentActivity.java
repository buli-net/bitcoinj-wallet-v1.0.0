package wallet.history;

import wallet.main.R;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import wallet.main.MainActivityPresenter;

public class SentActivity extends AppCompatActivity {
    private final List<TransactionItem> all = new ArrayList<>();
    private final List<TransactionItem> filtered = new ArrayList<>();
    private TransactionAdapter adapter;
    private int filter = 0;
    private Button tabAll, tabSent, tabReceived;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_sent);
        Toolbar toolbar = findViewById(R.id.toolbar_sent);
        setSupportActionBar(toolbar);
        if (getSupportActionBar()!=null) { getSupportActionBar().setTitle("Transactions"); getSupportActionBar().setDisplayHomeAsUpEnabled(true); }
        toolbar.setNavigationOnClickListener(v -> finish());
        RecyclerView list = findViewById(R.id.recyclerTransactions);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TransactionAdapter(filtered);
        list.setAdapter(adapter);
        tabAll=findViewById(R.id.tabAll); tabSent=findViewById(R.id.tabSent); tabReceived=findViewById(R.id.tabReceived);
        tabAll.setOnClickListener(v -> {filter=0; applyFilter();});
        tabSent.setOnClickListener(v -> {filter=1; applyFilter();});
        tabReceived.setOnClickListener(v -> {filter=2; applyFilter();});
        load();
    }
    @Override protected void onResume(){ super.onResume(); if(adapter!=null) load(); }
    private void load() {
        WalletAppKit kit=MainActivityPresenter.getActiveWalletAppKit();
        NetworkParameters params=MainActivityPresenter.getActiveParameters();
        if(kit==null||params==null)return;
        new Thread(() -> {
            Context.propagate(Context.getOrCreate(params));
            try {
                List<Transaction> txs=kit.wallet().getTransactionsByTime();
                all.clear();
                SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                for(Transaction tx:txs){
                    Wallet w=kit.wallet();
                    Coin received=tx.getValueSentToMe(w), sent=tx.getValueSentFromMe(w), net=received.subtract(sent);
                    if(net.isZero())continue;
                    String type=net.isNegative()?"SENT":"RECEIVED";
                    String amount=(net.isNegative()?"-":"+")+(net.isNegative()?net.negate():net).toFriendlyString();
                    String time=tx.updateTime().isPresent()?fmt.format(Date.from(tx.updateTime().get())):"Unknown time";
                    int depth=tx.getConfidence()==null?0:tx.getConfidence().getDepthInBlocks();
                    String conf=depth+" conf";
                    String id=tx.getTxId().toString();
                    String shortId=id.length()>18?id.substring(0,8)+"..."+id.substring(id.length()-8):id;
                    all.add(new TransactionItem(type,amount,time,conf,shortId));
                }
                runOnUiThread(this::applyFilter);
            }catch(Exception e){runOnUiThread(()->((TextView)findViewById(R.id.tvEmptyTransactions)).setText("Unable to load transactions: "+e.getMessage()));}
        },"bitcoinj-history").start();
    }
    private void applyFilter(){
        tabAll.setBackgroundResource(filter==0?R.drawable.bg_tab_selected:R.drawable.bg_tab_normal);
        tabSent.setBackgroundResource(filter==1?R.drawable.bg_tab_selected:R.drawable.bg_tab_normal);
        tabReceived.setBackgroundResource(filter==2?R.drawable.bg_tab_selected:R.drawable.bg_tab_normal);
        tabAll.setTextColor(filter==0?0xffffffff:0xff31445f);
        tabSent.setTextColor(filter==1?0xffffffff:0xff31445f);
        tabReceived.setTextColor(filter==2?0xffffffff:0xff31445f);
        filtered.clear();
        for(TransactionItem i:all) if(filter==0 || (filter==1&&i.type.equals("SENT")) || (filter==2&&i.type.equals("RECEIVED"))) filtered.add(i);
        if(adapter!=null) adapter.notifyDataSetChanged();
        TextView empty=findViewById(R.id.tvEmptyTransactions);
        if(empty!=null) empty.setVisibility(filtered.isEmpty()?View.VISIBLE:View.GONE);
    }
}
