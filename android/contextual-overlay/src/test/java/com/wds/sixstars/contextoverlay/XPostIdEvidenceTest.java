package com.wds.sixstars.contextoverlay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class XPostIdEvidenceTest {
    private static final long NOW=1795824000000L;
    private static final long ID=((NOW-1288834974657L-60000L)<<22)|73;
    @Test public void smallLocalIdentifiersDoNotBecomePostCandidates(){
        XPostIdEvidence audit=new XPostIdEvidence(NOW);
        audit.text("compose.id","card","69187",false);
        audit.text("span.id","body","2032",false);
        audit.parcel(new byte[]{(byte)0xf0,7,0,0},"span","body");
        assertTrue(audit.hits().isEmpty());
    }
    @Test public void namedAndSerializedStringEvidencePreservesItsOrigin(){
        XPostIdEvidence audit=new XPostIdEvidence(NOW);
        audit.text("extras.postId","card",Long.toString(ID),true);
        audit.parcel(("https://x.com/example/status/"+ID).getBytes(StandardCharsets.UTF_16LE),"nodeParcel","body");
        assertTrue(audit.hits().stream().anyMatch(h->h.id().equals(Long.toString(ID))&&h.kind().startsWith("explicit")&&h.location().equals("card")));
        assertTrue(audit.hits().stream().anyMatch(h->h.id().equals(Long.toString(ID))&&h.kind().startsWith("status URL")&&h.location().equals("body")));
    }
    @Test public void binaryCandidatesAreNotConfirmedIdentities(){
        XPostIdEvidence audit=new XPostIdEvidence(NOW);
        audit.parcel(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(ID).array(),"nodeParcel","card");
        assertTrue(audit.hits().stream().anyMatch(h->h.id().equals(Long.toString(ID))&&h.kind().equals("raw 64-bit candidate only")));
        assertFalse(audit.hits().stream().anyMatch(h->h.kind().startsWith("explicit")));
    }
}
