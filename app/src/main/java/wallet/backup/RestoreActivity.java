/**
 * Release documentation:
 * Release documentation for the wallet restore screen.
 * Lets the user select a wallet backup, confirms the destructive replacement operation, and delegates restoration to the persistent main wallet presenter.
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
import android.widget.Toast;

import wallet.main.MainActivityPresenter;

public class RestoreActivity extends AppCompatActivity {
    private static final int OPEN=8201;
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_restore);Toolbar t=findViewById(R.id.toolbar_restore);setSupportActionBar(t);if(getSupportActionBar()!=null){getSupportActionBar().setTitle(R.string.restore_title);getSupportActionBar().setDisplayHomeAsUpEnabled(true);}t.setNavigationOnClickListener(v->finish());Button b=findViewById(R.id.btnChooseRestore);b.setOnClickListener(v->choose());}
    private void choose(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");startActivityForResult(i,OPEN);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==OPEN&&c==RESULT_OK&&d!=null&&d.getData()!=null){Uri uri=d.getData();new android.support.v7.app.AlertDialog.Builder(this).setTitle(R.string.restore_question).setMessage(R.string.restore_message).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.restore_action,(x,w)->{MainActivityPresenter p=MainActivityPresenter.getActivePresenter();if(p==null){Toast.makeText(this,getString(R.string.wallet_core_not_ready),Toast.LENGTH_LONG).show();}else{p.restoreWallet(uri);Toast.makeText(this,getString(R.string.restore_in_progress),Toast.LENGTH_LONG).show();finish();}}).show();}}
}
