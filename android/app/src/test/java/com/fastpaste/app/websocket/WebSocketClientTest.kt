package com.fastpaste.app.websocket

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketClientTest {
    @Test
    fun pairRequiredIsRecognizedBeforeSecureChannelHandling() {
        assertEquals("pair_required", protocolMessageType("""{"type":"pair_required"}"""))
    }

    @Test
    fun malformedMessagesHaveNoProtocolType() {
        assertEquals("", protocolMessageType("not-json"))
    }
}
