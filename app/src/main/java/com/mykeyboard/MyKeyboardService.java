package com.mykeyboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
    private boolean shift = false;

    private LinearLayout root;
    private LinearLayout suggestions;
    private SuggestionEngine predictor;

    // Rows are intentionally reversed so the keys that were on the right
    // appear on the left, matching the requested mirrored layout.
    private static final String[] EN = {
            "poiuytrewq",
            "lkjhgfdsa",
            "mnbvcxz"
    };

    private static final String[] AR = {
            "دجحخهعغفقثصض",
            "طكنمئتايسش",
            "ظوزوةىلارؤءئ"
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
        shift = false;
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
        root.setPadding(dp(4), dp(4), dp(4), dp(5));
        root.setBackgroundColor(Color.rgb(29, 30, 37));

        buildSuggestionToolbar();
        buildSuggestionRow();
        addNumberRow();

        String[] rows = arabic ? AR : EN;
        for (String row : rows) {
            String[] keys = new String[row.length()];
            for (int i = 0; i < row.length(); i++) {
                keys[i] = String.valueOf(row.charAt(i));
            }
            addLetterRow(keys);
        }

        buildBottomRow();
        updateSuggestions();
    }

    private void buildSuggestionToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(2), 0);

        addTool(bar, "•••", 0.9f, v -> {});
        addTool(bar, "ⓘ", 0.9f, v -> {});
        addTool(bar, "文\nA", 1.0f, v -> {});
        addTool(bar, "▣", 1.0f, v -> pasteClipboard());
        addTool(bar, "☺", 1.0f, v -> {});
        addTool(bar, "GIF", 1.0f, v -> {});
        addTool(bar, "⌕", 1.0f, v -> {});

        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private void buildSuggestionRow() {
        suggestions = new LinearLayout(this);
        suggestions.setGravity(Gravity.CENTER_VERTICAL);
        suggestions.setPadding(dp(5), 0, dp(5), 0);
        root.addView(suggestions, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void updateSuggestions() {
        if (suggestions == null || predictor == null) return;

        suggestions.removeAllViews();

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        CharSequence before = ic.getTextBeforeCursor(120, 0);
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
        t.setPadding(dp(8), 0, dp(8), 0);
        t.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;

            CharSequence before = ic.getTextBeforeCursor(120, 0);
            String context = before == null ? "" : before.toString();
            String partial = predictor.currentWord(context);

            if (!partial.isEmpty()) {
                ic.deleteSurroundingText(partial.length(), 0);
            }

            ic.commitText(value + " ", 1);
            predictor.learnText(value + " ");
            updateSuggestions();
        });

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(t, p);
    }

    private void addNumberRow() {
        String[] numbers = {"٠","٩","٨","٧","٦","٥","٤","٣","٢","١"};
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (String n : numbers) addKey(row, n, 1f);
        root.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void addLetterRow(String[] keys) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (String key : keys) addKey(row, key, 1f);
        root.addView(row, new LinearLayout.LayoutParams(-1, dp(55)));
    }

    private void buildBottomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);

        addSpecial(row, "123", 0.95f, v -> {});
        addSpecial(row, "☺", 0.75f, v -> {});
        addSpecial(row, "،", 0.55f, v -> commit("،"));
        addSpecial(row, arabic ? "EN" : "ع", 0.9f, v -> {
            arabic = !arabic;
            shift = false;
            buildKeyboard();
        });
        addSpecial(row, "مسافة", 3.4f, v -> commit(" "));
        addSpecial(row, ".", 0.55f, v -> commit(arabic ? "،" : "."));
        addSpecial(row, "⌫", 0.9f, v -> deleteOne());
        addSpecial(row, "↵", 0.9f, v -> enter());

        root.addView(row, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private void addKey(LinearLayout row, String label, float weight) {
        Button b = new Button(this);
        String shown = shift && !arabic ? label.toUpperCase() : label;
        b.setText(shown);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
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
        b.setPadding(0, 0, 0, 0);
        b.setBackground(keyBackground(true));
        b.setOnClickListener(click);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(b, p);
    }

    private void addTool(LinearLayout row, String label, float weight, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.LTGRAY);
        b.setTextSize(label.contains("GIF") ? 11 : 19);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(click);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
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

        CharSequence before = ic.getTextBeforeCursor(120, 0);
        String context = before == null ? "" : before.toString();

        ic.commitText(text, 1);

        // Learn completed words and useful phrases locally.
        if (" ".equals(text) || text.indexOf('\n') >= 0) {
            predictor.learnText(context + text);
        }

        if (shift) {
            shift = false;
            buildKeyboard();
        } else {
            updateSuggestions();
        }
    }

    private void deleteOne() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.deleteSurroundingText(1, 0);
            updateSuggestions();
        }
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;

        ClipData data = cm.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) return;

        CharSequence text = data.getItemAt(0).coerceToText(this);
        if (text != null) commit(text.toString());
    }

    private void enter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        // Always insert a real newline. Do not submit/search/next.
        ic.commitText("\n", 1);
        updateSuggestions();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
