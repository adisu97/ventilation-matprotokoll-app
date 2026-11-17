package fi.nyja.ventapp;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import fi.nyja.ventapp.engine.VentFormats;

/** Asks for the saved vent example before the read starts. */
public class ReadSetupActivity extends Activity {
    private EditText ventField;
    private TextView error;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_read_setup);
        SystemBarInsets.apply(this, R.id.readSetupRoot);
        error = findViewById(R.id.readSetupError);
        ventField = findViewById(R.id.ventExample);
        ventField.setText(VentFormatStore.load(this));
        findViewById(R.id.readSetupNext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = ventField.getText().toString();
                if (text.trim().length() > 0 && VentFormats.derive(text) == null) {
                    error.setText(R.string.vent_example_invalid);
                    return;
                }
                VentFormatStore.save(ReadSetupActivity.this, text);
                setResult(RESULT_OK);
                finish();
            }
        });
    }
}
