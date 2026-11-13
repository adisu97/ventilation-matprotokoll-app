package fi.nyja.ventapp;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Shows every string the reader saw, and keeps a copy in Downloads. */
public class OcrLogActivity extends Activity {
    public static final String EXTRA_TEXT = "text";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ocr_log);
        SystemBarInsets.apply(this, R.id.ocrLogRoot);
        String text = getIntent().getStringExtra(EXTRA_TEXT);
        if (text == null) {
            text = "";
        }
        TextView body = findViewById(R.id.ocrText);
        body.setText(text);
        final String payload = text;
        findViewById(R.id.closeOcr).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        if (write(payload)) {
            Toast.makeText(this, R.string.ocr_saved, Toast.LENGTH_LONG).show();
        }
    }

    private boolean write(String text) {
        byte[] bytes;
        try {
            bytes = text.getBytes("UTF-8");
        } catch (Exception error) {
            bytes = text.getBytes();
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, "ventapp-ocr.txt");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return false;
                }
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) {
                    return false;
                }
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
                return true;
            }
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                return false;
            }
            FileOutputStream out = new FileOutputStream(new File(dir, "ventapp-ocr.txt"));
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }
}
