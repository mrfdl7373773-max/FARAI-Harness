package dev.harness

import dev.harness.net.SseParser
import dev.harness.net.StreamAssembler
import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {
    @Test
    fun assemblesTextAndToolCallsFromChunks() {
        val stream = """
            data: {"choices":[{"delta":{"content":"Hel"}}]}

            data: {"choices":[{"delta":{"content":"lo"}}]}

            : keep-alive

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read","arguments":"{\"pa"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent()
        val events = SseParser.parseAll(stream)
        val asm = StreamAssembler()
        events.filter { it != SseParser.DONE }.forEach { asm.apply(it) }
        val r = asm.result(emptyMap(), 200)
        assertEquals("Hello", r.content)
        assertEquals("read", r.toolCalls.single().function.name)
        assertEquals("{\"path\":\"a\"}", r.toolCalls.single().function.arguments)
        assertEquals("tool_calls", r.finishReason)
    }
}
