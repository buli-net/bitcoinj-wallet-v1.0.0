package wallet.about;

import wallet.main.R;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;

public class AboutActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_about);Toolbar t=findViewById(R.id.toolbar_about);setSupportActionBar(t);if(getSupportActionBar()!=null){getSupportActionBar().setTitle("About");getSupportActionBar().setDisplayHomeAsUpEnabled(true);}t.setNavigationOnClickListener(v->finish());}
}
