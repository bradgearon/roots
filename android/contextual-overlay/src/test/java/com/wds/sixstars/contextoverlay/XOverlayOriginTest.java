package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public final class XOverlayOriginTest {
    @Test public void measuresOriginFromNodesInsteadOfVisibleWindowTop(){
        XOverlayOrigin measured=new XOverlayOrigin();
        measured.observe(168,820,1048,990,168,655,1048,825);
        measured.observe(168,1200,1048,1350,168,1035,1048,1185);
        assertEquals(new XOverlayOrigin.Offset(0,165),measured.result());
    }
    @Test public void handlesShiftedWindowInBothAxes(){
        XOverlayOrigin measured=new XOverlayOrigin();
        measured.observe(196,310,876,430,168,250,848,370);
        measured.observe(196,820,876,900,168,760,848,840);
        assertEquals(new XOverlayOrigin.Offset(28,60),measured.result());
    }
    @Test public void inconsistentOriginMustUseScreenSpaceFallback(){
        XOverlayOrigin measured=new XOverlayOrigin();
        measured.observe(168,820,1048,990,168,655,1048,825);
        measured.observe(168,1200,1048,1350,168,1200,1048,1350);
        assertNull(measured.result());
    }
    @Test public void missingAndNonTranslationTransformsAreRejected(){
        XOverlayOrigin missing=new XOverlayOrigin();
        assertNull(missing.result());
        missing.observe(168,820,1048,990,168,655,1048,825);
        missing.missing();
        assertNull(missing.result());
        XOverlayOrigin scaled=new XOverlayOrigin();
        scaled.observe(168,820,1048,990,84,410,524,495);
        assertNull(scaled.result());
    }
}
