package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class XPostMetadataTest {
    @Test public void composeIdentifiersAreNotRemotePostIds(){
        assertFalse(XPostMetadata.remoteKey(XPostMetadata.SEMANTICS_ID));
        assertFalse(XPostMetadata.remoteKey(XPostMetadata.TEST_TAG));
        assertEquals("",XPostMetadata.linkFromTag("timeline_post"));
        assertEquals("",XPostMetadata.linkFromTag("42924"));
        assertEquals("",XPostMetadata.linkFromTag("post_id_42924"));
    }

    @Test public void acceptsExplicitRemoteMetadata(){
        assertTrue(XPostMetadata.remoteKey("com.twitter.android.post_id"));
        assertTrue(XPostMetadata.remoteKey("tweetId"));
        assertTrue(XPostMetadata.remoteKey("post_permalink"));
        assertEquals("https://x.com/i/web/status/1234567890123456789",
            XPostMetadata.linkFromTag("post_id_1234567890123456789"));
        assertEquals("https://x.com/example/status/1234567890123456789",
            XPostMetadata.linkFromTag("https://x.com/example/status/1234567890123456789?s=20"));
        assertEquals("",XPostMetadata.linkFromTag("https://example.com/status/1234567890123456789"));
    }
}
