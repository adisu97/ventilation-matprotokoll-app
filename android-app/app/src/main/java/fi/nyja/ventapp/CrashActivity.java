package fi.nyja.ventapp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/** Shown in its own process so the message survives the crashed process. */
public class CrashActivity extends Activity {
    public static final String EXTRA_TEXT = "text";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash);
        String text = getIntent().getStringExtra(EXTRA_TEXT);
        if (text == null || text.length() == 0) {
            text = CrashLog.read(this);
        }
        if (text == null || text.length() == 0) {
            text = "Ingen detalj kunde läsas.";
        }
        final TextView body = findViewById(R.id.crashText);
        body.setText(text);
        findViewById(R.id.copyCrash).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("ventapp-krasch", body.getText()));
                Toast.makeText(CrashActivity.this, R.string.crash_copied, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
