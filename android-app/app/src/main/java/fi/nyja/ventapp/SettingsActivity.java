package fi.nyja.ventapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import fi.nyja.ventapp.engine.LearnedRules;

/** Language, factory reset, and the list of learned corrections. */
public class SettingsActivity extends Activity {
    private LinearLayout rulesList;
    private TextView rulesEmpty;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SystemBarInsets.apply(this, R.id.settingsRoot);
        LearnedRuleStore.load(this);
        rulesList = findViewById(R.id.rulesList);
        rulesEmpty = findViewById(R.id.rulesEmpty);
        RadioGroup group = findViewById(R.id.languageGroup);
        if (AppLocale.FI.equals(AppLocale.get(this))) {
            group.check(R.id.languageFi);
        } else {
            group.check(R.id.languageSv);
        }
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                String lang = checkedId == R.id.languageFi ? AppLocale.FI : AppLocale.SV;
                if (lang.equals(AppLocale.get(SettingsActivity.this))) {
                    return;
                }
                AppLocale.set(SettingsActivity.this, lang);
                recreate();
            }
        });
        findViewById(R.id.resetAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        renderRules();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_body)
                .setPositiveButton(R.string.reset_all, (dialog, which) -> {
                    LearnedRuleStore.reset(SettingsActivity.this);
                    AppLocale.set(SettingsActivity.this, AppLocale.SV);
                    Toast.makeText(SettingsActivity.this, R.string.reset_done, Toast.LENGTH_LONG).show();
                    recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renderRules() {
        rulesList.removeAllViews();
        java.util.List<LearnedRules.Rule> rules = LearnedRules.current().all();
        rulesEmpty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < rules.size(); i++) {
            final LearnedRules.Rule rule = rules.get(i);
            TextView label = new TextView(this);
            label.setText(rule.field + ": " + rule.pattern + " → " + rule.value);
            label.setTextColor(getResources().getColor(R.color.text_primary));
            label.setTextSize(15);
            Button remove = new Button(this);
            remove.setText(R.string.delete_rule);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    LearnedRules.current().remove(rule.id);
                    try {
                        LearnedRuleStore.save(SettingsActivity.this, LearnedRules.current());
                    } catch (Exception ignored) {
                        // The rule is already gone from memory.
                    }
                    renderRules();
                }
            });
            rulesList.addView(label);
            rulesList.addView(remove);
        }
    }
}
