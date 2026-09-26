package com.hyouka.spacebudsz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceBudsConnectionTest {
    @Test
    fun recognizesSupportedAirBudsNames() {
        assertTrue(isSupportedSpaceBudsName("Oraimo SpaceBuds Z"))
        assertTrue(isSupportedSpaceBudsName("OTW-625L"))
        assertTrue(isSupportedSpaceBudsName("OTW625"))
        assertTrue(isSupportedSpaceBudsName("SpaceBuds Z (LE)"))
    }

    @Test
    fun rejectsUnrelatedBluetoothNames() {
        assertFalse(isSupportedSpaceBudsName("AirPods Pro"))
        assertFalse(isSupportedSpaceBudsName("Galaxy Buds"))
        assertFalse(isSupportedSpaceBudsName("Generic 625L Device"))
        assertFalse(isSupportedSpaceBudsName(null))
        assertFalse(isSupportedSpaceBudsName("   "))
    }
}
