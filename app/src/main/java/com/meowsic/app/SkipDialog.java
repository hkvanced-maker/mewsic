package com.meowsic.app;

import android.text.InputType;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Lets the user type any number of seconds for the rewind / forward buttons. */
final class SkipDialog {
    static final int MAX_SECONDS = 7200;

    private SkipDialog() {
    }

    static void show(final AppCompatActivity activity, final boolean back, final Runnable onChanged) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(back ? Prefs.getRewindSec(activity) : Prefs.getForwardSec(activity)));
        input.setSelectAllOnFocus(true);

        FrameLayout frame = new FrameLayout(activity);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(back ? R.string.rewind_interval : R.string.forward_interval)
                .setMessage(R.string.skip_range_hint)
                .setView(frame)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Float parsed = Fmt.parseNumber(input.getText().toString());
                    int v = parsed == null ? -1 : Math.round(parsed);
                    if (v < 1 || v > MAX_SECONDS) {
                        Toast.makeText(activity, R.string.skip_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (back) {
                        Prefs.setRewindSec(activity, v);
                    } else {
                        Prefs.setForwardSec(activity, v);
                    }
                    if (onChanged != null) {
                        onChanged.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }
}
