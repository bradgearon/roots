package com.wds.sixstars.contextoverlay;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** The model determines language. An explicit source attribute is a hint,
 * never a replacement for a classifier result. Normalize stylized letters
 * for the detector only; preserve the original display and stored text. */
final class PostLanguage {
    private static final Pattern NOISE=Pattern.compile("(?i)https?://\\S+|www\\.\\S+|(?<![\\p{L}\\p{N}_])[@#][\\p{L}\\p{N}_]+");
    private PostLanguage(){}
    static String clean(String text){
        if(text==null)return "";
        return NOISE.matcher(Normalizer.normalize(text,Normalizer.Form.NFKC)).replaceAll(" ")
            .replaceFirst("(?i)(?:…|\\.\\.\\.)?\\s*show more\\s*$","").trim();
    }
    static String code(String value){
        if(value==null)return "";
        String code=value.trim().toLowerCase(Locale.ROOT).replace('_','-').split("-",2)[0];
        return code.matches("[a-z]{2,3}")&&!code.equals("und")?code:"";
    }
    static int letters(String text){return (int)clean(text).codePoints().filter(Character::isLetter).count();}
    static String hint(String text,String reported){return code(reported);}
    record Detection(String language,double confidence,double margin,boolean available){
        Detection {language=code(language);}
        static Detection unknown(){return new Detection("",0,0,false);}
        String accepted(boolean name){
            return available&&!language.isEmpty()&&Double.isFinite(confidence)&&Double.isFinite(margin)&&
                confidence>=.70&&margin>=.15?language:"";
        }
    }
    static String resolve(String text,String reported,String detected,double confidence,boolean reliable){
        return reliable&&confidence>=.70?code(detected):"";
    }
}
