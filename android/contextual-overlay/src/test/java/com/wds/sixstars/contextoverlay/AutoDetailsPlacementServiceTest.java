package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pixel-only avatar-gutter placement; no Android SDK/device required. */
public final class AutoDetailsPlacementServiceTest {
    @Test public void iconGoesBelowTheActualAvatarAndOutsideTheNameAndBody() {
        AutoDetailsPlacementService.Placement p = AutoDetailsPlacementService.locate(
                130, 260, 410, 335, 0, 195, 440, 420, true,
                22, 205, 104, 283, 440, 920, 1f);
        assertNotNull(p);
        assertEquals(287, p.y);
        assertEquals(47, p.x); // avatar center 63, width 32
        assertEquals(30, p.height); // slightly taller than old 21dp control
        assertTrue(p.x + p.width < 130);
    }

    @Test public void cardBoundsSupplyAnAvatarGutterWhenXHidesImageNodes() {
        AutoDetailsPlacementService.Placement p = AutoDetailsPlacementService.locate(
                130, 255, 400, 325, 0, 192, 440, 422, true,
                0, 0, 0, 0, 440, 920, 1f);
        assertNotNull(p);
        assertEquals(49, p.x);
        assertEquals(262, p.y);
        assertTrue(p.x + p.width < 130);
    }

    @Test public void noAvatarOrCardStillUsesTheBodyLeftGutter() {
        AutoDetailsPlacementService.Placement p = AutoDetailsPlacementService.locate(
                130, 252, 405, 280, 130, 252, 405, 280, false,
                0, 0, 0, 0, 440, 920, 1f);
        assertNotNull(p);
        assertEquals(84, p.x);
        assertEquals(260, p.y);
    }

    @Test public void doesntPlaceControlsAcrossTheNextPostOrViewport() {
        assertNull(AutoDetailsPlacementService.locate(
                130, 250, 400, 290, 0, 200, 440, 275, true,
                15, 210, 100, 240, 440, 920, 1f));
        assertNull(AutoDetailsPlacementService.locate(
                130, 905, 400, 935, 0, 850, 440, 970, true,
                15, 870, 100, 902, 440, 920, 1f));
        assertNull(AutoDetailsPlacementService.locate(
                22, 200, 400, 300, 0, 180, 440, 350, true,
                0, 0, 0, 0, 440, 920, 1f));
    }

    @Test public void measuredMovementShiftsTheActionByExactlyTheSamePixels() {
        AutoDetailsPlacementService.Placement original =
                new AutoDetailsPlacementService.Placement(47, 289, 32, 30);
        AutoDetailsPlacementService.Placement moved = original.shifted(0, -57);
        assertEquals(47, moved.x);
        assertEquals(232, moved.y);
        assertFalse(original.sameAs(moved));
        assertTrue(moved.sameAs(new AutoDetailsPlacementService.Placement(47, 232, 32, 30)));
    }
}
