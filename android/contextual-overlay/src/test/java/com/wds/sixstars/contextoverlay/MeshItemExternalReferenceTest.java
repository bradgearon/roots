package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeshItemExternalReferenceTest {
    @Test public void receiptRetainsIdentityBodySourceAndUntrustedProvenance()throws Exception{
        MeshItem item=MeshItem.create("en","Public X post","Test public knowledge","https://x.com/test/status/123");
        String peer="a".repeat(64);
        var rows=item.receivedRows(peer,1234);
        assertEquals(6,rows.size());
        for(var row:rows)assertEquals(item.key(),row.externalId());
        assertTrue(rows.stream().anyMatch(r->r.source().equals("record.mesh.body")&&r.body().equals(item.text())));
        assertTrue(rows.stream().anyMatch(r->r.source().equals("record.mesh.link")&&r.body().equals(item.link())));
        assertTrue(rows.stream().anyMatch(r->r.source().equals("record.mesh.external-peer")&&r.body().equals(peer)));
        assertTrue(rows.stream().anyMatch(r->r.source().equals("record.mesh.trust")&&r.body().contains("unverified")));
    }
    @Test public void invalidPeerRejected()throws Exception{
        MeshItem item=MeshItem.create("en","Public","Text","");
        try{item.receivedRows("unknown",0);fail("Expected peer validation");}catch(IllegalArgumentException expected){}
    }
}
