package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationLatencyTest {
    @Test public void learnsSeparatelyByProfileAndLength(){
        TranslationLatency model=new TranslationLatency();
        long cpu=model.predict("cpu:6",20);
        long longCpu=model.predict("cpu:6",500);
        long gpu=model.predict("vulkan:6",20);
        assertTrue(longCpu>cpu);
        for(int i=0;i<8;i++)model.observe("vulkan:6",20,1400);
        assertTrue(model.predict("vulkan:6",20)>gpu);
        assertEquals(cpu,model.predict("cpu:6",20));
        assertEquals(longCpu,model.predict("cpu:6",500));
        model.observe("cpu:6",20,300000);
        assertTrue(model.predict("cpu:6",20)<4000);
    }
}
