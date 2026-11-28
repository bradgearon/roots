package com.wds.sixstars.contextoverlay;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public class RootGraphExchangeTest {
    private static MeshItem fragment(String source,String key,String body,String lang)throws Exception{
        return MeshItem.fromFragment(new NativeTree.Fragment(1,source,key,body,lang,1000));
    }
    @Test public void fullRootDoesNotRequireLinks()throws Exception{
        MeshItem post=fragment("record.x.body","local:x:sample","Unlinked original","en");
        MeshItem raw=fragment("x.android.visible.text","","Unlinked raw observation","en");
        assertTrue(RootGraphExchange.localOffer(post));assertTrue(RootGraphExchange.localOffer(raw));
        assertTrue(RootGraphExchange.fitsFrame(post));
        assertTrue(RootGraphExchange.localOffer(MeshItem.fromBranch(new NativeTree.Agent(1,"Music","harmony"))));
    }
    @Test public void preservesGroupingButCannotOverwriteLocalRecord()throws Exception{
        MeshItem body=fragment("record.x.body","local:x:sample","Original","en");
        MeshItem link=fragment("record.x.link","local:x:sample","https://x.com/test/status/123","");
        MeshItem translation=fragment("record.x.es.translation","local:x:sample","Original traducido","es");
        String key=RootGraphExchange.externalKey(body);
        assertTrue(key.startsWith("local:mesh:graph:"));assertNotEquals("local:x:sample",key);
        assertEquals(key,RootGraphExchange.externalKey(link));assertEquals(key,RootGraphExchange.externalKey(translation));
        String peer="a".repeat(64);
        assertTrue(body.receivedRows(peer,1001).stream().anyMatch(r->r.source().equals("record.mesh.body")&&r.body().equals("Original")));
        assertTrue(link.receivedRows(peer,1001).stream().anyMatch(r->r.source().equals("record.mesh.link")&&r.body().equals(link.text())));
        assertTrue(translation.receivedRows(peer,1001).stream().anyMatch(r->r.source().equals("record.mesh.es.translation")));
    }
    @Test public void noTransportCredentialsOrReceiptLoops()throws Exception{
        assertFalse(RootGraphExchange.localOffer(fragment("credential.api-key","","secret","")));
        assertFalse(RootGraphExchange.localOffer(fragment("record.mesh.graph-id","local:mesh:graph:abc","old id","")));
        assertFalse(RootGraphExchange.localOffer(fragment("record.mesh.offer","local:mesh:abc","receipt envelope","")));
        MeshItem malicious=fragment("record.mesh.offer","local:mesh:fake","untrusted command","en");
        List<NativeTree.Observation> rows=malicious.receivedRows("b".repeat(64),1001);
        assertFalse(rows.stream().anyMatch(r->r.source().equals("record.mesh.offer")));
    }
    @Test public void graphCursorAdvancesWhenCatalogGrows(){
        assertEquals("cursor",EncounterSync.inventoryCursor(true,"old","new","cursor"));
        assertEquals("",EncounterSync.inventoryCursor(false,"old","new","cursor"));
        assertEquals("cursor",EncounterSync.inventoryCursor(false,"same","same","cursor"));
    }
}
