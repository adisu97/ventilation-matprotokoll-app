package fi.nyja.ventapp;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import fi.nyja.ventapp.engine.PendingSize;
import fi.nyja.ventapp.engine.ProtocolBuilder;
import fi.nyja.ventapp.engine.ProtocolRow;
import fi.nyja.ventapp.engine.ProtocolXlsx;

/** Asks for a size before a vent with an unclear label is written to Excel. */
public class SizeReviewActivity extends Activity {
    private final List<EditText> fields = new ArrayList<EditText>();
    private boolean applied;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_size_review);
        SystemBarInsets.apply(this, R.id.sizeReviewRoot);
        LinearLayout list = findViewById(R.id.sizeList);
        List<PendingSize> pending = ExtractionService.state().pending;
        if (pending == null || pending.isEmpty()) {
            finish();
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            list.addView(rowFor(pending.get(i)));
        }
        findViewById(R.id.sizeApply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWith(false);
            }
        });
        findViewById(R.id.sizeSkipAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWith(true);
            }
        });
    }

    private View rowFor(PendingSize item) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 28, 0, 8);
        TextView found = new TextView(this);
        String raw = item.vent == null || item.vent.raw == null || item.vent.raw.trim().isEmpty()
                ? (item.vent == null ? "" : item.vent.name) : item.vent.raw.trim();
        found.setText(getString(R.string.size_found, raw));
        found.setTextColor(getResources().getColor(R.color.text_primary));
        found.setTextSize(18);
        TextView meta = new TextView(this);
        String side = item.vent != null && "exhaust".equals(item.vent.kind)
                ? getString(R.string.franluft) : getString(R.string.tilluft);
        String name = item.vent == null ? "" : item.vent.canonicalName();
        String room = item.room == null || item.room.trim().isEmpty() ? "" : getString(R.string.size_room, item.room);
        meta.setText((room + "   " + side + " " + name).trim());
        meta.setTextColor(getResources().getColor(R.color.text_secondary));
        meta.setTextSize(16);
        EditText size = new EditText(this);
        size.setHint(R.string.size_hint);
        size.setSingleLine(true);
        size.setMinHeight(dp(48));
        size.setTextColor(getResources().getColor(R.color.text_primary));
        size.setHintTextColor(getResources().getColor(R.color.hint));
        size.setBackgroundResource(R.drawable.bg_field);
        size.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.topMargin = dp(8);
        size.setLayoutParams(gap);
        fields.add(size);
        box.addView(found);
        box.addView(meta);
        box.addView(size);
        return box;
    }

    private void finishWith(boolean skipAll) {
        if (applied) {
            return;
        }
        applied = true;
        ExtractionService.State state = ExtractionService.state();
        List<PendingSize> pending = state.pending;
        if (!skipAll && state.sheets != null && pending != null) {
            for (int i = 0; i < pending.size() && i < fields.size(); i++) {
                PendingSize item = pending.get(i);
                if (item.sheetIndex < 0 || item.sheetIndex >= state.sheets.size()) {
                    continue;
                }
                List<ProtocolRow> rows = state.sheets.get(item.sheetIndex).rows;
                String typed = fields.get(i).getText() == null ? "" : fields.get(i).getText().toString();
                rows.addAll(ProtocolBuilder.rowsForConfirmedSize(rows, item, typed));
            }
        }
        try {
            if (state.template != null && state.sheets != null) {
                state.xlsx = ProtocolXlsx.build(state.template, state.sheets, state.fromTemplate);
            }
        } catch (Exception ex) {
            state.error = ex.getMessage() == null ? "Kunde inte skriva Excel-filen." : ex.getMessage();
        }
        state.needsSize = false;
        state.pending = new ArrayList<PendingSize>();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWith(true);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
