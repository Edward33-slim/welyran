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
    private boolean shifted = false;
    private boolean symbols = false;

    @Override public View onCreateInputView() {
        LinearLayout keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setPadding(5, 5, 5, 5);
        keyboard.setBackgroundColor(Color.rgb(45, 45, 45));

        if (symbols) {
            addRow(keyboard, new String[]{"1","2","3","4","5","6","7","8","9","0"});
            addRow(keyboard, new String[]{"@","#","$","%","&","*","-","+","(",")"});
        } else {
            addRow(keyboard, new String[]{"q","w","e","r","t","y","u","i","o","p"});
            addRow(keyboard, new String[]{"a","s","d","f","g","h","j","k","l"});
            addRow(keyboard, new String[]{"⇧","z","x","c","v","b","n","m","⌫"});
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);

        addSpecial(bottom, symbols ? "ABC" : "?123", 1.25f, v -> {
            symbols = !symbols;
            setInputView(onCreateInputView());
        });
        addSpecial(bottom, "🌐", 0.8f, v -> switchToNextInputMethod(false));
        addSpecial(bottom, "space", 3.5f, v -> commit(" "));
        addSpecial(bottom, "↵", 1.2f, v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.performEditorAction(EditorInfo.IME_ACTION_DONE);
        });

        keyboard.addView(bottom, new LinearLayout.LayoutParams(-1, dp(52)));
        return keyboard;
    }

    private void addRow(LinearLayout parent, String[] keys) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (String key : keys) {
            final String value = key;
            addKey(row, display(value), 1f, v -> handleKey(value));
        }
        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(52)));
    }

    private String display(String key) {
        return shifted && key.length() == 1 && Character.isLetter(key.charAt(0))
                ? key.toUpperCase() : key;
    }

    private void handleKey(String key) {
        if ("⇧".equals(key)) {
            shifted = !shifted;
            setInputView(onCreateInputView());
            return;
        }
        if ("⌫".equals(key)) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
            return;
        }
        commit(display(key));
        if (shifted) {
            shifted = false;
            setInputView(onCreateInputView());
        }
    }

    private void commit(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    private void addKey(LinearLayout row, String text, float weight, View.OnClickListener listener) {
        Button b = makeButton(text);
        b.setOnClickListener(listener);
        row.addView(b, new LinearLayout.LayoutParams(0, dp(48), weight));
    }

    private void addSpecial(LinearLayout row, String text, float weight, View.OnClickListener listener) {
        addKey(row, text, weight, listener);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(80, 80, 80));
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        return b;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
