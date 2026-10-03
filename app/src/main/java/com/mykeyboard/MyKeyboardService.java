package com.mykeyboard;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;

public class MyKeyboardService extends InputMethodService {
    private boolean arabic = false;
    private boolean shift = false;
    private boolean symbols = false;
    private LinearLayout root;

    private static final String[] EN = {
        "qwertyuiop", "asdfghjkl", "zxcvbnm"
    };
    private static final String[] AR = {
        "ضصثقفغعهخحجد", "شسيبلاتنمكط", "ئءؤرلاىةوزظ"
    };

    @Override public View onCreateInputView() {
        buildKeyboard();
        return root;
    }

    private void buildKeyboard() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(4, 5, 4, 5);
        root.setBackgroundColor(Color.rgb(40, 40, 40));

        if (symbols) {
            addRow(new String[]{"1","2","3","4","5","6","7","8","9","0"});
            addRow(new String[]{"@","#","$","%","&","*","(",")","-","+"});
            addRow(new String[]{".",",","?","!","/","=","_","'","\"",";"});
        } else {
            String[] rows = arabic ? AR : EN;
            for (String row : rows) {
                String[] keys = new String[row.length()];
                for (int i = 0; i < row.length(); i++) keys[i] = String.valueOf(row.charAt(i));
                addRow(keys);
            }
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        addSpecial(bottom, "⇧", 1.15f, v -> { shift = !shift; buildKeyboard(); });
        addSpecial(bottom, arabic ? "EN" : "ع", 1.15f, v -> { arabic = !arabic; shift = false; buildKeyboard(); });
        addSpecial(bottom, "⌨", 1.0f, v -> { symbols = !symbols; buildKeyboard(); });
        addSpecial(bottom, "مسافة", 3.2f, v -> commit(" "));
        addSpecial(bottom, "⌫", 1.15f, v -> deleteOne());
        addSpecial(bottom, "↵", 1.15f, v -> enter());
        root.addView(bottom, new LinearLayout.LayoutParams(-1, 54));
    }

    private void addRow(String[] keys) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (String key : keys) addKey(row, key, 1f);
        root.addView(row, new LinearLayout.LayoutParams(-1, 52));
    }

    private void addKey(LinearLayout row, String label, float weight) {
        Button b = new Button(this);
        b.setText(shift && !symbols ? label.toUpperCase() : label);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(75, 75, 75));
        bg.setCornerRadius(8);
        b.setBackground(bg);
        b.setOnClickListener(v -> commit(b.getText().toString()));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
        p.setMargins(2, 2, 2, 2);
        row.addView(b, p);
    }

    private void addSpecial(LinearLayout row, String label, float weight, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(label.equals("مسافة") ? 14 : 17);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(90, 90, 90));
        bg.setCornerRadius(8);
        b.setBackground(bg);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
        p.setMargins(2, 2, 2, 2);
        row.addView(b, p);
    }

    private void commit(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
        if (shift && !symbols) { shift = false; buildKeyboard(); }
    }

    private void deleteOne() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.deleteSurroundingText(1, 0);
    }

    private void enter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo info = getCurrentInputEditorInfo();
        int action = info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            ic.commitText("\n", 1);
        }
    }
}
