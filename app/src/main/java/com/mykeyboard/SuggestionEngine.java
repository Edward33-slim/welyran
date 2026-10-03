package com.mykeyboard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SuggestionEngine {
    private final SharedPreferences prefs;

    private final List<String> arabicWords = Arrays.asList(
            "هذا","هذه","ذلك","تلك","الذي","التي","الذين","من","في","على","إلى","عن","مع",
            "كان","كانت","يكون","يمكن","لقد","ليس","أنا","أنت","أنتِ","هو","هي","نحن","هم",
            "اليوم","غداً","الآن","أمس","جيد","جميل","كبير","صغير","جديد","سريع","سهل","مهم",
            "كتاب","بيت","عمل","وقت","كلمة","تطبيق","لوحة","مفاتيح","لغة","عربي","العربية",
            "إنجليزي","الإنجليزية","شكراً","مرحبا","أهلاً","كيف","ماذا","لماذا","أريد","أحتاج",
            "أعرف","أحب","سوف","سأقوم","التالي","يمكنني","أستطيع","لدينا","عندي","عندك","معك",
            "منزل","مدرسة","سيارة","هاتف","جهاز","برنامج","مشكلة","حل","صحيح","خطأ","العمل",
            "الوقت","الكلام","النص","الرسالة","الاسم","الناس","الخير","السلام","صباح","مساء"
    );

    private final List<String> englishWords = Arrays.asList(
            "the","this","that","these","those","and","or","but","for","with","from","into","about",
            "you","your","are","is","was","were","have","has","had","can","will","would","could",
            "should","not","yes","hello","thanks","today","tomorrow","now","good","great","new",
            "old","easy","fast","small","large","app","keyboard","language","english","arabic",
            "word","next","current","prediction","text","input","home","work","time","phone",
            "device","program","problem","solution","right","wrong","message","name","people",
            "morning","evening","please","want","need","know","like","love","make","use","with",
            "this","there","here","what","why","how","where","when","who","which"
    );

    public SuggestionEngine(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences("prediction", Context.MODE_PRIVATE);
    }

    public String currentWord(String text) {
        if (text == null || text.isEmpty()) return "";
        int end = text.length();
        int i = end - 1;
        while (i >= 0 && isWordChar(text.charAt(i))) i--;
        return text.substring(i + 1, end);
    }

    public List<String> suggest(String text, boolean arabic) {
        List<String> dictionary = arabic ? arabicWords : englishWords;
        String partial = currentWord(text);
        String normalizedPartial = normalize(partial, arabic);
        Set<String> result = new LinkedHashSet<>();

        // 1) Prefix predictions: closest/high-frequency learned words first.
        if (!normalizedPartial.isEmpty()) {
            List<Candidate> candidates = new ArrayList<>();
            for (String word : dictionary) {
                String w = normalize(word, arabic);
                if (w.startsWith(normalizedPartial) && !w.equals(normalizedPartial)) {
                    candidates.add(new Candidate(word, wordFrequency(word), 0));
                } else {
                    int distance = editDistance(normalizedPartial, w);
                    if (distance <= 1 && !w.equals(normalizedPartial)) {
                        candidates.add(new Candidate(word, wordFrequency(word), distance));
                    }
                }
            }

            candidates.sort((a, b) -> {
                if (a.distance != b.distance) return Integer.compare(a.distance, b.distance);
                return Integer.compare(b.frequency, a.frequency);
            });

            for (Candidate c : candidates) {
                result.add(c.word);
                if (result.size() == 3) return new ArrayList<>(result);
            }
        }

        // 2) Next-word prediction from the last completed word and learned bigrams.
        String previous = previousWord(text);
        String next = nextWord(previous, arabic);
        if (next != null) result.add(next);

        for (String word : mostFrequent(dictionary)) {
            if (result.size() >= 3) break;
            result.add(word);
        }

        return new ArrayList<>(result);
    }

    public void learnText(String text) {
        if (text == null || text.isEmpty()) return;

        String[] words = text.trim().split("[^\p{L}\p{Nd}]+");
        String previous = null;
        SharedPreferences.Editor editor = prefs.edit();

        for (String word : words) {
            if (word.isEmpty()) continue;

            String key = "word." + normalizeKey(word);
            int frequency = prefs.getInt(key, 0);
            editor.putInt(key, Math.min(frequency + 1, 10000));

            if (previous != null) {
                String pairKey = "pair." + normalizeKey(previous) + ">" + normalizeKey(word);
                int pairFrequency = prefs.getInt(pairKey, 0);
                editor.putInt(pairKey, Math.min(pairFrequency + 1, 10000));
            }
            previous = word;
        }
        editor.apply();
    }

    private String previousWord(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String trimmed = text.trim();
        int end = trimmed.length();
        int i = end - 1;
        while (i >= 0 && isWordChar(trimmed.charAt(i))) i--;
        return trimmed.substring(i + 1, end);
    }

    private String nextWord(String previous, boolean arabic) {
        if (previous == null || previous.isEmpty()) return null;

        String prefix = normalizeKey(previous);
        String best = null;
        int bestCount = 0;

        // Learned bigrams get priority over built-in fallback phrases.
        for (String word : arabic ? arabicWords : englishWords) {
            int count = prefs.getInt("pair." + prefix + ">" + normalizeKey(word), 0);
            if (count > bestCount) {
                bestCount = count;
                best = word;
            }
        }

        if (best != null) return best;

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
                case "كيف": return "حالك";
                case "ماذا": return "تريد";
                default: return "هذا";
            }
        }

        switch (previous.toLowerCase(Locale.ROOT)) {
            case "i": return "want";
            case "you": return "can";
            case "the": return "next";
            case "thank": return "you";
            case "in": return "the";
            case "to": return "the";
            case "how": return "are";
            case "what": return "do";
            default: return "the";
        }
    }

    private int wordFrequency(String word) {
        return prefs.getInt("word." + normalizeKey(word), 0);
    }

    private List<String> mostFrequent(List<String> dictionary) {
        List<String> result = new ArrayList<>(dictionary);
        result.sort(Comparator.comparingInt(this::wordFrequency).reversed());
        return result;
    }

    private String normalize(String value, boolean arabic) {
        if (value == null) return "";
        return arabic ? value : value.toLowerCase(Locale.ROOT);
    }

    private String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\p{L}\p{Nd}]", "");
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    private int editDistance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) return 2;

        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }

    private static final class Candidate {
        final String word;
        final int frequency;
        final int distance;

        Candidate(String word, int frequency, int distance) {
            this.word = word;
            this.frequency = frequency;
            this.distance = distance;
        }
    }
}
