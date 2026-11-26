package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Local routing familiarity only: NOT authentication. No SSID or GPS.
 * IPv4 gateway/subnet can collide on unrelated LANs; encrypted host identity
 * is always checked independently by the real request/response. */
final class FamiliarComputeNetworks {
    private final ConnectivityManager cm;
    private final SharedPreferences prefs;
    FamiliarComputeNetworks(Context context){
        cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        prefs=context.getSharedPreferences("roots_compute_familiarity",Context.MODE_PRIVATE);
    }
    String fingerprint(Network network){
        if(cm==null||network==null)return "";
        LinkProperties link=cm.getLinkProperties(network);
        if(link==null)return "";
        ArrayList<String> parts=new ArrayList<>();
        for(RouteInfo route:link.getRoutes())
            if(route.isDefaultRoute()&&route.getGateway() instanceof Inet4Address)
                parts.add("gateway:"+route.getGateway().getHostAddress());
        if(parts.isEmpty())return "";
        for(LinkAddress address:link.getLinkAddresses()){
            if(!(address.getAddress() instanceof Inet4Address))continue;
            byte[] b=address.getAddress().getAddress();
            int prefix=address.getPrefixLength();
            for(int i=0;i<4;i++){
                int bits=Math.max(0,Math.min(8,prefix-i*8));
                b[i]=(byte)(b[i]& (bits==0?0:0xff<<(8-bits)));
            }
            parts.add("subnet:"+(b[0]&255)+"."+(b[1]&255)+"."+(b[2]&255)+"."+(b[3]&255)+"/"+prefix);
        }
        Collections.sort(parts);
        return TextFingerprint.of(String.join("|",parts));
    }
    private String key(String base,String fp){return TextFingerprint.of(base+"|"+fp);}
    synchronized boolean contains(String base,String fp){
        return !fp.isEmpty()&&prefs.getStringSet("seen",Collections.emptySet()).contains(key(base,fp));
    }
    synchronized boolean remember(String base,String fp){
        if(fp.isEmpty())return false;
        Set<String> seen=new HashSet<>(prefs.getStringSet("seen",Collections.emptySet()));
        if(seen.size()>=64&&!seen.contains(key(base,fp)))seen.clear();
        seen.add(key(base,fp));prefs.edit().putStringSet("seen",seen).apply();return true;
    }
    synchronized void clear(){prefs.edit().remove("seen").apply();}
}
