package dev.harness

import dev.harness.data.settings.ProviderSettings
import dev.harness.net.ChatMessage
import dev.harness.net.ChatRequest
import dev.harness.net.Connectivity
import dev.harness.net.OpenAiClient
import dev.harness.net.RetryKind
import dev.harness.net.Role
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ConnectivityTest {
    private val bodies = CopyOnWriteArrayList<String>()

    @After fun reset() = Connectivity.set(true)

    private fun chunk(text: String) =
        "data: {\"choices\":[{\"delta\":{\"content\":${kotlinx.serialization.json.JsonPrimitive(text)}}}]}\n\n"

    /** A body that sends [first] and then fails as if the connection dropped. */
    private fun cutBody(first: String): ResponseBody = object : ResponseBody() {
        override fun contentType() = "text/event-stream".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = object : Source {
            var sent = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (!sent) { sent = true; sink.writeUtf8(first); return first.length.toLong() }
                throw IOException("Connection reset")
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }.buffer()
    }

    private fun client(responses: List<() -> ResponseBody>): OpenAiClient {
        var i = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            bodies += Buffer().also { req.body!!.writeTo(it) }.readUtf8()
            val body = responses[(i++).coerceAtMost(responses.size - 1)]()
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream").body(body).build()
        }.build()
        return OpenAiClient(config = { ProviderSettings(baseUrl = "http://model.test/v1", model = "m") }, baseHttp = http, initialBackoffMs = 10)
    }

    private val request = ChatRequest(model = "m", messages = listOf(ChatMessage(Role.USER, "hi")))

    @Test fun cutStreamResumesWithoutStartingOver() = runBlocking {
        val c = client(listOf(
            { cutBody(chunk("Hello wor")) },
            { (chunk("ld!") + "data: [DONE]\n\n").toResponseBody() },
        ))
        val deltas = StringBuilder()
        val r = c.chat(request, { deltas.append(it) }) { }
        assertEquals("Hello world!", r.content)
        assertEquals("Hello world!", deltas.toString())
        assertEquals(2, bodies.size)
        assertTrue(bodies[1].contains("\"assistant\"") && bodies[1].contains("Hello wor"))
        assertTrue(bodies[1].contains("connection dropped"))
    }

    @Test fun offlineWaitsThenContinues() = runBlocking {
        Connectivity.set(false)
        val kinds = CopyOnWriteArrayList<RetryKind>()
        val c = client(listOf({ (chunk("ok") + "data: [DONE]\n\n").toResponseBody() }))
        val call = async { c.chat(request, { }) { kinds += it.kind } }
        delay(300)
        assertEquals(0, bodies.size)
        assertEquals(listOf(RetryKind.OFFLINE), kinds.toList())
        Connectivity.set(true)
        assertEquals("ok", call.await().content)
        assertEquals(listOf(RetryKind.OFFLINE, RetryKind.ONLINE), kinds.toList())
    }

    private fun String.toResponseBody(): ResponseBody {
        val s = this
        return object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = s.length.toLong()
            override fun source(): BufferedSource = Buffer().writeUtf8(s)
        }
    }
}
