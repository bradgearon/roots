package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;
public class TranslationAdmissionTest {
    @Test public void strictModeWaitsForCompletion(){
        TranslationAdmission a=new TranslationAdmission();
        assertTrue(a.tryAcquire("old:en",1));
        assertFalse(a.tryAcquire("new:en",1));
        assertFalse(a.tryAcquire("old:en",4));
        assertTrue(a.release("old:en"));
        assertTrue(a.tryAcquire("new:en",1));
        assertFalse(a.release("old:en"));
    }
    @Test public void reducingLimitDrainsExistingWork(){
        TranslationAdmission a=new TranslationAdmission();
        assertTrue(a.tryAcquire("a",3));assertTrue(a.tryAcquire("b",3));
        assertFalse(a.tryAcquire("c",1));
        a.release("a");assertFalse(a.tryAcquire("c",1));
        a.release("b");assertTrue(a.tryAcquire("c",1));
    }
}
