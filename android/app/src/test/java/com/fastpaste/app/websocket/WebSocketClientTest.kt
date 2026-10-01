package com.fastpaste.app.websocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketClientTest {
    @Test
    fun oldCallbacksAreRejectedEvenWhenReconnectingToTheSameUrl() {
        val connections = ConnectionGeneration()
        val first = connections.advance()
        val replacement = connections.advance()
        assertFalse(connections.isCurrent(first))
        assertTrue(connections.isCurrent(replacement))
        connections.advance() // disconnect invalidates all outstanding callbacks
        assertFalse(connections.isCurrent(replacement))
    }
    @Test
    fun pairRequiredIsRecognizedBeforeSecureChannelHandling() {
        assertEquals("pair_required", protocolMessageType("""{"type":"pair_required"}"""))
    }

    @Test
    fun malformedMessagesHaveNoProtocolType() {
        assertEquals("", protocolMessageType("not-json"))
    }
}
