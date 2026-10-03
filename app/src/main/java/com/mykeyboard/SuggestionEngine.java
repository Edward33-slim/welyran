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
            "نص","رسالة","اسم","رقم","صورة","ملف","مشكلة","حل","طريق","مكان","شيء","البيت",
            "العمل","الوقت","الكلام","النص","الرسالة","الناس","الخير","حالك","لك","لي","معك",
            "عندي","عندك","لدينا","لدي","تريد","يريد","تريدين","أن","أنها","أنه","أنني","لأن",
            "لذلك","ثم","أيضاً","أيضا","مرة","دائماً","دائما","اليوم","بكرة"
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
            "in","at","to","of","by","as","my","your","his","her","our","their","me","him","them",
            "please","could","help","make","build","test","download","application"
    );

    // Common next-word relationships provide useful predictions before the
    // personal model has learned enough text.
    private final Map<String, String[]> arabicBigrams = new HashMap<>();
    private final Map<String, String[]> englishBigrams = new HashMap<>();

    public SuggestionEngine(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences("prediction", Context.MODE_PRIVATE);
        initBigrams();
    }

    private void initBigrams() {
        arabicBigrams.put("أنا", new String[]{"أريد","أحتاج","أعرف"});
        arabicBigrams.put("أريد", new String[]{"أن","هذا","شيء"});
        arabicBigrams.put("أحتاج", new String[]{"إلى","هذا","مساعدة"});
        arabicBigrams.put("أنت", new String[]{"تريد","تعرف","تكتب"});
        arabicBigrams.put("كيف", new String[]{"حالك","يمكنني","أكتب"});
        arabicBigrams.put("ماذا", new String[]{"تريد","تفعل","تكتب"});
        arabicBigrams.put("هذا", new String[]{"هو","جيد","ما"});
        arabicBigrams.put("هذه", new String[]{"هي","الكلمة","الصورة"});
        arabicBigrams.put("في", new String[]{"البيت","العمل","الوقت"});
        arabicBigrams.put("على", new String[]{"الطريق","هذا","الكيبورد"});
        arabicBigrams.put("من", new String[]{"أجل","هذا","هنا"});
        arabicBigrams.put("شكراً", new String[]{"لك","جزيلاً"});
        arabicBigrams.put("شكرا", new String[]{"لك","جزيلاً"});
        arabicBigrams.put("الكيبورد", new String[]{"العربي","الإنجليزي"});
        arabicBigrams.put("العربية", new String[]{"والإنجليزية","سهلة","الآن"});

        englishBigrams.put("i", new String[]{"want","need","can"});
        englishBigrams.put("you", new String[]{"can","are","have"});
        englishBigrams.put("the", new String[]{"next","word","keyboard"});
        englishBigrams.put("how", new String[]{"are","do","can"});
        englishBigrams.put("what", new String[]{"do","is","are"});
        englishBigrams.put("where", new String[]{"is","are","can"});
        englishBigrams.put("why", new String[]{"are","do","is"});
        englishBigrams.put("to", new String[]{"the","use","do"});
        englishBigrams.put("in", new String[]{"the","this","a"});
        englishBigrams.put("on", new String[]{"the","this","my"});
        englishBigrams.put("thank", new String[]{"you"});
        englishBigrams.put("thanks", new String[]{"for"});
        englishBigrams.put("my", new String[]{"keyboard","phone","app"});
        englishBigrams.put("this", new String[]{"is","keyboard","app"});
        englishBigrams.put("i'm", new String[]{"going","using","trying"});
    }

    public String currentWord(String text) {
        if (text == null || text.isEmpty()) return "";
        int i = text.length() - 1;
        while (i >= 0 && isWordChar(text.charAt(i))) i--;
        return text.substring(i + 1);
    }

    private String previousCompletedWord(String text) {
        if (text == null || text.isEmpty()) return "";
        int end = text.length() - 1;

        while (end >= 0 && !isWordChar(text.charAt(end))) end--;
        if (end < 0) return "";

        int start = end;
        while (start >= 0 && isWordChar(text.charAt(start))) start--;

        // If the last characters form the current word, skip it and find
        // the word before it.
        int separator = start;
        while (separator >= 0 && !isWordChar(text.charAt(separator))) separator--;
        if (separator < 0) return "";

        int prevEnd = separator;
        int prevStart = prevEnd;
        while (prevStart >= 0 && isWordChar(text.charAt(prevStart))) prevStart--;

        return text.substring(prevStart + 1, prevEnd + 1);
    }

    private String lastCompletedWord(String text) {
        if (text == null || text.isEmpty()) return "";
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return "";

        int end = trimmed.length() - 1;
        while (end >= 0 && isWordChar(trimmed.charAt(end))) end--;
        if (end == trimmed.length() - 1) {
            return currentWord(trimmed);
        }
        return "";
    }

    public List<String> suggest(String text, boolean arabic) {
        String current = currentWord(text);
        String previous = previousCompletedWord(text);
        boolean typingCurrent = !current.isEmpty();

        Set<String> result = new LinkedHashSet<>();

        if (typingCurrent) {
            // First slot: verbatim current word, as in a real prediction bar.
            result.add(current);

            List<Candidate> candidates = new ArrayList<>();
            String partial = normalize(current);
            List<String> dictionary = arabic ? arabicWords : englishWords;

            for (String word : dictionary) {
                addCandidate(candidates, partial, word);
            }

            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith("word.")) {
                    String word = key.substring(5);
                    if (!word.isEmpty()) addCandidate(candidates, partial, word);
                }
            }

            candidates.sort((a, b) -> {
                if (a.distance != b.distance) return Integer.compare(a.distance, b.distance);
                return Integer.compare(b.frequency, a.frequency);
            });

            for (Candidate c : candidates) {
                result.add(c.word);
                if (result.size() >= 3) break;
            }

            // If the current word has only one/two matches, use the context
            // to fill the remaining prediction slot with the next word.
            if (result.size() < 3 && !previous.isEmpty()) {
                for (String word : nextWords(previous, arabic)) {
                    result.add(word);
                    if (result.size() >= 3) break;
                }
            }
        } else {
            // No current word: show three next-word predictions.
            for (String word : nextWords(lastCompletedWord(text), arabic)) {
                result.add(word);
                if (result.size() >= 3) break;
            }

            if (result.isEmpty()) {
                for (String word : (arabic ? arabicWords : englishWords)) {
                    result.add(word);
                    if (result.size() >= 3) break;
                }
            }
        }

        return new ArrayList<>(result);
    }

    private void addCandidate(List<Candidate> list, String partial, String word) {
        String normalized = normalize(word);
        if (normalized.isEmpty() || normalized.equals(partial)) return;

        if (normalized.startsWith(partial)) {
            list.add(new Candidate(word, wordFrequency(word), 0));
        } else if (partial.length() >= 2 && editDistance(partial, normalized) <= 1) {
            list.add(new Candidate(word, wordFrequency(word), 1));
        }
    }

    public void learnText(String text) {
        if (text == null || text.isEmpty()) return;

        String[] words = text.trim().split("[^\\p{L}\\p{Nd}]+");
        SharedPreferences.Editor editor = prefs.edit();
        String previous = null;

        for (String word : words) {
            if (word.isEmpty()) continue;

            String normalized = normalizeKey(word);
            if (normalized.isEmpty()) continue;

            String wordKey = "word." + normalized;
            int frequency = prefs.getInt(wordKey, 0);
            editor.putInt(wordKey, Math.min(10000, frequency + 1));

            if (previous != null) {
                String pairKey = "pair." + normalizeKey(previous) + ">" + normalized;
                int pairFrequency = prefs.getInt(pairKey, 0);
                editor.putInt(pairKey, Math.min(10000, pairFrequency + 1));
            }
            previous = word;
        }

        editor.apply();
    }

    private List<String> nextWords(String previous, boolean arabic) {
        List<Ranked> ranked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (previous != null && !previous.isEmpty()) {
            String key = normalizeKey(previous);

            // Personal learned bigrams get the strongest weight.
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                if (!e.getKey().startsWith("pair.")) continue;

                String pair = e.getKey().substring(5);
                int split = pair.indexOf('>');
                if (split <= 0) continue;

                if (pair.substring(0, split).equals(key)) {
                    String next = pair.substring(split + 1);
                    int count = toInt(e.getValue());
                    if (!next.isEmpty()) ranked.add(new Ranked(next, 100000 + count));
                }
            }

            Map<String, String[]> builtIn = arabic ? arabicBigrams : englishBigrams;
            String[] builtInWords = builtIn.get(previous);
            if (builtInWords == null) builtInWords = builtIn.get(normalize(previous));

            if (builtInWords != null) {
                for (int i = 0; i < builtInWords.length; i++) {
                    ranked.add(new Ranked(builtInWords[i], 5000 - i));
                }
            }
        }

        // Add frequent learned words as fallbacks.
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!e.getKey().startsWith("word.")) continue;
            String word = e.getKey().substring(5);
            if (!word.isEmpty()) ranked.add(new Ranked(word, toInt(e.getValue())));
        }

        ranked.sort(Comparator.comparingInt((Ranked r) -> r.score).reversed());

        for (Ranked r : ranked) {
            if (seen.add(r.word)) {
                if (seen.size() >= 3) break;
            }
        }

        return new ArrayList<>(seen);
    }

    private int wordFrequency(String word) {
        return prefs.getInt("word." + normalizeKey(word), 0);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
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

            int[] tmp = prev;
            prev = curr;
            curr = tmp;
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

    private static final class Ranked {
        final String word;
        final int score;

        Ranked(String word, int score) {
            this.word = word;
            this.score = score;
        }
    }
}
