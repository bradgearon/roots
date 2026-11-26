package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;
public class KnowledgeTextTest {
    @Test public void preservesAllLanguagesAndShortText(){
        assertEquals("a",KnowledgeText.fragments("a").get(0));
        assertEquals("日",KnowledgeText.fragments("日").get(0));
        assertEquals("123",KnowledgeText.fragments("123").get(0));
        assertTrue(KnowledgeText.fragments("   ").isEmpty());
    }
    @Test public void longValuesFitNativeUtf8Bounds(){
        StringBuilder s=new StringBuilder();
        for(int i=0;i<3999;i++)s.append('日');
        s.append("😀 trailing text");
        java.util.List<String> parts=KnowledgeText.fragments(s);
        assertEquals(s.toString(),String.join("",parts));
        for(String p:parts)assertTrue(p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=16384);
    }
}
