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
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_restore);Toolbar t=findViewById(R.id.toolbar_restore);setSupportActionBar(t);if(getSupportActionBar()!=null){getSupportActionBar().setTitle("Restore wallet");getSupportActionBar().setDisplayHomeAsUpEnabled(true);}t.setNavigationOnClickListener(v->finish());Button b=findViewById(R.id.btnChooseRestore);b.setOnClickListener(v->choose());}
    private void choose(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");startActivityForResult(i,OPEN);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==OPEN&&c==RESULT_OK&&d!=null&&d.getData()!=null){Uri uri=d.getData();new android.support.v7.app.AlertDialog.Builder(this).setTitle("Restore wallet?").setMessage("Wallet hiện tại sẽ được thay thế bằng wallet trong backup. Tiếp tục?").setNegativeButton("CANCEL",null).setPositiveButton("RESTORE",(x,w)->{MainActivityPresenter p=MainActivityPresenter.getActivePresenter();if(p==null){Toast.makeText(this,"Wallet core không sẵn sàng",Toast.LENGTH_LONG).show();}else{p.restoreWallet(uri);Toast.makeText(this,"Đang restore wallet...",Toast.LENGTH_LONG).show();finish();}}).show();}}
}
