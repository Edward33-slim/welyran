package com.mykeyboard;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SuggestionEngine {
    private final List<String> arabicWords = Arrays.asList(
            "هذا","هذه","ذلك","الذي","التي","من","في","على","إلى","عن",
            "مع","كان","يكون","يمكن","لقد","ليس","أنا","أنت","هو","هي",
            "نحن","هم","اليوم","غداً","الآن","جيد","جميل","كبير","صغير",
            "كتاب","بيت","عمل","وقت","كلمة","تطبيق","لوحة","مفاتيح","لغة",
            "عربي","العربية","إنجليزي","الإنجليزية","شكراً","مرحبا","كيف",
            "ماذا","لماذا","أريد","أحتاج","أعرف","سوف","سأقوم","التالي"
    );

    private final List<String> englishWords = Arrays.asList(
            "the","this","that","these","those","and","or","but","for","with",
            "from","into","about","you","your","are","is","was","were",
            "have","has","can","will","would","could","should","not","yes",
            "hello","thanks","today","tomorrow","now","good","great","new",
            "app","keyboard","language","english","arabic","word","next",
            "current","prediction","text","input","home","work","time"
    );

    private final Context context;

    public SuggestionEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public String currentWord(String text) {
        if (text == null || text.isEmpty()) return "";
        int end = text.length();
        int i = end - 1;
        while (i >= 0 && !Character.isWhitespace(text.charAt(i))) i--;
        return text.substring(i + 1, end);
    }

    public List<String> suggest(String text, boolean arabic) {
        String partial = currentWord(text);
        List<String> dictionary = arabic ? arabicWords : englishWords;
        Set<String> result = new LinkedHashSet<>();

        if (!partial.isEmpty()) {
            String p = arabic ? partial : partial.toLowerCase(Locale.ROOT);
            for (String word : dictionary) {
                String w = arabic ? word : word.toLowerCase(Locale.ROOT);
                if (w.startsWith(p) && !w.equals(p)) {
                    result.add(word);
                }
                if (result.size() == 2) break;
            }
        }

        // Third candidate: a simple next-word prediction based on the previous word.
        String previous = previousWord(text);
        String next = nextWord(previous, arabic);
        if (next != null) result.add(next);

        while (result.size() < 3) {
            for (String word : dictionary) {
                if (!result.contains(word)) {
                    result.add(word);
                    break;
                }
            }
        }

        return new ArrayList<>(result);
    }

    private String previousWord(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String trimmed = text.trim();
        int i = trimmed.length() - 1;
        while (i >= 0 && !Character.isWhitespace(trimmed.charAt(i))) i--;
        return trimmed.substring(i + 1);
    }

    private String nextWord(String previous, boolean arabic) {
        if (previous == null || previous.isEmpty()) return null;

        if (arabic) {
            switch (previous) {
                case "أنا": return "أريد";
                case "أريد": return "أن";
                case "هذا": return "هو";
                case "هذه": return "هي";
                case "في": return "البيت";
                case "على": return "الطريق";
                case "من": return "أجل";
                case "شكراً": return "لك";
                default: return "هذا";
            }
        } else {
            switch (previous.toLowerCase(Locale.ROOT)) {
                case "i": return "want";
                case "you": return "can";
                case "the": return "next";
                case "thank": return "you";
                case "in": return "the";
                case "to": return "the";
                default: return "the";
            }
        }
    }
}
