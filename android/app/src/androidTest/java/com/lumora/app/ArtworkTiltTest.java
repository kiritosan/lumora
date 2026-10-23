package com.lumora.app;

import android.test.InstrumentationTestCase;

/** Tilt mapping saturates and remains centered without sensor input. */
public final class ArtworkTiltTest extends InstrumentationTestCase {
    public void testTiltMapping() {
        assertEquals(0f, ArtworkView.sensorTilt(0f), 0.001f);
        assertTrue(ArtworkView.sensorTilt(4.9f) > 5f);
        assertEquals(-8f, ArtworkView.sensorTilt(-9.8f), 0.001f);
        assertEquals(8f, ArtworkView.sensorTilt(20f), 0.001f);
    }
}
