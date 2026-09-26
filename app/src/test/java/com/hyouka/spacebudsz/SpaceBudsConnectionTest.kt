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
    }

    @Test
    fun rejectsUnrelatedBluetoothNames() {
        assertFalse(isSupportedSpaceBudsName("AirPods Pro"))
        assertFalse(isSupportedSpaceBudsName("Galaxy Buds"))
        assertFalse(isSupportedSpaceBudsName(null))
        assertFalse(isSupportedSpaceBudsName("   "))
    }
}
