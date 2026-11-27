package com.wds.sixstars.contextoverlay;

import com.wds.sixstars.contextoverlay.tree.NativeTree;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeshGraphItemTest {
    @Test public void fragmentPreservesSourceIdentityAndFullText() throws Exception {
        String body="A".repeat(3500)+" distant topic";
        NativeTree.Fragment original=new NativeTree.Fragment(1,"record.x.body","local:x:one",body,"en",123);
        MeshItem item=MeshItem.fromFragment(original);
        assertEquals(body,item.text());
        assertEquals("record.x.body",item.graph().source());
        assertEquals("local:x:one",item.graph().key());
        assertEquals("en",item.graph().language());
        assertEquals(123L,item.graph().at());
        assertTrue(item.matchesTopic("distant topic"));
        assertTrue(item.topic().length()<=120);
    }
    @Test public void nativeIdentityDeduplicatesAcrossDevices() throws Exception {
        MeshItem one=MeshItem.fromFragment(new NativeTree.Fragment(1,"record.x.body","local:x:one","hello","en",10));
        MeshItem two=MeshItem.fromFragment(new NativeTree.Fragment(99,"record.x.body","local:x:one","hello","",99));
        assertEquals(one.id(),two.id());
        assertNotEquals(one.id(),MeshItem.fromFragment(new NativeTree.Fragment(2,"record.x.body","local:x:two","hello","en",10)).id());
        assertNotEquals(one.id(),MeshItem.fromFragment(new NativeTree.Fragment(3,"record.x.en.translation","local:x:one","hello","en",10)).id());
    }
    @Test public void branchIdentityIsIndependentOfLocalNumericId() throws Exception {
        MeshItem one=MeshItem.fromBranch(new NativeTree.Agent(1,"Music","melody"));
        MeshItem two=MeshItem.fromBranch(new NativeTree.Agent(23,"Music","melody"));
        assertEquals(one.id(),two.id());
        assertEquals("branch",one.graph().kind());
        assertEquals("Music",one.graph().key());
        assertEquals("melody",one.text());
    }
    @Test public void originalLinkRemainsAttachedToItsRecord() throws Exception {
        MeshItem link=MeshItem.fromFragment(new NativeTree.Fragment(2,"record.x.link","local:x:one","https://x.com/i/web/status/123","",42));
        assertEquals("https://x.com/i/web/status/123",link.link());
        assertEquals("local:x:one",link.graph().key());
        assertEquals("und",link.language());
        assertEquals("",link.graph().language());
    }
    @Test public void legacyRemembranceStillSupported() throws Exception {
        MeshItem legacy=MeshItem.create("en","Music","A melody","");
        assertNull(legacy.graph());
        assertEquals("local:mesh:"+legacy.id(),legacy.key());
    }
}
