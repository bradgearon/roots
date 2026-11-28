package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
public class AxonRegistryTest {
    @Test public void nearbyBindingCountsWithoutLanPolicy(){
        var nearby=new AxonRegistry.Entry("peer","Nearby","Bluetooth","*",false,false,0);
        var snapshot=new AxonRegistry.Snapshot(Collections.singletonList(nearby),false);
        assertEquals(1,snapshot.count());assertEquals(1,snapshot.nearbyCount());assertEquals(0,snapshot.privateCount());
        assertTrue(snapshot.summary().startsWith("1 Axon subscription"));
        assertTrue(AxonRegistry.display(nearby).contains("all knowledge"));
    }
    @Test public void combinedRegistryExcludesBlockedButRetainsThem(){
        var nearby=new AxonRegistry.Entry("a","Nearby","Bluetooth","*",false,true,5);
        var blocked=new AxonRegistry.Entry("b","Blocked","Bluetooth","*",true,false,0);
        var lan=new AxonRegistry.Entry("c","LAN","Private LAN","music",false,false,0);
        var snapshot=new AxonRegistry.Snapshot(Arrays.asList(nearby,blocked,lan),false);
        assertEquals(2,snapshot.count());assertEquals(1,snapshot.nearbyCount());assertEquals(1,snapshot.privateCount());
        assertEquals(3,snapshot.entries().size());
    }
}
