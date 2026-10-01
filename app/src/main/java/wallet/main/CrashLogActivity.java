package wallet.main;

import android.support.v7.app.AppCompatActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Temporary screen used to export a captured crash report without adb/root. */
public class CrashLogActivity extends AppCompatActivity {
    private static final int EXPORT_REQUEST = 9401;
    private TextView reportText;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_crash_log);
        setTitle(R.string.crash_log_title);

        reportText = findViewById(R.id.crashReportText);
        Button export = findViewById(R.id.crashLogExport);
        Button clear = findViewById(R.id.crashLogClear);

        export.setOnClickListener(v -> exportReport());
        clear.setOnClickListener(v -> {
            CrashReporter.clearCrashReport(this);
            updateReport();
        });
        updateReport();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (reportText != null) {
            updateReport();
        }
    }

    private void updateReport() {
        String report = CrashReporter.readCrashReport(this);
        reportText.setText(report.isEmpty()
                ? getString(R.string.crash_log_empty)
                : report);
    }

    private void exportReport() {
        String report = CrashReporter.readCrashReport(this);
        if (report.isEmpty()) {
            reportText.setText(R.string.crash_log_empty);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, getString(R.string.crash_log_file_name));
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri destination = data.getData();
        if (destination == null) {
            return;
        }
        try (OutputStream output = getContentResolver().openOutputStream(destination)) {
            if (output == null) {
                throw new java.io.IOException("Unable to open destination");
            }
            output.write(CrashReporter.readCrashReport(this).getBytes(StandardCharsets.UTF_8));
            output.flush();
            android.widget.Toast.makeText(this, R.string.crash_log_exported,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            android.widget.Toast.makeText(this, R.string.crash_log_export_failed,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
