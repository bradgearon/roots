package com.wds.sixstars.contextoverlay;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class PublicTreePublishingTest {
    private static NativeTree.Fragment fragment(long sequence,String source,String body,String lang){
        return new NativeTree.Fragment(sequence,source,"local:x:sample",body,lang,1000);
    }
    @Test public void onlyOriginalBodyAndExactLinkAreProjected()throws Exception{
        NativeTree.Master record=new NativeTree.Master(1,"local:x:sample",Arrays.asList(
            fragment(1,"record.x.body","Original public post","en"),
            fragment(2,"record.x.link","https://x.com/example/status/123",""),
            fragment(3,"record.x.en.understanding","Private interpretation","en"),
            fragment(4,"record.x.author","Private metadata","")));
        MeshItem item=PublicTreePublishing.project(record);
        assertNotNull(item);assertEquals("Original public post",item.text());assertEquals("https://x.com/example/status/123",item.link());
        assertNull(item.graph());assertEquals("Public X post",item.topic());
    }
    @Test public void rawAndUnlinkedRecordsAreNotProjected()throws Exception{
        var body=fragment(1,"record.x.body","Unlinked","en");
        assertNull(PublicTreePublishing.project(new NativeTree.Master(1,"local:x:sample",Arrays.asList(body))));
        assertNull(PublicTreePublishing.project(new NativeTree.Master(1,"local:other",Arrays.asList(body,fragment(2,"record.x.link","https://x.com/example/status/123","")))));
    }
}
