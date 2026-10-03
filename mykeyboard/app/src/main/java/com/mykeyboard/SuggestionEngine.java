package com.mykeyboard;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private final Context appContext;

    // Compact static Arabic frequency dictionary. The local model then adds
    // personalized word, bigram and trigram boosts on top of this baseline.
    private final List<String> arabicFrequencyWords = new ArrayList<>();
    private final Map<String, Integer> arabicFrequencyRanks = new HashMap<>();

    private final List<String> arabicWords = Arrays.asList(
            "أنا","أنت","أنتِ","أنتما","أنتم","أنتن","هو","هي","هما","هم","هن","نحن",
            "هذا","هذه","هذان","هاتان","هؤلاء","ذلك","تلك","أولئك","الذي","التي","اللذان","اللتان","الذين",
            "من","ما","ماذا","متى","أين","كيف","لماذا","هل","كم","أي","أينما","حيث","حين","بينما",
            "و","أو","ثم","لكن","بل","لأن","لذلك","لعل","حتى","إذا","إن","أن","كي","كما","مثل","أيضا","أيضاً",
            "في","على","إلى","من","عن","مع","بين","تحت","فوق","أمام","خلف","داخل","خارج","حول","عند","قبل","بعد",
            "منذ","خلال","بدون","ضد","نحو","لدى","لدي","لدينا","لديك","لديه","لها","له","لي","لك","لكم","معي","معك",
            "كان","كانت","كانوا","كنت","كنتِ","كنا","يكون","تكون","يكونون","أصبح","أصبحت","صار","صارت","ليس","ليست",
            "لا","لن","لم","نعم","قد","لقد","سوف","سأ","سوف","يمكن","يمكنني","يمكنك","أستطيع","تستطيع","نستطيع",
            "أريد","تريد","يريد","تريدين","نريد","أحتاج","تحتاج","يحتاج","أعرف","تعرف","يعرف","نعرف",
            "أحب","تحب","يحب","أكره","يكره","أقول","تقول","يقول","نقول","أكتب","تكتب","يكتب","نكتب",
            "أقرأ","تقرأ","يقرأ","أستخدم","تستخدم","يستخدم","نعمل","أعمل","تعمل","يعمل","أذهب","تذهب","يذهب",
            "آتي","تأتي","يأتي","نأتي","تعال","تعالي","تعالوا","اذهب","اذهبي","اذهبوا","افتح","افتحي","افتحوا",
            "أغلق","أغلقي","أغلقوا","اضغط","اضغطي","اضغطوا","اكتب","اكتبي","اكتبوا","اختر","اختاري","اختار",
            "أعطني","أعطيني","أخبرني","أخبرني","ساعدني","ساعدنا","قل","قولي","قل لي","اسأل","اسألي",
            "مرحبا","مرحباً","أهلا","أهلاً","السلام","عليكم","وعليكم","شكرا","شكراً","جزيلا","جزيلاً",
            "عفوا","عفواً","من فضلك","لو سمحت","حسنا","حسناً","تمام","طيب","أكيد","ربما","بالتأكيد",
            "جيد","جيدة","جيداً","أفضل","الأفضل","جميل","جميلة","رائع","رائعة","ممتاز","ممتازة","صحيح","صحيحة",
            "خطأ","صحيحا","مهم","مهمة","ضروري","ضرورية","سهل","سهلة","صعب","صعبة","سريع","سريعة","بطيء","بطيئة",
            "كبير","كبيرة","صغير","صغيرة","جديد","جديدة","قديم","قديمة","طويل","طويلة","قصير","قصيرة",
            "كثير","قليل","كل","بعض","أكثر","أقل","آخر","أخرى","نفس","معظم","جميع","فقط","دائما","دائماً","أحيانا","أحياناً",
            "الآن","اليوم","غدا","غداً","أمس","بكرة","صباح","صباحا","صباحاً","مساء","ليلة","ليل","نهار","وقت","مرة",
            "يوم","أيام","أسبوع","أسابيع","شهر","أشهر","سنة","سنوات","ساعة","دقيقة","دقائق","ثانية","ثواني",
            "بيت","البيت","منزل","المنزل","غرفة","باب","نافذة","مطبخ","مدرسة","جامعة","عمل","العمل","شركة",
            "سيارة","طريق","شارع","مدينة","بلد","دولة","العالم","مكان","هاتف","جهاز","حاسوب","كمبيوتر","برنامج","تطبيق",
            "لوحة","مفاتيح","كيبورد","لوحة المفاتيح","لغة","العربية","عربي","عربية","إنجليزي","إنجليزية","الإنجليزية",
            "كلمة","كلمات","جملة","جمل","نص","نصوص","رسالة","رسائل","اسم","أسماء","رقم","أرقام","صورة","صور",
            "ملف","ملفات","مشكلة","مشاكل","حل","حلول","سؤال","أسئلة","جواب","إجابة","فكرة","أفكار","معلومة","معلومات",
            "خبر","أخبار","خبرة","طريقة","طرق","سبب","أسباب","نتيجة","نتائج","شيء","أشياء","موضوع","مواضيع",
            "الناس","شخص","أشخاص","رجل","امرأة","طفل","أطفال","صديق","صديقي","صديقك","عائلة","أهل","أبي","أمي",
            "أخ","أخت","ابن","بنت","ولد","بنتي","ابني","الخير","الحمد","الله","إن شاء الله","ما شاء الله",
            "الحياة","الدنيا","المستقبل","الماضي","الحاضر","الحقيقة","الحب","السلام","الأمان","النجاح","الوقت",
            "أولا","أولاً","ثانيا","ثانياً","ثالثا","ثالثاً","أخيرا","أخيراً","أيضا","أيضاً","معا","معاً",
            "لكن","ولكن","لأن","لذلك","لهذا","وهذا","وهذه","وهو","وهي","وهم","ويمكن","وإذا","وإن",
            "الكيبورد","الكيبورد العربي","المساعدة","مساعدة","تحديث","تطبيق","التطبيق","المستخدم","المستخدمين",
            "بناء","يبني","بنيت","إنشاء","إنشاءه","تجربة","اختبار","يعمل","يعمل بشكل","بشكل","أفضل",
            "تحميل","تنزيل","تثبيت","فتح","إغلاق","كتابة","الكتابة","تنبؤ","اقتراح","اقتراحات","كلمات","الكلمة",
            "التالية","الحالية","التالي","الحالي","تصحيح","تصحيح تلقائي","قاموس","قاموس عربي","عربي","عربية",
            "نعم","لا","ربما","ممكن","موجود","غير","جدا","جداً","مرة","مرات","الذي","التي","الذين","اللاتي"
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
    private final Map<String, String[]> arabicTrigrams = new HashMap<>();
    private final Map<String, String[]> englishTrigrams = new HashMap<>();

    public SuggestionEngine(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences("prediction", Context.MODE_PRIVATE);
        loadArabicFrequencyDictionary();
        initBigrams();
    }

    private void loadArabicFrequencyDictionary() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        appContext.getAssets().open("ar_frequency.txt"),
                        StandardCharsets.UTF_8))) {
            String line;
            int rank = 0;
            Set<String> seen = new LinkedHashSet<>();
            while ((line = reader.readLine()) != null && rank < 1500) {
                String word = line.trim();
                if (word.isEmpty() || !isArabicWord(word)) continue;

                String normalized = normalize(word);
                if (normalized.isEmpty() || !seen.add(normalized)) continue;

                arabicFrequencyWords.add(word);
                arabicFrequencyRanks.put(normalized, 200000 - rank);
                rank++;
            }
        } catch (IOException ignored) {
            // The curated built-in dictionary remains available if the asset
            // cannot be loaded.
        }
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
        arabicBigrams.put("أنا", new String[]{"أريد","أحتاج","أحب"});
        arabicBigrams.put("أريد", new String[]{"أن","أن أكتب","هذا"});
        arabicBigrams.put("أحتاج", new String[]{"إلى","هذا","مساعدة"});
        arabicBigrams.put("أعرف", new String[]{"أن","هذا","كيف"});
        arabicBigrams.put("أحب", new String[]{"هذا","اللغة","الكتابة"});
        arabicBigrams.put("أنت", new String[]{"تريد","تعرف","تكتب"});
        arabicBigrams.put("أنتِ", new String[]{"تريدين","تعرفين","تكتبين"});
        arabicBigrams.put("هو", new String[]{"في","من","مع"});
        arabicBigrams.put("هي", new String[]{"في","من","مع"});
        arabicBigrams.put("نحن", new String[]{"نريد","نحتاج","نستطيع"});
        arabicBigrams.put("هذا", new String[]{"هو","جيد","ما"});
        arabicBigrams.put("هذه", new String[]{"هي","الكلمة","الصورة"});
        arabicBigrams.put("ذلك", new String[]{"هو","كان","بسبب"});
        arabicBigrams.put("في", new String[]{"البيت","العمل","الوقت"});
        arabicBigrams.put("على", new String[]{"الطريق","هذا","الكيبورد"});
        arabicBigrams.put("من", new String[]{"أجل","هذا","هنا"});
        arabicBigrams.put("إلى", new String[]{"البيت","العمل","أن"});
        arabicBigrams.put("مع", new String[]{"هذا","الناس","بعض"});
        arabicBigrams.put("كيف", new String[]{"حالك","يمكنني","أكتب"});
        arabicBigrams.put("ماذا", new String[]{"تريد","تفعل","تكتب"});
        arabicBigrams.put("لماذا", new String[]{"لا","هذا","تقول"});
        arabicBigrams.put("هل", new String[]{"يمكن","تستطيع","هذا"});
        arabicBigrams.put("شكراً", new String[]{"لك","جزيلاً"});
        arabicBigrams.put("شكرا", new String[]{"لك","جزيلاً"});
        arabicBigrams.put("مرحبا", new String[]{"بك","كيف","يا"});
        arabicBigrams.put("مرحباً", new String[]{"بك","كيف","يا"});
        arabicBigrams.put("السلام", new String[]{"عليكم","عليك"});
        arabicBigrams.put("عليكم", new String[]{"السلام","ورحمة"});
        arabicBigrams.put("الكيبورد", new String[]{"العربي","الإنجليزي","الجديد"});
        arabicBigrams.put("العربية", new String[]{"والإنجليزية","سهلة","الآن"});
        arabicBigrams.put("الإنجليزية", new String[]{"والعربية","سهلة","الآن"});
        arabicBigrams.put("الكلمة", new String[]{"التالية","الحالية","الصحيحة"});
        arabicBigrams.put("اقتراحات", new String[]{"الكلمات","جيدة","أفضل"});
        arabicBigrams.put("كلمات", new String[]{"جديدة","عربية","كثيرة"});
        arabicBigrams.put("تطبيق", new String[]{"أندرويد","جديد","لوحة"});
        arabicBigrams.put("التطبيق", new String[]{"يعمل","بشكل","الجديد"});
        arabicBigrams.put("مشكلة", new String[]{"في","هذا","التطبيق"});
        arabicBigrams.put("حل", new String[]{"هذه","المشكلة","جيد"});
        arabicBigrams.put("مساعدة", new String[]{"في","هذا","الموضوع"});
        arabicBigrams.put("اليوم", new String[]{"سوف","أريد","أنا"});
        arabicBigrams.put("الآن", new String[]{"أريد","يمكن","هو"});
        arabicBigrams.put("غداً", new String[]{"سوف","أريد","يمكن"});
        arabicBigrams.put("جيد", new String[]{"جداً","لكن","أيضاً"});
        arabicBigrams.put("ممتاز", new String[]{"جداً","شكراً","الآن"});
        arabicBigrams.put("صحيح", new String[]{"تماماً","ولكن","هذا"});
        arabicBigrams.put("إن", new String[]{"شاء","هذا","الله"});
        arabicBigrams.put("إن شاء", new String[]{"الله","الله،"});
        arabicBigrams.put("الله", new String[]{"خيراً","يحفظك","عليك"});
        arabicBigrams.put("لذلك", new String[]{"أريد","يجب","يمكن"});
        arabicBigrams.put("لأن", new String[]{"هذا","الوقت","هناك"});
        arabicBigrams.put("يمكن", new String[]{"أن","أنك","ذلك"});
        arabicBigrams.put("يجب", new String[]{"أن","عليك","عليه"});
        arabicBigrams.put("سوف", new String[]{"أذهب","أكتب","أعمل"});
        arabicBigrams.put("ليس", new String[]{"هناك","هذا","لدي"});
        arabicBigrams.put("هناك", new String[]{"شيء","مشكلة","الكثير"});
        arabicBigrams.put("هنا", new String[]{"يمكن","يوجد","الآن"});
        arabicBigrams.put("يا", new String[]{"صديقي","صديقتي","أخي"});
        arabicBigrams.put("صديقي", new String[]{"كيف","العزيز","الغالي"});
        arabicBigrams.put("صباح", new String[]{"الخير","النور"});
        arabicBigrams.put("مساء", new String[]{"الخير","النور"});
        arabicBigrams.put("صباحا", new String[]{"الخير","اليوم"});
        arabicBigrams.put("صباحاً", new String[]{"الخير","اليوم"});
        arabicBigrams.put("أهلا", new String[]{"وسهلا","بك"});
        arabicBigrams.put("أهلاً", new String[]{"وسهلاً","بك"});
        arabicBigrams.put("وسهلا", new String[]{"بك"});
        arabicBigrams.put("بك", new String[]{"كيف","جداً","اليوم"});
        arabicBigrams.put("جدا", new String[]{"جيد","ممتاز","لكن"});
        arabicBigrams.put("جداً", new String[]{"جيد","ممتاز","لكن"});
        arabicBigrams.put("بشكل", new String[]{"جيد","أفضل","صحيح"});
        arabicBigrams.put("بسبب", new String[]{"هذا","ذلك","المشكلة"});
        arabicBigrams.put("قبل", new String[]{"أن","هذا","العمل"});
        arabicBigrams.put("بعد", new String[]{"أن","ذلك","هذا"});
        arabicBigrams.put("عندما", new String[]{"تكون","يكون","أكون"});
        arabicBigrams.put("إذا", new String[]{"كان","كنت","كنتِ"});
        arabicBigrams.put("عندي", new String[]{"وقت","مشكلة","سؤال"});
        arabicBigrams.put("لدي", new String[]{"وقت","مشكلة","سؤال"});
        arabicBigrams.put("عندك", new String[]{"وقت","مشكلة","سؤال"});
        arabicBigrams.put("تريد", new String[]{"أن","هذا","شيئا"});
        arabicBigrams.put("تريدين", new String[]{"أن","هذا","شيئا"});
        arabicBigrams.put("يريد", new String[]{"أن","يكون","هذا"});
        arabicBigrams.put("نريد", new String[]{"أن","هذا","يمكن"});
        arabicBigrams.put("أستطيع", new String[]{"أن","ذلك","هذا"});
        arabicBigrams.put("تستطيع", new String[]{"أن","ذلك","هذا"});
        arabicBigrams.put("أعمل", new String[]{"على","في","الآن"});
        arabicBigrams.put("أكتب", new String[]{"هذا","رسالة","الكلمة"});
        arabicBigrams.put("أقرأ", new String[]{"هذا","الكتاب","الرسالة"});
        arabicBigrams.put("أذهب", new String[]{"إلى","البيت","العمل"});
        arabicBigrams.put("البيت", new String[]{"الآن","اليوم","معي"});
        arabicBigrams.put("العمل", new String[]{"الآن","اليوم","غداً"});
        arabicBigrams.put("الهاتف", new String[]{"الجديد","الآن","معي"});
        arabicBigrams.put("الجهاز", new String[]{"الجديد","الآن","يعمل"});
        arabicBigrams.put("الجديد", new String[]{"جداً","والأفضل","الآن"});
        arabicBigrams.put("المشكلة", new String[]{"في","هي","الآن"});
        arabicBigrams.put("المساعدة", new String[]{"في","شكراً","مهمة"});
        arabicBigrams.put("شكراً", new String[]{"لك","جزيلاً","على"});
        arabicBigrams.put("شكرا", new String[]{"لك","جزيلاً","على"});
        arabicBigrams.put("جزيلاً", new String[]{"لك","على","جداً"});
        arabicBigrams.put("على", new String[]{"الخير","الطريق","هذا"});
        arabicBigrams.put("لك", new String[]{"على","جداً","هذا"});
        arabicBigrams.put("إن", new String[]{"شاء","هذا","كان"});
        arabicBigrams.put("شاء", new String[]{"الله"});
        arabicBigrams.put("ورحمة", new String[]{"الله"});
        arabicBigrams.put("ورحمة الله", new String[]{"وبركاته"});
        arabicBigrams.put("بركاته", new String[]{"يا","لك"});


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

        // A small built-in trigram layer gives the engine more context than
        // a simple previous-word lookup, while remaining fully offline.
        arabicTrigrams.put("أنا|أريد", new String[]{"أن","أكتب","هذا"});
        arabicTrigrams.put("كيف|حالك", new String[]{"اليوم","الآن","؟"});
        arabicTrigrams.put("هذا|هو", new String[]{"جيد","أفضل","المطلوب"});
        arabicTrigrams.put("في|البيت", new String[]{"الآن","اليوم","معي"});
        arabicTrigrams.put("على|هذا", new String[]{"الكيبورد","الهاتف","الجهاز"});
        arabicTrigrams.put("شكرا|لك", new String[]{"جزيلاً","على","المساعدة"});

        englishTrigrams.put("i|want", new String[]{"to","the","a"});
        englishTrigrams.put("i|need", new String[]{"to","some","your"});
        englishTrigrams.put("how|are", new String[]{"you","things","we"});
        englishTrigrams.put("this|is", new String[]{"the","a","my"});
        englishTrigrams.put("in|the", new String[]{"next","same","app"});
        englishTrigrams.put("thank|you", new String[]{"for","very","so"});
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

    private List<String> completedWords(String text) {
        List<String> words = new ArrayList<>();
        if (text == null || text.isEmpty()) return words;

        String[] parts = text.split("[^\\p{L}\\p{Nd}]+");
        for (String part : parts) {
            if (!part.isEmpty()) words.add(part);
        }

        // If the cursor is inside a word, the last token is the current word,
        // not a completed context word.
        if (!currentWord(text).isEmpty() && !words.isEmpty()) {
            words.remove(words.size() - 1);
        }
        return words;
    }

    private String secondPreviousCompletedWord(String text) {
        List<String> words = completedWords(text);
        return words.size() >= 2 ? words.get(words.size() - 2) : "";
    }

    private String lastCompletedWord(String text) {
        if (text == null || text.isEmpty()) return "";

        // The cursor is often immediately after a space. Ignore separators
        // and return the last complete token before them.
        int end = text.length() - 1;
        while (end >= 0 && !isWordChar(text.charAt(end))) end--;
        if (end < 0) return "";

        int start = end;
        while (start >= 0 && isWordChar(text.charAt(start))) start--;

        return text.substring(start + 1, end + 1);
    }

    public List<String> suggest(String text, boolean arabic) {
        String current = currentWord(text);
        String previous = previousCompletedWord(text);
        String secondPrevious = secondPreviousCompletedWord(text);
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

            if (arabic) {
                for (String word : arabicFrequencyWords) {
                    addCandidate(candidates, partial, word);
                }
            }

            // User-learned vocabulary stays local and is filtered by language.
            for (String key : prefs.getAll().keySet()) {
                if (!key.startsWith("word.")) continue;
                String word = key.substring(5);
                if (!word.isEmpty() && matchesLanguage(word, arabic)) {
                    addCandidate(candidates, partial, word);
                }
            }

            candidates.sort((a, b) -> {
                if (a.distance != b.distance) return Integer.compare(a.distance, b.distance);
                int aExtra = Math.max(0, normalize(a.word).length() - partial.length());
                int bExtra = Math.max(0, normalize(b.word).length() - partial.length());
                if (aExtra != bExtra) return Integer.compare(aExtra, bExtra);
                return Integer.compare(b.frequency, a.frequency);
            });

            for (Candidate c : candidates) {
                result.add(c.word);
                if (result.size() >= 3) break;
            }

            // If the current word has only one/two matches, use the context
            // to fill the remaining prediction slot with the next word.
            if (result.size() < 3 && !previous.isEmpty()) {
                for (String word : nextWords(secondPrevious, previous, arabic)) {
                    result.add(word);
                    if (result.size() >= 3) break;
                }
            }
        } else {
            // No current word: show three next-word predictions.
            for (String word : nextWords(secondPrevious, lastCompletedWord(text), arabic)) {
                result.add(word);
                if (result.size() >= 3) break;
            }

            if (result.isEmpty()) {
                List<Ranked> coldStart = new ArrayList<>();
                for (String word : (arabic ? arabicWords : englishWords)) {
                    coldStart.add(new Ranked(word, Math.max(1, wordFrequency(word))));
                }
                if (arabic) {
                    for (String word : arabicFrequencyWords) {
                        coldStart.add(new Ranked(word, Math.max(1, wordFrequency(word))));
                    }
                }
                coldStart.sort(Comparator.comparingInt((Ranked r) -> r.score).reversed());
                for (Ranked r : coldStart) {
                    result.add(r.word);
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
        String secondPrevious = null;

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

            if (secondPrevious != null && previous != null) {
                String triKey = "tri." + normalizeKey(secondPrevious)
                        + ">" + normalizeKey(previous) + ">" + normalized;
                int triFrequency = prefs.getInt(triKey, 0);
                editor.putInt(triKey, Math.min(10000, triFrequency + 1));
            }

            secondPrevious = previous;
            previous = word;
        }

        editor.apply();
    }

    private List<String> nextWords(String secondPrevious, String previous, boolean arabic) {
        List<Ranked> ranked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (previous != null && !previous.isEmpty()) {
            String previousKey = normalizeKey(previous);
            String secondKey = normalizeKey(secondPrevious);

            // Personal trigrams are strongest because they model the user's
            // immediate sentence context.
            if (!secondKey.isEmpty()) {
                String prefix = "tri." + secondKey + ">" + previousKey + ">";
                for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                    if (!e.getKey().startsWith(prefix)) continue;
                    String next = e.getKey().substring(prefix.length());
                    int count = toInt(e.getValue());
                    if (!next.isEmpty()) ranked.add(new Ranked(next, 200000 + count));
                }

                Map<String, String[]> builtInTri = arabic ? arabicTrigrams : englishTrigrams;
                String[] triWords = builtInTri.get(
                        normalize(secondPrevious) + "|" + normalize(previous));
                if (triWords != null) {
                    for (int i = 0; i < triWords.length; i++) {
                        ranked.add(new Ranked(triWords[i], 12000 - i));
                    }
                }
            }

            // Personal bigrams remain useful when there is not enough trigram
            // history yet.
            String pairPrefix = "pair." + previousKey + ">";
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                if (!e.getKey().startsWith(pairPrefix)) continue;
                String next = e.getKey().substring(pairPrefix.length());
                int count = toInt(e.getValue());
                if (!next.isEmpty()) ranked.add(new Ranked(next, 100000 + count));
            }

            Map<String, String[]> builtIn = arabic ? arabicBigrams : englishBigrams;
            String[] builtInWords = builtIn.get(previous);
            if (builtInWords == null) builtInWords = builtIn.get(normalize(previous));

            if (builtInWords != null) {
                for (int i = 0; i < builtInWords.length; i++) {
                    String candidate = builtInWords[i];
                    int frequency = wordFrequency(candidate);
                    ranked.add(new Ranked(candidate, 50000 - (i * 100) + Math.min(frequency, 1000)));
                }
            }
        }

        // Learned word frequency is the fallback, similar to a user history
        // dictionary used by mature Android keyboard engines.
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!e.getKey().startsWith("word.")) continue;
            String word = e.getKey().substring(5);
            if (!word.isEmpty() && matchesLanguage(word, arabic)) {
                int learned = toInt(e.getValue());
                ranked.add(new Ranked(word, 1000000 + learned * 10000 + builtInFrequency(word)));
            }
        }

        ranked.sort(Comparator.comparingInt((Ranked r) -> r.score).reversed());

        for (Ranked r : ranked) {
            if (seen.add(r.word) && seen.size() >= 3) break;
        }

        return new ArrayList<>(seen);
    }

    private int wordFrequency(String word) {
        int learned = prefs.getInt("word." + normalizeKey(word), 0);
        return learned * 10000 + builtInFrequency(word);
    }

    private int builtInFrequency(String word) {
        int best = 0;
        String normalized = normalize(word);

        Integer arabicRank = arabicFrequencyRanks.get(normalized);
        if (arabicRank != null) best = Math.max(best, arabicRank);

        int index = arabicWords.indexOf(word);
        if (index >= 0) best = Math.max(best, 5000 - index);

        int englishIndex = englishWords.indexOf(word);
        if (englishIndex >= 0) best = Math.max(best, 5000 - englishIndex);

        return best;
    }

    private boolean matchesLanguage(String word, boolean arabic) {
        if (arabic) return isArabicWord(word);
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c) && c < 0x0600) return true;
        }
        return false;
    }

    private boolean isArabicWord(String word) {
        if (word == null || word.isEmpty()) return false;
        boolean found = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isWhitespace(c)) return false;
            if ((c >= '\u0600' && c <= '\u06FF')
                    || (c >= '\u0750' && c <= '\u077F')
                    || (c >= '\u08A0' && c <= '\u08FF')
                    || (c >= '\uFB50' && c <= '\uFDFF')
                    || (c >= '\uFE70' && c <= '\uFEFF')) {
                found = true;
            } else if (Character.isLetter(c)) {
                return false;
            }
        }
        return found;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ٱ', 'ا')
                .replace('ى', 'ي')
                .replace('ؤ', 'و')
                .replace('ئ', 'ي')
                .replace('ة', 'ه')
                .replace("ـ", "");
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
