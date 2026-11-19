package com.meowsic.app;

import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Bottom sheet with everything about how the audio sounds: speed (0.25-3x, optional 5x),
 * presets, pitch, skip silence and volume boost.
 */
public final class SpeedSheet {
    private static final float STEP = 0.05f;

    private final AppCompatActivity activity;
    private final PlaybackService service;
    private final BottomSheetDialog dialog;
    private final View root;

    private final TextView speedValue;
    private final TextView percentValue;
    private final TextView pitchValue;
    private final TextView boostValue;
    private final SeekBar speedBar;
    private final SeekBar pitchBar;
    private final SeekBar boostBar;
    private final CheckBox skipSilence;
    private final LinearLayout presetsBox;
    private final MaterialButton rangeButton;

    private final List<MaterialButton> presetButtons = new ArrayList<>();
    private final List<Float> presetValues = new ArrayList<>();
    private final List<ColorStateList> presetStroke = new ArrayList<>();
    private final List<ColorStateList> presetText = new ArrayList<>();
    private boolean extended;
    private boolean updating;

    private final PlaybackService.Listener listener = new PlaybackService.Listener() {
        @Override
        public void onSettingsChanged() {
            refresh();
        }
    };

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    public static SpeedSheet show(AppCompatActivity activity, PlaybackService service) {
        SpeedSheet sheet = new SpeedSheet(activity, service);
        sheet.open();
        return sheet;
    }

    private SpeedSheet(AppCompatActivity activity, PlaybackService service) {
        this.activity = activity;
        this.service = service;
        this.dialog = new BottomSheetDialog(activity);
        this.root = LayoutInflater.from(activity).inflate(R.layout.sheet_speed, null);
        dialog.setContentView(root);

        speedValue = root.findViewById(R.id.speed_value);
        percentValue = root.findViewById(R.id.speed_percent);
        pitchValue = root.findViewById(R.id.pitch_value);
        boostValue = root.findViewById(R.id.boost_value);
        speedBar = root.findViewById(R.id.speed_bar);
        pitchBar = root.findViewById(R.id.pitch_bar);
        boostBar = root.findViewById(R.id.boost_bar);
        skipSilence = root.findViewById(R.id.skip_silence);
        presetsBox = root.findViewById(R.id.presets_box);
        rangeButton = root.findViewById(R.id.range_button);

        speedValue.setOnClickListener(v -> askSpeed());
        root.findViewById(R.id.speed_minus).setOnClickListener(v -> nudge(-STEP));
        root.findViewById(R.id.speed_plus).setOnClickListener(v -> nudge(STEP));
        root.findViewById(R.id.add_preset).setOnClickListener(v -> addPreset());
        rangeButton.setOnClickListener(v -> toggleRange());

        speedBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && !updating) {
                    SpeedSheet.this.service.setSpeed(PlaybackService.MIN_SPEED + STEP * progress);
                }
            }
        });

        pitchBar.setMax(48);
        pitchBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && !updating) {
                    SpeedSheet.this.service.setPitchSemitones((progress - 24) * 0.5f);
                }
            }
        });
        root.findViewById(R.id.pitch_reset).setOnClickListener(v -> service.setPitchSemitones(0f));

        boostBar.setMax((int) (PlaybackService.MAX_BOOST_DB * 2));
        boostBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && !updating) {
                    SpeedSheet.this.service.setBoostDb(progress * 0.5f);
                }
            }
        });
        root.findViewById(R.id.boost_reset).setOnClickListener(v -> service.setBoostDb(0f));
        root.findViewById(R.id.boost_auto).setOnClickListener(v -> autoBoost());

        skipSilence.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) {
                service.setSkipSilence(checked);
            }
        });
    }

    private void open() {
        extended = Prefs.isExtendedRange(activity)
                || service.getSpeed() > PlaybackService.MAX_SPEED_DEFAULT + 0.001f;
        service.addListener(listener);
        dialog.setOnDismissListener(d -> service.removeListener(listener));
        buildPresets();
        refresh();
        dialog.show();
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    public void dismiss() {
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        updating = true;
        float sp = service.getSpeed();
        if (sp > PlaybackService.MAX_SPEED_DEFAULT + 0.001f) {
            extended = true;
        }
        speedValue.setText(Fmt.speed(sp));
        percentValue.setText(Fmt.percent(sp));
        int max = extended ? 95 : 55;
        speedBar.setMax(max);
        speedBar.setProgress(Math.min(max, Math.max(0, Math.round((sp - PlaybackService.MIN_SPEED) / STEP))));
        rangeButton.setText(extended ? R.string.range_is_extended : R.string.range_extend);

        float st = service.getPitchSemitones();
        pitchValue.setText(activity.getString(R.string.pitch_value, Fmt.semitones(st)));
        pitchBar.setProgress(Math.round(st * 2f) + 24);

        float db = service.getBoostDb();
        boostValue.setText(activity.getString(R.string.boost_value, Fmt.db(db), Fmt.boostPercent(db)));
        boostBar.setProgress(Math.round(db * 2f));

        skipSilence.setChecked(service.isSkipSilence());

        int orange = ContextCompat.getColor(activity, R.color.meow_orange);
        for (int i = 0; i < presetButtons.size(); i++) {
            boolean on = Math.abs(presetValues.get(i) - sp) < 0.004f;
            MaterialButton b = presetButtons.get(i);
            b.setStrokeColor(on ? ColorStateList.valueOf(orange) : presetStroke.get(i));
            b.setStrokeWidth(dp(on ? 2 : 1));
            b.setTextColor(on ? ColorStateList.valueOf(orange) : presetText.get(i));
        }
        updating = false;
    }

    // ------------------------------------------------------------------ speed

    private void applySpeed(float v) {
        v = Fmt.clamp(v, PlaybackService.MIN_SPEED, PlaybackService.MAX_SPEED_EXTENDED);
        if (v > PlaybackService.MAX_SPEED_DEFAULT + 0.001f && !extended) {
            extended = true;
            Prefs.setExtendedRange(activity, true);
        }
        service.setSpeed(v);
    }

    private void nudge(float delta) {
        float max = extended ? PlaybackService.MAX_SPEED_EXTENDED : PlaybackService.MAX_SPEED_DEFAULT;
        service.setSpeed(Math.min(max, service.getSpeed() + delta));
    }

    private void toggleRange() {
        extended = !extended;
        Prefs.setExtendedRange(activity, extended);
        if (!extended && service.getSpeed() > PlaybackService.MAX_SPEED_DEFAULT) {
            service.setSpeed(PlaybackService.MAX_SPEED_DEFAULT);
        } else {
            refresh();
        }
    }

    private void askSpeed() {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.2f", service.getSpeed()));
        input.setSelectAllOnFocus(true);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.set_speed)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Float v = parseNumber(input.getText().toString());
                    if (v != null) {
                        applySpeed(v);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Accepts Latin, Persian and Arabic digits and both decimal separators. */
    private static Float parseNumber(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isDigit(ch)) {
                sb.append(Character.digit(ch, 10));
            } else if (ch == '.' || ch == ',' || ch == '\u066B' || ch == '\u060C') {
                sb.append('.');
            }
        }
        try {
            return Float.parseFloat(sb.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ presets

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void buildPresets() {
        presetsBox.removeAllViews();
        presetButtons.clear();
        presetValues.clear();
        presetStroke.clear();
        presetText.clear();
        List<Float> presets = Prefs.getPresets(activity);
        LinearLayout row = null;
        for (int i = 0; i < presets.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                presetsBox.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final float value = presets.get(i);
            MaterialButton b = (MaterialButton) LayoutInflater.from(activity)
                    .inflate(R.layout.item_preset, row, false);
            b.setText(String.format(Locale.US, "%.2f", value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> applySpeed(value));
            b.setOnLongClickListener(v -> {
                confirmRemove(value);
                return true;
            });
            row.addView(b);
            presetButtons.add(b);
            presetValues.add(value);
            presetStroke.add(b.getStrokeColor());
            presetText.add(b.getTextColors());
        }
        if (row != null) {
            int missing = (4 - presets.size() % 4) % 4;
            for (int i = 0; i < missing; i++) {
                View filler = new View(activity);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(filler, lp);
            }
        }
    }

    private void addPreset() {
        float cur = service.getSpeed();
        List<Float> list = Prefs.getPresets(activity);
        for (float f : list) {
            if (Math.abs(f - cur) < 0.004f) {
                Toast.makeText(activity, R.string.preset_exists, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        list.add(cur);
        Collections.sort(list);
        Prefs.setPresets(activity, list);
        buildPresets();
        refresh();
        Toast.makeText(activity, activity.getString(R.string.preset_added, Fmt.speed(cur)),
                Toast.LENGTH_SHORT).show();
    }

    private void confirmRemove(final float value) {
        new MaterialAlertDialogBuilder(activity)
                .setMessage(activity.getString(R.string.preset_remove_q, Fmt.speed(value)))
                .setPositiveButton(R.string.remove, (d, w) -> {
                    List<Float> list = Prefs.getPresets(activity);
                    List<Float> keep = new ArrayList<>();
                    for (float f : list) {
                        if (Math.abs(f - value) >= 0.004f) {
                            keep.add(f);
                        }
                    }
                    Prefs.setPresets(activity, keep);
                    buildPresets();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ boost

    private void autoBoost() {
        Track t = service.getCurrentTrack();
        if (t == null) {
            return;
        }
        WaveformData d = WaveformStore.peek(t);
        float db = d == null ? Float.NaN : d.suggestBoostDb(PlaybackService.MAX_BOOST_DB);
        if (Float.isNaN(db)) {
            Toast.makeText(activity, R.string.auto_boost_wait, Toast.LENGTH_SHORT).show();
            return;
        }
        service.setBoostDb(db);
        Toast.makeText(activity, activity.getString(R.string.auto_boost_set, Fmt.db(service.getBoostDb())),
                Toast.LENGTH_SHORT).show();
    }
}
