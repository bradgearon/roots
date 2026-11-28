package com.wds.sixstars.contextoverlay;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** PLaMo 3 language codes. The picker offers 43 widely spoken supported
 * variants; source inference can use all 53 published variants.
 * https://huggingface.co/pfnet/plamo-3-translate-0.8b#supported-languages
 * Chinese scripts are distinct model codes, not interchangeable base tags.
 */
public final class TranslationLanguages {
    private TranslationLanguages() {}
    private static final String[][] TARGETS = {
        {"ar", "Arabic"},
        {"az", "Azerbaijani"},
        {"bn", "Bengali"},
        {"bg", "Bulgarian"},
        {"my", "Burmese"},
        {"ca", "Catalan"},
        {"ceb", "Cebuano"},
        {"zh-hans", "Chinese (Simplified)"},
        {"zh-hant", "Chinese (Traditional)"},
        {"nl", "Dutch"},
        {"en", "English"},
        {"fr", "French"},
        {"de", "German"},
        {"gu", "Gujarati"},
        {"he", "Hebrew"},
        {"hi", "Hindi"},
        {"hu", "Hungarian"},
        {"id", "Indonesian"},
        {"it", "Italian"},
        {"ja", "Japanese"},
        {"jv", "Javanese"},
        {"kk", "Kazakh"},
        {"km", "Khmer"},
        {"ko", "Korean"},
        {"ms", "Malay"},
        {"ml", "Malayalam"},
        {"mr", "Marathi"},
        {"ne", "Nepali"},
        {"fa", "Persian"},
        {"pl", "Polish"},
        {"pa", "Punjabi"},
        {"ru", "Russian"},
        {"sr", "Serbian"},
        {"si", "Sinhala"},
        {"es", "Spanish"},
        {"sw", "Swahili"},
        {"tl", "Tagalog"},
        {"ta", "Tamil"},
        {"te", "Telugu"},
        {"th", "Thai"},
        {"ur", "Urdu"},
        {"uz", "Uzbek"},
        {"vi", "Vietnamese"},
    };
    private static final Set<String> MODEL_CODES = new HashSet<>(Arrays.asList(
        "ja", "en", "zh-hans", "zh-hant", "ko", "ar", "az", "be", "bg",
        "bn", "ca", "ceb", "de", "es", "et", "eu", "fa", "fr",
        "gu", "he", "hi", "hr", "hu", "hy", "id", "it", "jv",
        "ka", "kk", "km", "lt", "lv", "ml", "mr", "ms", "my",
        "ne", "nl", "pa", "pl", "ru", "si", "sk", "sl", "sr",
        "sw", "ta", "te", "th", "tl", "ur", "uz", "vi"
    ));

    public static String[] targetCodes() {
        String[] result = new String[TARGETS.length];
        for (int i = 0; i < TARGETS.length; i++) result[i] = TARGETS[i][0];
        return result;
    }

    public static String[] targetLabels() {
        String[] result = new String[TARGETS.length];
        for (int i = 0; i < TARGETS.length; i++) result[i] = TARGETS[i][1];
        return result;
    }

    public static boolean isTarget(String code) {
        for (String[] entry : TARGETS) if (entry[0].equals(code)) return true;
        return false;
    }

    public static boolean isModelLanguage(String code) {
        return code != null && MODEL_CODES.contains(code);
    }

    /** Detector tags are broader than PLaMo's supported source vocabulary. */
    static String detectedCode(String value) {
        if (value == null) return "";
        String tag = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (tag.equals("zh") || tag.startsWith("zh-")) {
            for (String part : tag.split("-")) {
                if (part.equals("hans")) return "zh-hans";
                if (part.equals("hant")) return "zh-hant";
            }
            for (String part : tag.split("-")) {
                if (part.equals("cn") || part.equals("sg")) return "zh-hans";
                if (part.equals("tw") || part.equals("hk") || part.equals("mo")) return "zh-hant";
            }
            return "zh"; // script unspecified; do not invent a source script
        }
        String base = tag.split("-", 2)[0];
        if (base.equals("iw")) return "he";
        if (base.equals("in")) return "id";
        if (base.equals("fil")) return "tl";
        return base.matches("[a-z]{2,3}") && !base.equals("und") ? base : "";
    }

    /** Omit generic Chinese or unsupported hints so the model can infer. */
    public static String sourceCode(String value) {
        String code = detectedCode(value);
        return isModelLanguage(code) ? code : "";
    }
}
