package com.wds.sixstars.contextoverlay;

import java.util.Locale;
import java.util.regex.Pattern;

/** Independent language evidence; a foreign hashtag, name or one letter
 * does not determine the language of the whole post. */
final class PostLanguage {
    private static final Pattern NOISE=Pattern.compile("(?i)https?://\\S+|www\\.\\S+|(?<![\\p{L}\\p{N}_])[@#][\\p{L}\\p{N}_]+");
    private PostLanguage(){}
    static String clean(String text){
        if(text==null)return "";
        return NOISE.matcher(text).replaceAll(" ").replaceFirst("(?i)(?:…|\\.\\.\\.)?\\s*show more\\s*$","").trim();
    }
    static String code(String value){
        if(value==null)return "";
        String code=value.trim().toLowerCase(Locale.ROOT).replace('_','-').split("-",2)[0];
        return code.matches("[a-z]{2,3}")&&!code.equals("und")?code:"";
    }
    static int letters(String text){return (int)clean(text).codePoints().filter(Character::isLetter).count();}
    /** Majority of meaningful letters, not the presence of one character. */
    static String script(String text,String reported){
        int letters=0,kana=0,han=0,hangul=0,arabic=0,cyrillic=0,hebrew=0,devanagari=0,thai=0;
        for(int cp:clean(text).codePoints().toArray()){
            if(!Character.isLetter(cp))continue;
            letters++;
            switch(Character.UnicodeScript.of(cp)){
                case HIRAGANA:case KATAKANA:kana++;break;
                case HAN:han++;break;
                case HANGUL:hangul++;break;
                case ARABIC:arabic++;break;
                case CYRILLIC:cyrillic++;break;
                case HEBREW:hebrew++;break;
                case DEVANAGARI:devanagari++;break;
                case THAI:thai++;break;
                default:break;
            }
        }
        if(letters<2)return "";
        int majority=(int)Math.ceil(letters*.60);
        String hint=code(reported);
        if(kana>0&&kana+han>=majority)return "ja";
        if(hangul>0&&hangul+han>=majority)return "ko";
        // Han alone is not evidence that distinguishes Japanese and Chinese.
        if(han>=majority)return hint.equals("ja")?"ja":hint.equals("zh")?"zh":"";
        if(arabic>=majority)return hint.equals("fa")||hint.equals("ur")?hint:"ar";
        if(cyrillic>=majority)return hint.equals("uk")||hint.equals("bg")||hint.equals("sr")?hint:"ru";
        if(hebrew>=majority)return "he";
        if(devanagari>=majority)return hint.equals("mr")||hint.equals("ne")?hint:"hi";
        if(thai>=majority)return "th";
        return "";
    }
    static String hint(String text,String reported){
        String clean=clean(text),reportedCode=code(reported);
        String script=script(clean,reportedCode);
        if(!script.isEmpty())return script;
        // Evaluate English lexical evidence on the dominant Latin portion.
        int latin=0,total=0;
        StringBuilder latinText=new StringBuilder();
        for(int cp:clean.codePoints().toArray()){
            if(Character.isLetter(cp)){
                total++;
                if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.LATIN){latin++;latinText.appendCodePoint(cp);}
                else latinText.append(' ');
            }else latinText.appendCodePoint(cp);
        }
        if(total>0&&latin>=Math.ceil(total*.80)&&"en".equals(KnowledgeLanguage.englishHint(latinText.toString())))return "en";
        if(!reportedCode.isEmpty())return reportedCode;
        String words=" "+clean.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+"," ")+" ";
        if(contains(words," bonjour "," merci "," pourquoi "," aujourd hui "," je suis "," avec vous "))return "fr";
        if(contains(words," hola "," gracias "," buenos dias "," buenas noches "," estoy "," nosotros "))return "es";
        if(contains(words," hallo "," danke "," bitte "," guten morgen "," ich bin "))return "de";
        return "";
    }
    static String resolve(String text,String reported,String detected,double confidence,boolean reliable){
        String local=hint(text,reported);
        String model=code(detected);
        if(!reliable||confidence<.70||model.isEmpty())return local;
        String script=script(text,reported);
        // Use the detector to disambiguate languages sharing a script;
        // do not mistake a small Latin/foreign fragment for the dominant script.
        if(!script.isEmpty()&&!script.equals(model)){
            if((script.equals("ja")||script.equals("ko")||script.equals("zh"))&&
               (model.equals("ja")||model.equals("ko")||model.equals("zh")))return model;
            if(script.equals("ar")&&(model.equals("fa")||model.equals("ur")))return model;
            if(script.equals("ru")&&(model.equals("uk")||model.equals("bg")||model.equals("sr")))return model;
            return script;
        }
        // A reliable classifier can correct a weak hint; short conflicting
        // labels need stronger confidence before discarding the original hint.
        if(!local.isEmpty()&&!local.equals(model)&&letters(text)<12&&confidence<.90)return local;
        return model;
    }
    private static boolean contains(String text,String... words){for(String word:words)if(text.contains(word))return true;return false;}
}
