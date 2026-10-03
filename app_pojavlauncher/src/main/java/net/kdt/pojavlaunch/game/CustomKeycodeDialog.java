package net.kdt.pojavlaunch.game;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.CallbackBridge;

import java.util.ArrayList;
import java.util.List;

import git.artdeell.mojo.R;

public final class CustomKeycodeDialog {
    private final Context context;
    private final List<Entry> entries = new ArrayList<>();
    private int selected = -1;
    private Button confirm;
    private AlertDialog dialog;
    private final int surface;
    private final int text;
    private final int key;
    private final int selectedKey;

    private CustomKeycodeDialog(Context context) {
        this.context = context;
        surface = color(android.R.attr.colorBackground, Color.rgb(32, 32, 32));
        text = color(android.R.attr.textColorPrimary, Color.WHITE);
        key = darken(surface, .68f);
        selectedKey = color(android.R.attr.colorAccent, Color.rgb(80, 80, 80));
    }

    public static void show(@NonNull Context context) {
        new CustomKeycodeDialog(context).show();
    }

    private void show() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(8));
        root.setBackgroundColor(surface);

        root.addView(keyboard(), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button close = action(R.string.custom_keycode_close);
        close.setOnClickListener(v -> dialog.dismiss());
        actions.addView(close, new LinearLayout.LayoutParams(0, dp(58), 1f));

        confirm = action(R.string.custom_keycode_confirm);
        confirm.setEnabled(false);
        confirm.setAlpha(.55f);
        confirm.setOnClickListener(v -> {
            if (selected != -1) {
                CallbackBridge.sendKeyPress(selected);
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        cp.setMarginStart(dp(10));
        actions.addView(confirm, cp);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        dialog = new AlertDialog.Builder(context).setView(root).create();
        dialog.setOnShowListener(v -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                int width = context.getResources().getDisplayMetrics().widthPixels;
                dialog.getWindow().setLayout((int)(width * .98f), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private LinearLayout keyboard() {
        LinearLayout board = new LinearLayout(context);
        board.setOrientation(LinearLayout.VERTICAL);
        board.setBackgroundColor(surface);

        row(board,
                k("Esc", 111, 1), k("F1", 131, 1), k("F2", 132, 1), k("F3", 133, 1), k("F4", 134, 1),
                k("F5", 135, 1), k("F6", 136, 1), k("F7", 137, 1), k("F8", 138, 1), k("F9", 139, 1),
                k("F10", 140, 1), k("F11", 141, 1), k("F12", 142, 1), k("Pause", 121, 1.25f));

        row(board,
                k("~\n`", 68, 1), k("!\n1", 8, 1), k("@\n2", 9, 1), k("#\n3", 10, 1), k("$\n4", 11, 1),
                k("%\n5", 12, 1), k("^\n6", 13, 1), k("&\n7", 14, 1), k("*\n8", 15, 1), k("(\n9", 16, 1),
                k(")\n0", 7, 1), k("_\n-", 69, 1), k("+\n=", 70, 1), k("backspace", 67, 1.75f), k("End", 123, 1));

        row(board,
                k("tab", 61, 1.45f), k("Q", 45, 1), k("W", 51, 1), k("E", 33, 1), k("R", 46, 1),
                k("T", 48, 1), k("Y", 53, 1), k("U", 49, 1), k("I", 37, 1), k("O", 43, 1), k("P", 44, 1),
                k("{\n[", 71, 1), k("}\n]", 72, 1), k("|\\", 73, 1.25f));

        row(board,
                k("Caps Lock", 115, 1.55f), k("A", 29, 1), k("S", 47, 1), k("D", 32, 1), k("F", 34, 1),
                k("G", 35, 1), k("H", 36, 1), k("J", 38, 1), k("K", 39, 1), k("L", 40, 1),
                k(";\n:", 74, 1), k("\"\n'", 75, 1), k("Enter", 66, 1.8f));

        row(board,
                k("Shift", 59, 2), k("Z", 54, 1), k("X", 52, 1), k("C", 31, 1), k("V", 50, 1),
                k("B", 30, 1), k("N", 42, 1), k("M", 41, 1), k("<\n,", 55, 1), k(">\n.", 56, 1),
                k("?\n/", 76, 1), k("Shift", 60, 2));

        row(board,
                k("Ctrl", 113, 1.3f), k("Alt", 57, 1.3f), k("Space", 62, 6.2f),
                k("Alt", 58, 1.3f), k("Ctrl", 114, 1.3f), k("←", 21, 1));

        return board;
    }

    private Entry k(String label, int code, float weight) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextColor(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 8 ? 12 : 14);
        b.setGravity(Gravity.CENTER);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setBackground(shape(key));
        Entry e = new Entry(b, code, weight);
        b.setOnClickListener(v -> select(e));
        entries.add(e);
        return e;
    }

    private void row(LinearLayout parent, Entry... items) {
        LinearLayout r = new LinearLayout(context);
        r.setGravity(Gravity.CENTER_VERTICAL);
        for (Entry e : items) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, e.weight);
            p.setMargins(dp(2), dp(2), dp(2), dp(2));
            r.addView(e.button, p);
        }
        parent.addView(r, new LinearLayout.LayoutParams(-1, dp(58)));
    }

    private Button action(int stringId) {
        Button b = new Button(context);
        b.setText(stringId);
        b.setTextColor(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setBackground(shape(key));
        return b;
    }

    private void select(Entry entry) {
        selected = entry.code;
        for (Entry e : entries) e.button.setBackground(shape(e == entry ? selectedKey : key));
        confirm.setEnabled(true);
        confirm.setAlpha(1f);
    }

    private GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(9));
        return d;
    }

    private int color(int attr, int fallback) {
        TypedValue v = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, v, true)) return fallback;
        if (v.resourceId != 0) {
            try { return context.getResources().getColor(v.resourceId); } catch (Exception ignored) {}
        }
        return v.data;
    }

    private int darken(int c, float f) {
        return Color.rgb((int)(Color.red(c) * f), (int)(Color.green(c) * f), (int)(Color.blue(c) * f));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Entry {
        final Button button;
        final int code;
        final float weight;
        Entry(Button button, int code, float weight) {
            this.button = button;
            this.code = code;
            this.weight = weight;
        }
    }
}
