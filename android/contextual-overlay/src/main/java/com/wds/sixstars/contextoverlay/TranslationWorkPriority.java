package com.wds.sixstars.contextoverlay;

/** Pure scheduling policy; position is only meaningful for current visibility. */
final class TranslationWorkPriority {
    static long score(boolean visible,int top,long ageMs){
        // Base 100, immediate boost 70..100, aging up to 120 points.
        long age=Math.min(120,Math.max(0,ageMs)/1000);
        long reading=visible?100-Math.min(30,Math.max(0,top)/100):0;
        return 100+reading+age;
    }
}
