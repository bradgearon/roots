package com.wds.sixstars.contextoverlay;

import org.junit.Test;

import static org.junit.Assert.*;

/** Source-body bounds only; controls are positioned by a separate role service. */
public final class AutoInlinePlacementServiceTest {
    @Test public void staysInsideSourceBodyWithoutOverlayingCardHeader() {
        AutoInlinePlacementService.Placement p = AutoInlinePlacementService.locate(
                40, 250, 400, 330, 440, 920, 1f);
        assertNotNull(p);
        assertEquals(40, p.x);
        assertEquals(250, p.y);
        assertEquals(360, p.width);
        assertEquals(80, p.height);
    }

    @Test public void oneLineJapaneseBodiesAreStillPlaceable() {
        AutoInlinePlacementService.Placement p = AutoInlinePlacementService.locate(
                30, 210, 100, 228, 440, 920, 1f);
        assertNotNull(p);
        assertEquals(18, p.height);
        assertEquals(70, p.width);
    }

    @Test public void rejectsBodyThatHasScrolledAboveTop() {
        assertNull(AutoInlinePlacementService.locate(
                40, -2, 400, 90, 440, 920, 1f));
    }

    @Test public void rejectsOnlyTinyBottomSliver() {
        assertNull(AutoInlinePlacementService.locate(
                40, 915, 400, 950, 440, 920, 1f));
    }

    @Test public void clipsToViewportInsteadOfCoveringNextPost() {
        AutoInlinePlacementService.Placement p = AutoInlinePlacementService.locate(
                40, 720, 400, 960, 440, 920, 1f);
        assertNotNull(p);
        assertEquals(200, p.height);
        assertEquals(920, p.y + p.height);
    }

    @Test public void narrowAndInvalidRectanglesAreRejected() {
        assertNull(AutoInlinePlacementService.locate(
                20, 200, 45, 230, 440, 920, 1f));
        assertNull(AutoInlinePlacementService.locate(
                20, 230, 400, 200, 440, 920, 1f));
    }
}
