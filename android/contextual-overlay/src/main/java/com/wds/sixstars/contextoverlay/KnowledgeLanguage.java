package com.wds.sixstars.contextoverlay;

import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/** Conservative English hint for grouped knowledge. Ambiguous short text remains unknown. */
public final class KnowledgeLanguage {
    private KnowledgeLanguage(){}
    private static final Set<String> ENGLISH=new HashSet<>(Arrays.asList(
        "the","this","that","these","those","with","without","which","what","where","when","why","how",
        "would","could","should","their","there","they","them","your","you","our","we","have","has","had",
        "been","being","was","were","are","is","will","from","about","because","through","into","than","then",
        "not","and","but","for","of","to","in","on","it","its","can","does","did","do","if"));
    private static final Set<String> STRONG=new HashSet<>(Arrays.asList(
        "the","this","that","these","those","with","without","which","what","where","when","why","how",
        "would","could","should","their","there","they","them","your","you","have","been","being","was",
        "were","are","will","from","about","because","through","into","than","then","does"));
    private static final Set<String> FOREIGN=new HashSet<>(Arrays.asList(
        "bonjour","merci","avec","pour","dans","vous","nous","cette","sont","mais","parce","hola","gracias",
        "para","pero","porque","estoy","nosotros","usted","ustedes","como","cuando","donde","hallo","danke",
        "nicht","und","oder","haben","eine","einen","einem","dies","sind","werden","auch","ich","mit","auf",
        "obrigado","obrigada","você","vocês","não","uma","isso","ciao","grazie","sono","della","questa",
        "och","inte","detta","jag","ikke","zijn","het","een","niet","voor"));
    public static String englishHint(String text){
        if(text==null||text.isEmpty())return "";
        String clean=text.replaceAll("(?i)https?://\\S+|[@#][\\p{L}\\p{N}_]+"," ");
        if(clean.codePoints().anyMatch(c->Character.isLetter(c)&&Character.UnicodeScript.of(c)!=Character.UnicodeScript.LATIN))return "";
        String[] words=clean.toLowerCase(Locale.ROOT).split("[^\\p{L}]+");
        Set<String> common=new HashSet<>(),strong=new HashSet<>();int count=0;
        for(String word:words){
            if(word.isEmpty())continue;
            count++;
            if(FOREIGN.contains(word))return "";
            if(ENGLISH.contains(word))common.add(word);
            if(STRONG.contains(word))strong.add(word);
        }
        return count>=4&&common.size()>=2&&strong.size()>=1&&(double)common.size()/count>=0.12?"en":"";
    }
}
