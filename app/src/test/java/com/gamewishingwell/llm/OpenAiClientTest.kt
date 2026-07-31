package com.gamewishingwell.llm

import com.gamewishingwell.data.ChatMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiClientTest {

    private fun client(server: MockWebServer) = OpenAiCompatibleClient(
        okHttp = OkHttpClient(),
        apiKey = "test-key",
        baseUrl = server.url("/").toString().trimEnd('/'),
        model = "test-model"
    )

    @Test
    fun `流式解析 delta 内容`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        server.start()

        val sb = StringBuilder()
        client(server).streamChat(
            listOf(ChatMessage("user", "hi")),
            onDelta = { sb.append(it) },
            onDone = {}
        )
        assertEquals("AB", sb.toString())

        // 校验请求体包含 model 与消息
        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"stream\":true"))

        server.shutdown()
    }

    @Test
    fun `max_tokens 被服务端拒绝时去掉该字段重试`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("{\"error\":\"max_tokens must be <= 4096\"}")
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n")
        )
        server.start()

        val sb = StringBuilder()
        client(server).streamChat(
            listOf(ChatMessage("user", "hi")),
            onDelta = { sb.append(it) },
            onDone = {}
        )
        assertEquals("OK", sb.toString())

        val first = server.takeRequest()
        assertTrue(first.body.readUtf8().contains("\"max_tokens\""))
        val second = server.takeRequest()
        assertFalse(second.body.readUtf8().contains("\"max_tokens\""))

        server.shutdown()
    }

    @Test
    fun `推理模型的 reasoning_content 解析与 thinking disabled 请求`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"一\\n下\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"<html>game</html>\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        server.start()

        val content = StringBuilder()
        val thinking = StringBuilder()
        OpenAiCompatibleClient(
            okHttp = OkHttpClient(),
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "test-model",
            disableThinking = true
        ).streamChat(
            listOf(ChatMessage("user", "hi")),
            onDelta = { content.append(it) },
            onThinking = { thinking.append(it) },
            onDone = {}
        )
        assertEquals("想一\n下", thinking.toString())
        assertEquals("<html>game</html>", content.toString())

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))

        server.shutdown()
    }

    @Test
    fun `thinking 字段被服务端拒绝时去掉该字段重试`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("{\"error\":\"unknown field: thinking\"}")
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n")
        )
        server.start()

        val sb = StringBuilder()
        OpenAiCompatibleClient(
            okHttp = OkHttpClient(),
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "test-model",
            disableThinking = true
        ).streamChat(
            listOf(ChatMessage("user", "hi")),
            onDelta = { sb.append(it) },
            onDone = {}
        )
        assertEquals("OK", sb.toString())

        val first = server.takeRequest()
        assertTrue(first.body.readUtf8().contains("\"thinking\""))
        val second = server.takeRequest()
        assertFalse(second.body.readUtf8().contains("\"thinking\""))

        server.shutdown()
    }

    @Test
    fun `流在无 DONE 标记时正常结束并回调 onDone`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"X\"}}]}\n\n")
        )
        server.start()

        var doneCalled = false
        val sb = StringBuilder()
        client(server).streamChat(
            listOf(ChatMessage("user", "hi")),
            onDelta = { sb.append(it) },
            onDone = { doneCalled = true }
        )
        assertEquals("X", sb.toString())
        assertTrue(doneCalled)

        server.shutdown()
    }

    @Test
    fun `API 错误时抛出 LlmError`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid key\"}"))
        server.start()

        try {
            client(server).streamChat(listOf(ChatMessage("user", "hi")), onDelta = {}, onDone = {})
            fail("应当抛出 LlmError")
        } catch (e: LlmError) {
            assertTrue(e.message.orEmpty().contains("401"))
        }

        server.shutdown()
    }
}
