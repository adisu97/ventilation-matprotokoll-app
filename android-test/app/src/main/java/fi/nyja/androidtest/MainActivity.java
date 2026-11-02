package fi.nyja.androidtest;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button button = findViewById(R.id.button);
        TextView label = findViewById(R.id.label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                label.setText(R.string.nyja);
            }
        });
    }
}
