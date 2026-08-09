package com.fastpaste.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardFingerprintGateTest {
    @Test
    fun appliedEchoIsConsumedSoManualRecopyCanSend() {
        val gate = ClipboardFingerprintGate()
        assertTrue(gate.shouldApply("abc"))
        assertFalse(gate.shouldSend("abc"))
        assertTrue(gate.shouldSend("abc"))
    }
}
