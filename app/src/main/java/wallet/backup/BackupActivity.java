/**
 * Release documentation:
 * Release documentation for the wallet backup screen.
 * Creates a current wallet file from bitcoinj, opens the Android document provider, and copies the wallet to the user-selected destination.
 * All user-visible release text is resolved from Android string resources.
 */

package wallet.backup;

import wallet.main.R;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.Context;
import org.bitcoinj.kits.WalletAppKit;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import wallet.Constants;
import wallet.main.MainActivityPresenter;

public class BackupActivity extends AppCompatActivity {
    private static final int CREATE = 8101;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_backup);
        Toolbar t=findViewById(R.id.toolbar_backup); setSupportActionBar(t);
        if(getSupportActionBar()!=null){getSupportActionBar().setTitle(R.string.backup_title);getSupportActionBar().setDisplayHomeAsUpEnabled(true);} t.setNavigationOnClickListener(v->finish());
        Button b=findViewById(R.id.btnCreateBackup); b.setOnClickListener(v->prepare());
        TextView path=findViewById(R.id.tvBackupPath); path.setText(new File(getFilesDir(),Constants.WALLET_NAME+".wallet").getAbsolutePath());
    }
    private void prepare(){
        WalletAppKit kit=MainActivityPresenter.getActiveWalletAppKit();
        org.bitcoinj.core.NetworkParameters params=MainActivityPresenter.getActiveParameters();
        if(kit==null||params==null){toast(getString(R.string.wallet_not_ready));return;}
        new Thread(()->{
            Context.propagate(Context.getOrCreate(params));
            try{
                File file=new File(getFilesDir(),Constants.WALLET_NAME+".wallet");
                kit.wallet().saveToFile(file);
                if(!file.exists()||file.length()==0)throw new java.io.IOException(getString(R.string.wallet_file_empty));
                runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,getString(R.string.backup_title_file, Constants.WALLET_NAME));startActivityForResult(i,CREATE);});
            }catch(Exception e){toast(getString(R.string.backup_failed, e.getMessage()));}
        },"wallet-backup").start();
    }
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==CREATE&&c==RESULT_OK&&d!=null&&d.getData()!=null)copy(d.getData());}
    private void copy(Uri uri){new Thread(()->{try(InputStream in=new FileInputStream(new File(getFilesDir(),Constants.WALLET_NAME+".wallet"));OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new java.io.IOException(getString(R.string.backup_destination_open_failed));byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();toast(getString(R.string.backup_success));}catch(Exception e){toast(getString(R.string.backup_failed, e.getMessage()));}},"wallet-backup-copy").start();}
    private void toast(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_LONG).show());}
}
