package com.wds.sixstars.contextoverlay;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class KnowledgeArchiveTest {
    @Test public void archiveSizingStartsAt4096AndAdapts(){
        assertEquals(4096,ArchiveSizing.choose(4096,8000,16*1024*1024,8*1024*1024,0,0,0));
        assertEquals(800,ArchiveSizing.choose(4096,8000,16*1024*1024,8*1024*1024,10,400,100));
        assertEquals(8192,ArchiveSizing.choose(4096,8000,16*1024*1024,8*1024*1024,.1,100,20));
    }
    @Test public void requestIdsAreBinaryAndUnboundedByLegacyFrame()throws Exception{
        List<String> ids=new ArrayList<>();
        for(int i=0;i<4096;i++)ids.add(String.format(java.util.Locale.ROOT,"%064x",i));
        byte[] bytes=KnowledgeChunk.encodeIds(ids);
        assertEquals(131072,bytes.length);
        assertEquals(ids,KnowledgeChunk.decodeIds(bytes,4096));
    }
    @Test public void partitionsDoNotChangeWhenUnrelatedKnowledgeGrows()throws Exception{
        String first="0"+"0".repeat(63),second="f".repeat(64);
        KnowledgeManifest manifest=new KnowledgeManifest(Collections.singleton(first));
        KnowledgeManifest.Part old=manifest.part(0);
        manifest.add(second);
        assertEquals(old,manifest.part(0));
        assertEquals(1,manifest.part(63).count());
    }
}
