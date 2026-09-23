/**
 * Release documentation:
 * Release documentation for the About screen.
 * Displays the application information supplied by Android string resources and provides standard toolbar navigation.
 * The Activity contains presentation logic only and does not own wallet state.
 */

package wallet.about;

import wallet.main.R;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;

public class AboutActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_about);Toolbar t=findViewById(R.id.toolbar_about);setSupportActionBar(t);if(getSupportActionBar()!=null){getSupportActionBar().setTitle(R.string.about_title);getSupportActionBar().setDisplayHomeAsUpEnabled(true);}t.setNavigationOnClickListener(v->finish());}
}
