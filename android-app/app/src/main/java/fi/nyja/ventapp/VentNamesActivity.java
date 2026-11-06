package fi.nyja.ventapp;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import fi.nyja.ventapp.engine.VentCatalog;

/** Edit the vent names the next extraction will search for. */
public class VentNamesActivity extends Activity {
    private VentCatalog catalog;
    private LinearLayout supplyList;
    private LinearLayout exhaustList;
    private TextView countView;
    private TextView errorView;
    private EditText nameField;
    private RadioButton typeSupply;
    private boolean ignoreSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vent_names);
        SystemBarInsets.apply(this, R.id.ventNamesRoot);
        catalog = VentNameStore.load(this);
        supplyList = findViewById(R.id.supplyList);
        exhaustList = findViewById(R.id.exhaustList);
        countView = findViewById(R.id.ventCount);
        errorView = findViewById(R.id.ventError);
        nameField = findViewById(R.id.newVentName);
        typeSupply = findViewById(R.id.typeSupply);
        findViewById(R.id.addVent).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addName();
            }
        });
        findViewById(R.id.resetNames).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                catalog = VentCatalog.defaults();
                errorView.setText("");
                persist();
            }
        });
        render();
    }

    private void addName() {
        boolean tilluft = typeSupply.isChecked();
        String reason = catalog.rejection(nameField.getText().toString(), tilluft);
        if (reason != null) {
            errorView.setText(reason);
            return;
        }
        catalog = catalog.add(nameField.getText().toString(), tilluft);
        nameField.setText("");
        errorView.setText("");
        persist();
    }

    private void persist() {
        VentNameStore.save(this, catalog);
        render();
    }

    private void render() {
        countView.setText(getString(R.string.vent_count, catalog.activeCount()));
        fill(supplyList, catalog.tilluftRows());
        fill(exhaustList, catalog.franluftRows());
    }

    private void fill(LinearLayout list, java.util.List<VentCatalog.Row> rows) {
        list.removeAllViews();
        for (int i = 0; i < rows.size(); i++) {
            list.addView(rowView(rows.get(i)));
        }
    }

    private View rowView(final VentCatalog.Row row) {
        View view = getLayoutInflater().inflate(R.layout.item_vent_name, null, false);
        TextView name = view.findViewById(R.id.ventName);
        TextView kind = view.findViewById(R.id.ventKind);
        Switch enabled = view.findViewById(R.id.ventEnabled);
        View delete = view.findViewById(R.id.ventDelete);
        name.setText(row.label);
        name.setTextColor(getResources().getColor(row.enabled ? R.color.text_primary : R.color.text_secondary));
        kind.setText(row.builtin ? R.string.builtin_name : R.string.custom_name);
        if (row.builtin) {
            enabled.setVisibility(View.VISIBLE);
            delete.setVisibility(View.GONE);
            ignoreSwitch = true;
            enabled.setChecked(row.enabled);
            ignoreSwitch = false;
            enabled.setContentDescription(row.label);
            enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    if (ignoreSwitch) {
                        return;
                    }
                    catalog = catalog.setEnabled(row.key, row.tilluft, checked);
                    persist();
                }
            });
        } else {
            enabled.setVisibility(View.GONE);
            delete.setVisibility(View.VISIBLE);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    catalog = catalog.removeCustom(row.key, row.tilluft);
                    persist();
                }
            });
        }
        return view;
    }
}
