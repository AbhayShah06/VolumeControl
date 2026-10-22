package com.abhayshah.volumecontrol;

import org.junit.Test;
import static org.junit.Assert.*;

public class LevelTest {
    @Test public void everyNonzeroLevelHasDistinctGainAndNeverBoosts() {
        float previous = -97;
        for (int n = 0; n <= 100; n++) {
            float gain = Level.decibels(n);
            assertTrue(gain > previous); assertTrue(gain <= 0); previous = gain;
        }
        assertEquals(-60, Level.decibels(1), 0.0001);
        assertEquals(0, Level.decibels(100), 0.0001);
    }
    @Test public void corruptOrOutOfRangeValuesAreClamped() {
        assertEquals(-96, Level.decibels(Integer.MIN_VALUE), 0);
        assertEquals(0, Level.decibels(Integer.MAX_VALUE), 0);
    }
    @Test public void attenuationPreservesSongDynamics() {
        double gain = Math.pow(10, Level.decibels(40) / 20.0);
        double quiet = 0.02 * gain, loud = 0.8 * gain;
        assertEquals(40.0, loud / quiet, 0.00001);
    }
    @Test public void physicalKeysPassThroughForCallsOrInactiveControl() {
        assertTrue(Level.handlesKeys(true, true, true, 0));
        assertFalse(Level.handlesKeys(false, true, true, 0));
        assertFalse(Level.handlesKeys(true, false, true, 0));
        assertFalse(Level.handlesKeys(true, true, false, 0));
        for (int mode = 1; mode <= 6; mode++) assertFalse(Level.handlesKeys(true, true, true, mode));
    }
    @Test public void shortNotificationsAndInterfaceSoundsNeverInterruptMusic() {
        for (int usage : new int[]{0, 1, 5, 7, 8, 9, 10, 11, 12, 13, 14}) assertFalse(Level.interruptsMedia(usage));
        for (int usage : new int[]{2, 3, 4, 6}) assertTrue(Level.interruptsMedia(usage));
    }
}
