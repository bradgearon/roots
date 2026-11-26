package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;
public class TranslationWorkPriorityTest {
    @Test public void visibleReadingOrder(){
        assertTrue(TranslationWorkPriority.score(true,100,0)>TranslationWorkPriority.score(true,900,0));
        assertTrue(TranslationWorkPriority.score(true,900,0)>TranslationWorkPriority.score(false,100,0));
    }
    @Test public void retainedWorkIsNotWorthless(){
        assertTrue(TranslationWorkPriority.score(false,0,0)>0);
        assertTrue(TranslationWorkPriority.score(false,0,110000)>TranslationWorkPriority.score(true,0,0));
    }
}
