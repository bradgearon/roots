package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import org.json.JSONObject;
import java.util.List;
import static org.junit.Assert.*;

public class PublicFootprintsTest {
    private static final String VECTOR="{\"id\":\"dfbe12e2d2664cbf6a3c217d455c72908c33bca4fdd3b2f068b6bd538cb7128c\",\"revision\":1000,\"links\":[\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"],\"key\":\"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ4DF/HAnXixwYaDnh3uxdN6t65iHAn8/qDZUFYun9Qw8uow0vDXSDoH3MKwce9bWYalC+QxqnKVcUS+eSgASZg==\",\"sig\":\"MEQCIANwWze2MZ4bdv/5RI0fXVsbxlM5iClxvbxg/HNvcQUVAiBrsjbyN07soEGCkZsZuMdmVIEZ/iS55aod+mawpx6aFg==\"}";
    @Test public void verifiesIndependentP256VectorAndRejectsTampering()throws Exception{
        var card=PublicFootprints.verify(VECTOR,1001);
        assertEquals("dfbe12e2d2664cbf6a3c217d455c72908c33bca4fdd3b2f068b6bd538cb7128c",card.id());
        assertEquals(List.of("a".repeat(64),"b".repeat(64)),card.links());
        assertThrows(Exception.class,()->PublicFootprints.verify(new JSONObject(VECTOR).put("revision",1002).toString(),1003));
        assertThrows(Exception.class,()->PublicFootprints.verify(new JSONObject(VECTOR).put("id","c".repeat(64)).toString(),1001));
        assertThrows(Exception.class,()->PublicFootprints.verify(new JSONObject(VECTOR).put("sig","AAAA").toString(),1001));
    }
    @Test public void signsSortedBoundedLinksAndRejectsFutureClaims()throws Exception{
        var key=EncounterChannel.newEcKey();
        String own=EncounterChannel.identityId(key.getPublic());
        var card=PublicFootprints.sign(key,List.of("b".repeat(64),own,"a".repeat(64),"b".repeat(64)),400000);
        assertEquals(List.of("a".repeat(64),"b".repeat(64)),card.links());
        assertEquals(own,PublicFootprints.verify(card.wire(),400000).id());
        assertThrows(Exception.class,()->PublicFootprints.verify(card.wire(),1000));
    }
}
