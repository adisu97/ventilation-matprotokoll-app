package fi.nyja.ventapp;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.List;

import fi.nyja.ventapp.engine.RoomFormats;
import fi.nyja.ventapp.engine.VentFormats;

/** Asks for the room example, then the vent example, before the read starts. */
public class ReadSetupActivity extends Activity {
    private int step;
    private EditText roomField;
    private EditText ventField;
    private TextView title;
    private TextView help;
    private TextView error;
    private Button next;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_read_setup);
        SystemBarInsets.apply(this, R.id.readSetupRoot);
        title = findViewById(R.id.readSetupTitle);
        help = findViewById(R.id.readSetupHelp);
        error = findViewById(R.id.readSetupError);
        roomField = findViewById(R.id.roomExample);
        ventField = findViewById(R.id.ventExample);
        next = findViewById(R.id.readSetupNext);
        List<String> savedRooms = RoomFormatStore.load(this);
        if (!savedRooms.isEmpty()) {
            roomField.setText(savedRooms.get(savedRooms.size() - 1));
        }
        ventField.setText(VentFormatStore.load(this));
        step = 0;
        showStep();
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                advance();
            }
        });
    }

    private void showStep() {
        boolean room = ReadSetup.ROOM.equals(ReadSetup.promptOrder().get(step));
        roomField.setVisibility(room ? View.VISIBLE : View.GONE);
        ventField.setVisibility(room ? View.GONE : View.VISIBLE);
        title.setText(room ? R.string.read_setup_room_title : R.string.read_setup_vent_title);
        help.setText(room ? R.string.read_setup_room_help : R.string.read_setup_vent_help);
        next.setText(room ? R.string.read_setup_next : R.string.read_setup_start);
        error.setText("");
    }

    private void advance() {
        if (ReadSetup.ROOM.equals(ReadSetup.promptOrder().get(step))) {
            String text = roomField.getText().toString();
            if (text.trim().length() > 0 && RoomFormats.derive(text) == null) {
                error.setText(R.string.room_example_invalid);
                return;
            }
            step = 1;
            showStep();
            return;
        }
        String text = ventField.getText().toString();
        if (text.trim().length() > 0 && VentFormats.derive(text) == null) {
            error.setText(R.string.vent_example_invalid);
            return;
        }
        if (roomField.getText().toString().trim().length() == 0) {
            RoomFormatStore.save(this, java.util.Collections.<String>emptyList());
        } else {
            RoomFormatStore.save(this, java.util.Collections.singletonList(roomField.getText().toString().trim()));
        }
        VentFormatStore.save(this, ventField.getText().toString());
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (step > 0) {
            step = 0;
            showStep();
            return;
        }
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }
}
