package com.mykeyboard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SuggestionEngine {
    private final SharedPreferences prefs;

    // Built-in common vocabulary. Learned words are added dynamically.
    private final List<String> arabicWords = Arrays.asList(
            "أنا","أنت","أنتِ","هو","هي","نحن","أنتم","هم","هذا","هذه","ذلك","تلك",
            "الذي","التي","الذين","من","ما","ماذا","متى","أين","كيف","لماذا","هل","و","أو","لكن",
            "في","على","إلى","من","عن","مع","بين","تحت","فوق","عند","قبل","بعد","حتى","إذا",
            "كان","كانت","يكون","تكون","يمكن","يمكنني","أستطيع","سوف","سأكون","ليس","ليست",
            "لقد","قد","لا","نعم","كل","بعض","أي","هناك","هنا","الآن","اليوم","غداً","أمس",
            "صباح","مساء","ليلة","وقت","يوم","أسبوع","شهر","سنة","جيد","جيدة","جميل","جميلة",
            "كبير","صغير","جديد","قديم","سريع","بطيء","سهل","صعب","مهم","صحيح","خطأ",
            "أريد","أحتاج","أعرف","أحب","أكره","أقول","أكتب","أقرأ","أستخدم","أعمل","أذهب",
            "تعال","افتح","أغلق","اضغط","اكتب","اختر","أعطني","أخبرني","ساعدني","شكراً","شكرا",
            "مرحبا","مرحباً","أهلاً","السلام","عليكم","حسناً","تمام","طيب","أكيد","ربما",
            "كتاب","بيت","منزل","مدرسة","جامعة","عمل","سيارة","هاتف","جهاز","برنامج","تطبيق",
            "لوحة","مفاتيح","كيبورد","لغة","عربي","العربية","إنجليزي","الإنجليزية","كلمة","كلمات",
            "نص","رسالة","اسم","رقم","صورة","ملف","مشكلة","حل","طريق","مكان","شيء","وقت",
            "البيت","العمل","الوقت","الكلام","النص","الرسالة","الناس","الخير","السلام","حالك",
            "لك","لي","معك","عندي","عندك","لدينا","لدي","أريد","تريد","يريد","تريدين",
            "أن","أنها","أنه","أنني","لأن","لذلك","ثم","أيضاً","أيضا","مرة","دائماً","دائما"
    );

    private final List<String> englishWords = Arrays.asList(
            "i","you","he","she","we","they","it","this","that","these","those","the","a","an",
            "and","or","but","if","then","so","because","what","when","where","why","how","who",
            "which","is","are","was","were","be","been","being","have","has","had","do","does","did",
            "can","could","will","would","should","may","might","must","not","yes","no","all","some",
            "any","there","here","now","today","tomorrow","yesterday","morning","evening","night",
            "good","great","nice","new","old","big","small","fast","slow","easy","hard","important",
            "right","wrong","want","need","know","like","love","hate","say","write","read","use",
            "make","go","come","open","close","press","choose","give","tell","help","thanks","thank",
            "hello","please","okay","ok","sure","maybe","really","very","just","also","again",
            "home","house","school","university","work","car","phone","device","program","app",
            "keyboard","language","english","arabic","word","words","text","message","name","number",
            "picture","file","problem","solution","road","place","thing","time","people","next",
            "current","prediction","typing","type","input","with","from","into","about","for","on",
            "in","at","to","of","by","as","my","your","his","her","our","their","me","him","them"
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
        String current = currentWord(text);
        String previous = previousWord(text);
        boolean typingCurrent = !current.isEmpty();

        List<String> dictionary = arabic ? arabicWords : englishWords;
        Set<String> result = new LinkedHashSet<>();

        if (typingCurrent) {
            // 1. Current-word prediction: prefix + one-edit correction.
            List<Candidate> candidates = new ArrayList<>();
            String partial = normalize(current);

            for (String word : dictionary) {
                addCurrentCandidate(candidates, partial, word);
            }

            // Learned vocabulary is searched too.
            for (String key : prefs.getAll().keySet()) {
                if (!key.startsWith("word.")) continue;
                String learned = key.substring(5);
                if (learned.isEmpty()) continue;
                addLearnedCandidate(candidates, partial, learned);
            }

            candidates.sort((a, b) -> {
                if (a.distance != b.distance) return Integer.compare(a.distance, b.distance);
                return Integer.compare(b.frequency, a.frequency);
            });

            for (Candidate c : candidates) {
                if (!normalize(c.word).equals(partial)) result.add(c.word);
                if (result.size() == 3) break;
            }

            // If there are not enough current-word matches, add next-word
            // prediction as a useful third suggestion.
            if (result.size() < 3) {
                String next = nextWord(previous, arabic);
                if (next != null) result.add(next);
            }
        } else {
            // 2. Next-word prediction after a completed word/space.
            String next = nextWord(previous, arabic);
            if (next != null) result.add(next);

            // Add two more likely next words.
            List<NextCandidate> nextCandidates = nextWordCandidates(previous, arabic);
            for (NextCandidate c : nextCandidates) {
                result.add(c.word);
                if (result.size() == 3) break;
            }

            // At the beginning, show high-frequency/common words.
            if (result.isEmpty()) {
                for (String word : mostFrequent(dictionary)) {
                    result.add(word);
                    if (result.size() == 3) break;
                }
            }
        }

        return new ArrayList<>(result);
    }

    private void addCurrentCandidate(List<Candidate> list, String partial, String word) {
        String normalized = normalize(word);
        if (normalized.isEmpty() || normalized.equals(partial)) return;

        if (normalized.startsWith(partial)) {
            list.add(new Candidate(word, wordFrequency(word), 0));
        } else if (partial.length() >= 2 && editDistance(partial, normalized) <= 1) {
            list.add(new Candidate(word, wordFrequency(word), 1));
        }
    }

    private void addLearnedCandidate(List<Candidate> list, String partial, String normalizedLearned) {
        if (normalizedLearned.equals(partial)) return;
        if (!normalizedLearned.startsWith(partial) &&
                (partial.length() < 2 || editDistance(partial, normalizedLearned) > 1)) {
            return;
        }

        int frequency = prefs.getInt("word." + normalizedLearned, 0);
        list.add(new Candidate(normalizedLearned, frequency,
                normalizedLearned.startsWith(partial) ? 0 : 1));
    }

    public void learnText(String text) {
        if (text == null || text.isEmpty()) return;

        String[] words = text.trim().split("[^\\p{L}\\p{Nd}]+");
        String previous = null;
        SharedPreferences.Editor editor = prefs.edit();

        for (String word : words) {
            if (word.isEmpty()) continue;

            String normalizedWord = normalizeKey(word);
            if (normalizedWord.isEmpty()) continue;

            String key = "word." + normalizedWord;
            int frequency = prefs.getInt(key, 0);
            editor.putInt(key, Math.min(frequency + 1, 10000));

            if (previous != null) {
                String pairKey = "pair." + normalizeKey(previous) + ">" + normalizedWord;
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
        if (previous == null || previous.isEmpty()) {
            return null;
        }

        String prefix = normalizeKey(previous);
        String best = null;
        int bestCount = 0;

        // Search learned bigrams across the user's own vocabulary.
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("pair.")) continue;

            String pair = key.substring(5);
            int separator = pair.indexOf('>');
            if (separator <= 0) continue;

            String left = pair.substring(0, separator);
            String right = pair.substring(separator + 1);

            if (!left.equals(prefix)) continue;

            int count = toInt(entry.getValue());
            if (count > bestCount) {
                bestCount = count;
                best = right;
            }
        }

        if (best != null) return best;

        // Useful built-in language model fallback.
        if (arabic) {
            switch (previous) {
                case "أنا": return "أريد";
                case "أريد": return "أن";
                case "هذا": return "هو";
                case "هذه": return "هي";
                case "في": return "البيت";
                case "على": return "الطريق";
                case "من": return "أجل";
                case "شكراً":
                case "شكرا": return "لك";
                case "كيف": return "حالك";
                case "ماذا": return "تريد";
                case "أنت": return "تريد";
                case "الكيبورد": return "العربي";
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
            case "i'm": return "going";
            default: return "the";
        }
    }

    private List<NextCandidate> nextWordCandidates(String previous, boolean arabic) {
        List<NextCandidate> result = new ArrayList<>();
        if (previous == null || previous.isEmpty()) return result;

        String prefix = normalizeKey(previous);

        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("pair.")) continue;

            String pair = key.substring(5);
            int separator = pair.indexOf('>');
            if (separator <= 0) continue;

            String left = pair.substring(0, separator);
            String right = pair.substring(separator + 1);

            if (left.equals(prefix) && !right.isEmpty()) {
                result.add(new NextCandidate(right, toInt(entry.getValue())));
            }
        }

        result.sort(Comparator.comparingInt((NextCandidate c) -> c.frequency).reversed());

        // Add a few generic words so the bar never looks empty.
        for (String word : arabic ? arabicWords : englishWords) {
            result.add(new NextCandidate(word, wordFrequency(word)));
        }

        return result;
    }

    private int wordFrequency(String word) {
        return prefs.getInt("word." + normalizeKey(word), 0);
    }

    private List<String> mostFrequent(List<String> dictionary) {
        List<String> result = new ArrayList<>(dictionary);
        result.sort(Comparator.comparingInt(this::wordFrequency).reversed());
        return result;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT);
    }

    private String normalizeKey(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]", "");
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    private int toInt(Object value) {
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Long) return ((Long) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
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

    private static final class NextCandidate {
        final String word;
        final int frequency;

        NextCandidate(String word, int frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }
}
