package fi.nyja.ventapp;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import fi.nyja.ventapp.engine.LearnedRules;
import fi.nyja.ventapp.engine.ProtocolRow;
import fi.nyja.ventapp.engine.ProtocolXlsx;

/**
 * After a run, the user can correct a row or mark it as unwanted.
 * Each correction is stored against the raw text so the same text is not asked again.
 */
public class ReportActivity extends Activity {
    private LinearLayout list;
    private final List<EditText> rawFields = new ArrayList<EditText>();
    private final List<EditText> roomFields = new ArrayList<EditText>();
    private final List<EditText> ventFields = new ArrayList<EditText>();
    private final List<EditText> sizeFields = new ArrayList<EditText>();
    private final List<EditText> behovFields = new ArrayList<EditText>();
    private final List<EditText> sideFields = new ArrayList<EditText>();
    private final List<int[]> addresses = new ArrayList<int[]>();
    private final List<String> original = new ArrayList<String>();

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);
        SystemBarInsets.apply(this, R.id.reportRoot);
        list = findViewById(R.id.reportList);
        LearnedRuleStore.load(this);
        render();
        findViewById(R.id.reportAdd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addBlank();
            }
        });
        findViewById(R.id.reportSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndRebuild();
            }
        });
    }

    private void render() {
        list.removeAllViews();
        rawFields.clear();
        original.clear();
        roomFields.clear();
        ventFields.clear();
        sizeFields.clear();
        behovFields.clear();
        sideFields.clear();
        addresses.clear();
        List<ProtocolXlsx.DrawingSheet> sheets = ExtractionService.state().sheets;
        if (sheets == null) {
            return;
        }
        for (int s = 0; s < sheets.size(); s++) {
            List<ProtocolRow> rows = sheets.get(s).rows;
            for (int r = 0; r < rows.size(); r++) {
                list.addView(editor(s, r, rows.get(r)));
            }
        }
    }

    private View editor(int sheet, int rowIndex, ProtocolRow row) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(4));
        String vent = !row.exhaustName.isEmpty() ? row.exhaustName : row.supplyName;
        String size = row.exhaustSize != null && !row.exhaustSize.isEmpty() ? row.exhaustSize
                : (row.supplySize == null ? "" : row.supplySize);
        Double behov = row.exhaustBehov != null ? row.exhaustBehov : row.supplyBehov;
        String side = !row.exhaustName.isEmpty() ? "franluft" : (!row.supplyName.isEmpty() ? "tilluft" : "");
        rawFields.add(field(box, R.string.report_raw, row.raw, InputType.TYPE_CLASS_TEXT));
        roomFields.add(field(box, R.string.report_room, row.room, InputType.TYPE_CLASS_TEXT));
        ventFields.add(field(box, R.string.report_vent, vent, InputType.TYPE_CLASS_TEXT));
        sizeFields.add(field(box, R.string.report_size, size, InputType.TYPE_CLASS_TEXT));
        behovFields.add(field(box, R.string.report_behov, behov == null ? "" : trim(behov),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        sideFields.add(field(box, R.string.report_side, side, InputType.TYPE_CLASS_TEXT));
        addresses.add(new int[] {sheet, rowIndex});
        original.add(row.raw + "\n" + row.room + "\n" + vent + "\n" + size + "\n" + (behov == null ? "" : trim(behov)) + "\n" + side);
        Button drop = new Button(this);
        drop.setText(R.string.report_drop);
        final int index = addresses.size() - 1;
        drop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sideFields.get(index).setText("drop");
            }
        });
        box.addView(drop);
        return box;
    }

    private void addBlank() {
        list.addView(editor(-1, -1, new ProtocolRow("", "", null, null, "", null, null, false, "", 1)));
    }

    private void saveAndRebuild() {
        LearnedRules rules = LearnedRules.current();
        List<ProtocolXlsx.DrawingSheet> sheets = ExtractionService.state().sheets;
        if (sheets == null || sheets.isEmpty()) {
            finish();
            return;
        }
        for (int i = addresses.size() - 1; i >= 0; i--) {
            String raw = text(rawFields.get(i));
            String room = text(roomFields.get(i));
            String vent = text(ventFields.get(i));
            String size = text(sizeFields.get(i));
            String behov = text(behovFields.get(i));
            String side = text(sideFields.get(i)).toLowerCase();
            boolean drop = "drop".equals(side) || "ska inte finnas".equals(side);
            String now = raw + "\n" + room + "\n" + vent + "\n" + size + "\n" + behov + "\n" + side;
            boolean changed = addressChanged(i) || !now.equals(original.get(i));
            if (changed) {
                String[] was = original.get(i).split("\n", -1);
                if (drop && !raw.isEmpty()) {
                    rules.put(LearnedRules.DROP, raw, "1");
                } else if (!drop) {
                    if (!room.isEmpty() && was.length > 1 && !room.equals(was[1]) && !was[1].isEmpty()) {
                        rules.put(LearnedRules.ROOM, was[1], room);
                    }
                    if (!raw.isEmpty() && was.length >= 6) {
                        if (!vent.isEmpty() && !vent.equals(was[2])) {
                            rules.put(LearnedRules.NAME, raw, vent);
                        }
                        if (!size.isEmpty() && !size.equals(was[3])) {
                            rules.put(LearnedRules.SIZE, raw, size);
                        }
                        if (!behov.isEmpty() && !behov.equals(was[4])) {
                            rules.put(LearnedRules.BEHOV, raw, behov);
                        }
                        if (!side.equals(was[5])) {
                            if ("tilluft".equals(side) || "supply".equals(side)) {
                                rules.put(LearnedRules.SIDE, raw, "supply");
                            } else if ("franluft".equals(side) || "frånluft".equals(side) || "exhaust".equals(side)) {
                                rules.put(LearnedRules.SIDE, raw, "exhaust");
                            }
                        }
                    }
                }
            }
            int[] address = addresses.get(i);
            if (address[0] < 0 || address[0] >= sheets.size()) {
                if (!drop && !vent.isEmpty()) {
                    sheets.get(0).rows.add(rowFrom(room, vent, size, behov, side, raw));
                }
                continue;
            }
            List<ProtocolRow> rows = sheets.get(address[0]).rows;
            if (address[1] < 0 || address[1] >= rows.size()) {
                continue;
            }
            if (drop) {
                rows.remove(address[1]);
            } else {
                rows.set(address[1], rowFrom(room, vent, size, behov, side, raw));
            }
        }
        try {
            LearnedRuleStore.save(this, rules);
            ExtractionService.State state = ExtractionService.state();
            if (state.template != null) {
                state.xlsx = ProtocolXlsx.build(state.template, sheets, state.fromTemplate);
            }
        } catch (Exception ex) {
            ExtractionService.state().error = ex.getMessage();
        }
        setResult(RESULT_OK);
        finish();
    }

    private boolean addressChanged(int index) {
        return addresses.get(index)[0] < 0;
    }

    private static ProtocolRow rowFrom(String room, String vent, String size, String behov, String side, String raw) {
        Double flow = null;
        if (!behov.isEmpty()) {
            try {
                flow = Double.valueOf(behov.replace(',', '.'));
            } catch (NumberFormatException ignored) {
                flow = null;
            }
        }
        boolean exhaust = "franluft".equals(side) || "frånluft".equals(side) || "exhaust".equals(side);
        if (exhaust) {
            return new ProtocolRow(room, vent, size, flow, "", null, null, false, "", 1, raw);
        }
        return new ProtocolRow(room, "", null, null, vent, size, flow, false, "", 1, raw);
    }

    private EditText field(LinearLayout box, int labelId, String value, int input) {
        TextView label = new TextView(this);
        label.setText(labelId);
        label.setTextColor(getResources().getColor(R.color.text_secondary));
        label.setTextSize(14);
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setInputType(input);
        edit.setSingleLine(true);
        edit.setMinHeight(dp(36));
        edit.setTextColor(getResources().getColor(R.color.text_primary));
        box.addView(label);
        box.addView(edit);
        return edit;
    }

    private static String text(EditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }

    private static String trim(Double value) {
        if (value == Math.rint(value)) {
            return Integer.toString(value.intValue());
        }
        return Double.toString(value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
