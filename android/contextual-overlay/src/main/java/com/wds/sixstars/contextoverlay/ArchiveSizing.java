package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.content.SharedPreferences;

/** Receiver-owned profile: actual end-to-end record time includes remote
 * compression, network transfer, local decompression and durable retention.
 * Separate profiles by peer and transport survive a reconnect/restart. */
final class ArchiveSizing {
    static final int INITIAL=4096,MIN=64,MAX=16384;
    record Budget(int records,int rawBytes,int wireBytes,int estimatedMs){}
    private final SharedPreferences prefs;
    private final String key;
    private final int rawLimit,wireLimit,targetMs;
    private double millisPerRecord,rawPerRecord,wirePerRecord;
    private int previous;
    ArchiveSizing(Context context,String peer,boolean wifi){
        prefs=context.getSharedPreferences("roots_archive_profiles_v1",Context.MODE_PRIVATE);
        key=peer+(wifi?":wifi":":ble");
        long heap=Runtime.getRuntime().maxMemory();
        rawLimit=(int)Math.max(65536,Math.min(wifi?KnowledgeChunk.MAX_RAW:4*1024*1024,heap/12));
        wireLimit=Math.max(65536,Math.min(wifi?KnowledgeChunk.MAX_WIRE:512*1024,rawLimit));
        targetMs=wifi?8000:6000;
        previous=Math.max(MIN,Math.min(MAX,prefs.getInt(key+":next",INITIAL)));
        millisPerRecord=valid(prefs.getFloat(key+":ms",0));
        rawPerRecord=valid(prefs.getFloat(key+":raw",0));
        wirePerRecord=valid(prefs.getFloat(key+":wire",0));
    }
    private static double valid(double n){return Double.isFinite(n)&&n>0?n:0;}
    static int choose(int previous,int targetMs,int rawLimit,int wireLimit,double ms,double raw,double wire){
        if(ms<=0||raw<=0||wire<=0)return INITIAL;
        double ideal=Math.min(targetMs/ms,Math.min(rawLimit/raw,wireLimit/wire));
        // Limit growth between observed runs, but allow fast reduction on
        // slower devices. Byte ceilings remain hard limits at the sender.
        return Math.max(MIN,Math.min(MAX,(int)Math.min(ideal,Math.max(MIN,previous)*2.0)));
    }
    Budget budget(){
        int n=choose(previous,targetMs,rawLimit,wireLimit,millisPerRecord,rawPerRecord,wirePerRecord);
        int estimate=millisPerRecord>0?(int)Math.min(Integer.MAX_VALUE,Math.ceil(n*millisPerRecord)):-1;
        return new Budget(n,rawLimit,wireLimit,estimate);
    }
    private static double blend(double old,double next){return old<=0?next:old*0.65+next*0.35;}
    void observe(int count,int raw,int wire,long elapsedMs){
        if(count<1||raw<1||wire<1)return;
        millisPerRecord=blend(millisPerRecord,(double)Math.max(1,elapsedMs)/count);
        rawPerRecord=blend(rawPerRecord,(double)raw/count);
        wirePerRecord=blend(wirePerRecord,(double)wire/count);
        previous=budget().records();
        prefs.edit().putInt(key+":next",previous).putFloat(key+":ms",(float)millisPerRecord)
            .putFloat(key+":raw",(float)rawPerRecord).putFloat(key+":wire",(float)wirePerRecord).apply();
    }
}
