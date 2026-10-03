package com.mykeyboard;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class MyKeyboardService extends InputMethodService {
    private boolean arabic = true;
    private LinearLayout root;
    private LinearLayout suggestions;
    private SuggestionEngine predictor;

    // These arrays are intentionally left-to-right. The rows are forced LTR
    // so Android RTL locale settings do not reverse their visual order.
    private static final String[] EN = {
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm"
    };

    private static final String[] AR = {
            "ضصثقفغعهخحجد",
            "شسيبلاتنمكط",
            "ئءؤرلاىةوزظ"
    };

    private static final String[] EN_NUMBERS = {
            "1","2","3","4","5","6","7","8","9","0"
    };

    private static final String[] AR_NUMBERS = {
            "١","٢","٣","٤","٥","٦","٧","٨","٩","٠"
    };

    @Override
    public View onCreateInputView() {
        predictor = new SuggestionEngine(this);
        buildKeyboard();
        return root;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        updateSuggestions();
    }

    private void buildKeyboard() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setTextDirection(View.TEXT_DIRECTION_LTR);
        root.setPadding(dp(3), dp(3), dp(3), dp(4));
        root.setBackgroundColor(Color.rgb(29, 30, 37));

        buildSuggestionRow();
        addNumberRow();

        String[] rows = arabic ? AR : EN;
        for (String rowText : rows) {
            addLetterRow(rowText);
        }

        buildBottomRow();
        updateSuggestions();
    }

    private void buildSuggestionRow() {
        suggestions = new LinearLayout(this);
        suggestions.setOrientation(LinearLayout.HORIZONTAL);
        suggestions.setGravity(Gravity.CENTER);
        suggestions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        suggestions.setTextDirection(View.TEXT_DIRECTION_LTR);
        suggestions.setPadding(dp(4), 0, dp(4), 0);
        root.addView(suggestions, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private void updateSuggestions() {
        if (suggestions == null || predictor == null) return;

        suggestions.removeAllViews();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        CharSequence before = ic.getTextBeforeCursor(200, 0);
        String context = before == null ? "" : before.toString();

        List<String> values = predictor.suggest(context, arabic);
        for (String value : values) {
            addSuggestion(suggestions, value);
        }
    }

    private void addSuggestion(LinearLayout row, String value) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setPadding(dp(5), 0, dp(5), 0);
        t.setBackground(keyBackground(true));

        t.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;

            CharSequence before = ic.getTextBeforeCursor(200, 0);
            String context = before == null ? "" : before.toString();
            String current = predictor.currentWord(context);

            if (!current.isEmpty()) {
                ic.deleteSurroundingText(current.length(), 0);
            }

            ic.commitText(value + " ", 1);
            predictor.learnText(context + value + " ");
            updateSuggestions();
        });

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(dp(2), dp(3), dp(2), dp(3));
        row.addView(t, p);
    }

    private void addNumberRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        String[] numbers = arabic ? AR_NUMBERS : EN_NUMBERS;
        for (String number : numbers) {
            addKey(row, number, 1f);
        }

        root.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void addLetterRow(String rowText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        if (arabic && rowText.equals("ئءؤرلاىةوزظ")) {
            String[] keys = {"ئ","ء","ؤ","ر","لا","ى","ة","و","ز","ظ"};
            for (String key : keys) {
                addKey(row, key, 1f);
            }
        } else {
            for (int i = 0; i < rowText.length(); i++) {
                addKey(row, String.valueOf(rowText.charAt(i)), 1f);
            }
        }

        root.addView(row, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private void buildBottomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        // Fixed left-to-right position: 123, emoji, comma, EN, space, punctuation, backspace, enter.
        addSpecial(row, "123", 0.85f, v -> {});
        addSpecial(row, "☺", 0.65f, v -> {});
        addSpecial(row, arabic ? "،" : ",", 0.55f,
                v -> commit(arabic ? "،" : ","));

        addSpecial(row, arabic ? "EN" : "ع", 0.85f, v -> {
            arabic = !arabic;
            buildKeyboard();
        });

        addSpecial(row, "مسافة", 3.3f, v -> commit(" "));

        addSpecial(row, arabic ? "؟" : ".", 0.55f,
                v -> commit(arabic ? "؟" : "."));

        addSpecial(row, "⌫", 0.85f, v -> deleteOne());
        addSpecial(row, "↵", 0.85f, v -> enter());

        root.addView(row, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private void addKey(LinearLayout row, String label, float weight) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        b.setTextDirection(View.TEXT_DIRECTION_LTR);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(keyBackground(false));
        b.setOnClickListener(v -> commit(b.getText().toString()));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(b, p);
    }

    private void addSpecial(LinearLayout row, String label, float weight, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(label.equals("مسافة") ? 13 : 15);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        b.setTextDirection(View.TEXT_DIRECTION_LTR);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(keyBackground(true));
        b.setOnClickListener(click);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(b, p);
    }

    private GradientDrawable keyBackground(boolean special) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(special ? Color.rgb(64, 65, 75) : Color.rgb(57, 58, 68));
        bg.setCornerRadius(dp(7));
        return bg;
    }

    private void commit(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        CharSequence before = ic.getTextBeforeCursor(200, 0);
        String context = before == null ? "" : before.toString();

        ic.commitText(text, 1);

        if (" ".equals(text) || text.indexOf('\n') >= 0) {
            predictor.learnText(context + text);
        }
        updateSuggestions();
    }

    private void deleteOne() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.deleteSurroundingText(1, 0);
            updateSuggestions();
        }
    }

    private void enter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText("\n", 1);
        updateSuggestions();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
