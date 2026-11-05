package fi.nyja.ventapp;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import fi.nyja.ventapp.engine.RoomFormats;

/** Examples of how room numbers are written on this job's drawings. */
public class RoomFormatsActivity extends Activity {
    private final List<String> examples = new ArrayList<String>();
    private LinearLayout list;
    private TextView errorView;
    private TextView trialResult;
    private EditText trial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room_formats);
        SystemBarInsets.apply(this, R.id.roomFormatsRoot);
        examples.addAll(RoomFormatStore.load(this));
        list = findViewById(R.id.roomExampleList);
        errorView = findViewById(R.id.roomFormatError);
        trialResult = findViewById(R.id.roomTrialResult);
        trial = findViewById(R.id.roomTrial);
        final EditText field = findViewById(R.id.roomExample);
        findViewById(R.id.addRoomExample).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                add(field.getText().toString());
                field.setText("");
            }
        });
        trial.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                showTrial();
            }
        });
        render();
    }

    private void add(String text) {
        RoomFormats.Derived derived = RoomFormats.derive(text);
        if (derived == null) {
            errorView.setText(R.string.room_example_invalid);
            return;
        }
        for (String existing : examples) {
            if (existing.equalsIgnoreCase(derived.example)) {
                errorView.setText(R.string.room_example_exists);
                return;
            }
        }
        examples.add(derived.example);
        errorView.setText("");
        persist();
    }

    private void persist() {
        RoomFormatStore.save(this, examples);
        render();
        showTrial();
    }

    private void render() {
        list.removeAllViews();
        for (int i = 0; i < examples.size(); i++) {
            final int index = i;
            RoomFormats.Derived derived = RoomFormats.derive(examples.get(i));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, 16, 0, 8);
            TextView title = new TextView(this);
            title.setText(examples.get(i));
            title.setTextColor(getResources().getColor(R.color.text_primary));
            title.setTextSize(18);
            TextView summary = new TextView(this);
            summary.setText(derived == null ? "" : derived.summary);
            summary.setTextColor(getResources().getColor(R.color.text_secondary));
            summary.setTextSize(16);
            android.widget.Button remove = new android.widget.Button(this);
            remove.setText(R.string.remove);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    examples.remove(index);
                    persist();
                }
            });
            row.addView(title);
            row.addView(summary);
            row.addView(remove);
            list.addView(row);
        }
    }

    private void showTrial() {
        if (trial.getText().toString().trim().length() == 0) {
            trialResult.setText("");
            return;
        }
        trialResult.setText(RoomFormats.trial(trial.getText().toString()));
    }
}
