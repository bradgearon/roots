package com.wds.sixstars.contextoverlay;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.languageid.IdentifiedLanguage;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Real on-device language classification, independent of PLaMo and screen geometry.
 * Invoked on the compute routing worker, never on the accessibility/UI thread. */
final class OnDeviceLanguageDetector {
    private static final int MAX_CACHE=1024;
    private static final long TTL=30*60*1000L;
    private LanguageIdentifier classifier;
    private record Cached(PostLanguage.Detection result,long at){}
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(32,.75f,true);
    PostLanguage.Detection detect(String text)throws Exception{
        String input=PostLanguage.clean(text);
        if(input.isBlank())return PostLanguage.Detection.unknown();
        String key=TextFingerprint.of(input);
        long now=android.os.SystemClock.elapsedRealtime();
        Cached old=cache.get(key);
        if(old!=null&&now-old.at()<TTL)return old.result();
        if(classifier==null)classifier=LanguageIdentification.getClient(
            new LanguageIdentificationOptions.Builder().setConfidenceThreshold(.01f).build());
        List<IdentifiedLanguage> predictions=Tasks.await(classifier.identifyPossibleLanguages(input),2500,TimeUnit.MILLISECONDS);
        String best="";double first=0,second=0;
        for(IdentifiedLanguage prediction:predictions){
            String language=PostLanguage.code(prediction.getLanguageTag());
            double confidence=prediction.getConfidence();
            if(language.isEmpty()||!Double.isFinite(confidence))continue;
            if(confidence>first){second=first;first=confidence;best=language;}
            else if(!language.equals(best)&&confidence>second)second=confidence;
        }
        PostLanguage.Detection result=new PostLanguage.Detection(best,first,Math.max(0,first-second),true);
        cache.put(key,new Cached(result,now));
        while(cache.size()>MAX_CACHE)cache.remove(cache.keySet().iterator().next());
        return result;
    }
}
