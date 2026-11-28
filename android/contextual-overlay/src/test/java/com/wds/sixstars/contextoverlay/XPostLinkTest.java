package com.wds.sixstars.contextoverlay;

import com.wds.sixstars.contextoverlay.tree.NativeTree;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class XPostLinkTest {
    @Test public void exactSourceUrlsAreCanonicalized(){
        assertEquals("https://x.com/example/status/123",XPostLink.canonical("https://twitter.com/example/status/123?s=20"));
        assertEquals("https://x.com/i/web/status/123",XPostLink.fromRemoteId(123L));
        assertEquals("https://x.com/example/status/123",XPostLink.fromMetadata("Posted at noon: https://x.com/example/status/123"));
        assertEquals("https://x.com/example/status/123",XPostLink.fromMetadata("https://x.com/example/status/123 (https://x.com/i/web/status/123)"));
    }
    @Test public void unrelatedAndAmbiguousSourceIdsAreNotAccepted(){
        assertEquals("",XPostLink.canonical("https://x.com.example.org/example/status/123"));
        assertEquals("",XPostLink.canonical("https://x.com/example/status/123/other"));
        assertEquals("",XPostLink.canonical("https://x.com/example/status/%31%32%33"));
        assertEquals("",XPostLink.fromRemoteId("local:x:123"));
        assertEquals("",XPostLink.fromMetadata("https://x.com/a/status/123 https://x.com/b/status/456"));
    }
    private static NativeTree.Fragment fragment(long n,String role,String text){
        return new NativeTree.Fragment(n,"record.x."+role,"local:x:fixture",text,"",n);
    }
    private static NativeTree.Master record(NativeTree.Fragment... fragments){
        return new NativeTree.Master(1,"local:x:fixture",Arrays.asList(fragments));
    }
    @Test public void recordNavigationUsesOnlyExplicitSourceFields(){
        assertEquals("",XPostLink.forRecord(record(fragment(1,"body","https://x.com/a/status/123"))));
        assertEquals("https://x.com/a/status/123",XPostLink.forRecord(record(
            fragment(1,"link","https://x.com/i/web/status/123"),fragment(2,"link","https://x.com/a/status/123"))));
        assertEquals("",XPostLink.forRecord(record(
            fragment(1,"link","https://x.com/a/status/123"),fragment(2,"link","https://x.com/b/status/456"))));
        assertEquals("https://x.com/a/status/123",XPostLink.forRecord(record(
            fragment(1,"link","https://x.com/b/status/456"),fragment(2,"link-confirmed","https://x.com/a/status/123"))));
    }
}
