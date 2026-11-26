package com.wds.sixstars.contextoverlay;

/** Deterministic demand ranking. Larger scores are more useful at completion. */
final class TranslationWorkPriority {
    private static final long BACKGROUND_MAX_AGE_MS=15_000L;

    static long score(boolean visible,boolean author,int top,int bottom,int height,
                      double offset,double velocity,long predictedMs,long ageMs,long sinceSeenMs) {
        height=Math.max(1,height);
        long age=Math.min(30,Math.max(0,ageMs)/500);
        boolean moving=Math.abs(velocity)>=0.08;
        // Previously seen content is a low-priority rollup, never allowed to
        // displace current demand. Don't chase old content during a gesture.
        if(!visible){
            return moving||sinceSeenMs>BACKGROUND_MAX_AGE_MS?0:100+(author?0:60)+age;
        }
        double currentTop=top-offset,currentBottom=bottom-offset;
        if(currentBottom<=0||currentTop>=height)return 0;
        double horizon=Math.max(60,Math.min(1500,predictedMs));
        // Look farther ahead for a slower backend or a longer request.
        double shift=Math.max(-1.5*height,Math.min(1.5*height,velocity*horizon));
        double futureTop=currentTop-shift,futureBottom=currentBottom-shift;
        // During a fast fling defer jobs likely to leave before they finish.
        if(moving&&(futureBottom<=0||futureTop>=height))return 0;
        double focus=height*0.38;
        double distance=focus<futureTop?futureTop-focus:focus>futureBottom?focus-futureBottom:0;
        long relevance=1000-Math.min(1000,Math.round(1000*distance/height));
        // Body always outranks a viable name. Age only breaks close ties.
        // Stable top-to-bottom order at rest, projected reading region in motion.
        long reading=moving?relevance:1000-Math.min(1000,Math.round(1000*Math.max(0,currentTop)/height));
        return (author?2000:4000)+reading+age;
    }
}
