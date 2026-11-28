package com.wds.sixstars.contextoverlay;

/** Uptime-only arbitration between asynchronous scroll events and measured
 * accessibility snapshots. Scroll deltas never become painted positions. */
final class XSceneGeometryClock {
    static final long MAX_UNVERIFIED_MS=240L;
    private long measuredAt=-1,pendingEvent=-1,pendingSince=-1;
    private boolean expired;

    boolean covers(long eventTime){
        return eventTime>0&&measuredAt>=0&&eventTime<=measuredAt;
    }
    boolean motion(long eventTime,long now){
        long event=eventTime>0?Math.min(eventTime,now):now;
        if(covers(event)||event<=pendingEvent)return false;
        pendingEvent=event;
        if(pendingSince<0){pendingSince=now;expired=false;}
        return true;
    }
    long deadline(){return pendingSince<0?Long.MAX_VALUE:pendingSince+MAX_UNVERIFIED_MS;}
    boolean expired(long now){
        if(pendingSince<0||expired||now<deadline())return false;
        expired=true;return true;
    }
    void snapshot(long started){
        measuredAt=Math.max(measuredAt,started);
        if(pendingEvent<=measuredAt){
            pendingEvent=pendingSince=-1;expired=false;
        }
    }
    void reset(){measuredAt=pendingEvent=pendingSince=-1;expired=false;}
}
