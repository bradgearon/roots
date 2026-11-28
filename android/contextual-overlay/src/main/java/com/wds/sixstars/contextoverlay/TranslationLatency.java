package com.wds.sixstars.contextoverlay;

import java.util.HashMap;
import java.util.Map;

/** In-memory, per transport/thread profile and input-length bucket latency EWMA. */
final class TranslationLatency {
    private static final class Estimate {
        double ms; int samples;
        Estimate(double seed){ms=seed;}
    }
    private final Map<String,Estimate> estimates=new HashMap<>();
    private static int bucket(int chars){return chars<=40?0:chars<=160?1:chars<=400?2:3;}
    private static double seed(String profile,int bucket){
        double base=profile.startsWith("desktop")?1000:profile.startsWith("vulkan")?650:260;
        return base*(1.0+bucket*0.70);
    }
    long predict(String profile,int chars){
        int b=bucket(chars);
        Estimate e=estimates.get(profile+":"+b);
        return Math.round(e==null?seed(profile,b):e.ms);
    }
    void observe(String profile,int chars,long elapsedMs){
        int b=bucket(chars);
        String key=profile+":"+b;
        if(estimates.size()>=96&&!estimates.containsKey(key))estimates.clear();
        Estimate e=estimates.get(key);
        if(e==null){e=new Estimate(seed(profile,b));estimates.put(key,e);}
        // End-to-end time includes queue/model preparation. Clamp outliers so
        // one cold load or remote stall doesn't make every future job urgent.
        double sample=Math.max(40,Math.min(4000,elapsedMs));
        double alpha=e.samples==0?0.35:0.25;
        e.ms=(1-alpha)*e.ms+alpha*sample;
        e.samples++;
    }
}
