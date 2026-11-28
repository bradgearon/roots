package com.wds.sixstars.contextoverlay;

/** Platform-independent discovery ordering and energy backoff. */
final class EncounterArbitration {
    private EncounterArbitration(){}
    static int compareRank(byte[] a,byte[] b){
        if(a==null||b==null||a.length!=8||b.length!=8)throw new IllegalArgumentException("Expected eight-byte radio rank");
        for(int i=0;i<8;i++){int d=(a[i]&255)-(b[i]&255);if(d!=0)return d;}
        return 0;
    }
    static final int MORE_PENDING=-3;
    static long cooldownMs(int result){
        if(result==MORE_PENDING)return 1500;
        if(result==-2)return 600000;
        if(result==0)return 120000;
        return 30000;
    }
}
