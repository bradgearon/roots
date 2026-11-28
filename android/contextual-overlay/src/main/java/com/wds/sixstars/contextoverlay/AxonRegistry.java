package com.wds.sixstars.contextoverlay;

import android.content.Context;
import java.util.*;

/** Unified local subscription view. External BLE and private LAN grants
 * remain distinct security domains; no BLE key becomes a TLS invitation. */
final class AxonRegistry {
    private AxonRegistry(){}
    record Entry(String id,String label,String transport,String topics,boolean blocked,boolean connected,long received){}
    record Snapshot(List<Entry> entries,boolean privateUnavailable){
        int count(){int n=0;for(Entry entry:entries)if(!entry.blocked())n++;return n;}
        int nearbyCount(){int n=0;for(Entry entry:entries)if(!entry.blocked()&&entry.transport().equals("Bluetooth"))n++;return n;}
        int privateCount(){int n=0;for(Entry entry:entries)if(!entry.blocked()&&entry.transport().equals("Private LAN"))n++;return n;}
        String summary(){return count()+" Axon subscription"+(count()==1?"":"s")+" · "+nearbyCount()+" nearby · "+privateCount()+" private LAN"+(privateUnavailable?" · LAN policy unavailable":"");}
    }
    static Snapshot load(Context context){
        List<Entry> entries=new ArrayList<>();
        for(var peer:EncounterBindings.get(context).subscriptions()){
            String id=peer.binding().peer();
            String label=peer.label().isEmpty()?"Axon "+id.substring(0,12):peer.label();
            entries.add(new Entry(id,label,"Bluetooth","*",peer.blocked(),id.equals(EncounterService.activePeer())&&!peer.blocked(),peer.received()));
        }
        boolean unavailable=false;
        try{
            AxonSubscription lan=AxonSubscription.load(context);
            if(lan!=null){
                List<String> topics=new ArrayList<>();for(var lane:lan.lanes)topics.add(lane.topic());
                List<String> pins=new ArrayList<>(lan.pins);Collections.sort(pins);
                for(String pin:pins)entries.add(new Entry(pin,"Axon "+pin.substring(0,12),"Private LAN",String.join(", ",topics),false,false,0));
            }
        }catch(Exception|LinkageError failure){unavailable=true;}
        return new Snapshot(Collections.unmodifiableList(entries),unavailable);
    }
    static String display(Entry entry){
        return entry.label()+" · "+(entry.transport().equals("Bluetooth")?"Nearby":entry.transport())+" · "+(entry.blocked()?"blocked":entry.connected()?"connected":entry.transport().equals("Bluetooth")?"bound":"saved")+
            " · "+(entry.topics().equals("*")?"all knowledge":entry.topics())+(entry.transport().equals("Bluetooth")?" · "+entry.received()+" received":"");
    }
}
