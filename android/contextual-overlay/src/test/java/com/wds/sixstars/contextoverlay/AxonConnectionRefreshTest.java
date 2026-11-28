package com.wds.sixstars.contextoverlay;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AxonConnectionRefreshTest {
    @Test public void refreshesOnlyPreviouslyArmedIdentities(){
        MeshPeer.Invite armed=new MeshPeer.Invite("192.168.1.2","a".repeat(64),"1".repeat(64));
        MeshPeer.Invite updated=new MeshPeer.Invite("192.168.1.4",armed.pin(),"2".repeat(64));
        MeshPeer.Invite notArmed=new MeshPeer.Invite("192.168.1.3","b".repeat(64),"3".repeat(64));
        assertEquals(List.of(updated),AxonRuntime.refreshArmedPeers(List.of(armed),List.of(updated,notArmed)));
        assertTrue(AxonRuntime.refreshArmedPeers(List.of(armed),List.of(notArmed)).isEmpty());
    }
}
