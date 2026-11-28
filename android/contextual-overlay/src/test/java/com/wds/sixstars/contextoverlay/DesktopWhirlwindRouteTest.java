package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopWhirlwindRouteTest {
    private static final String ID="0123456789abcdef".repeat(4);
    @Test public void acceptsPrivateRelayRoute(){
        String url="roots+iroh://"+ID+"?relay=https://relay.example/";
        var route=DesktopWhirlwind.route(url);
        assertEquals(ID,route.endpoint());assertEquals("https://relay.example/",route.relay());
        assertEquals("",route.fallback());assertEquals(url,route.canonical());
        assertEquals(url,DesktopIntelligenceProtocol.baseUrl(url));
    }
    @Test public void acceptsIndependentPinnedFallback(){
        String addr="/dns4/relay.example/tcp/4001/p2p/12D3KooWExample/p2p-circuit/p2p/12D3KooWHost";
        var route=DesktopWhirlwind.route("roots+iroh://"+ID+"?relay=https://relay.example/&fallback="+addr);
        assertEquals(addr,route.fallback());
    }
    @Test public void rejectsUnknownOrUnpinnedRoutes(){
        for(String suffix:new String[]{"","?relay=http://relay.example","?relay=https://user@relay.example","?relay=https://relay.example&token=secret","?fallback=/ip4/127.0.0.1/tcp/8221","?relay=https://relay.example&relay=https://other.example"}){
            try{DesktopWhirlwind.route("roots+iroh://"+ID+suffix);fail("Accepted invalid private route");}
            catch(IllegalArgumentException expected){}
        }
    }
}
