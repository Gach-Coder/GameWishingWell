package com.gamewishingwell.llm

import com.gamewishingwell.data.ChatMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicClientTest {

    private fun streamResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"OK\"}}\n\n" +
                "data: [DONE]\n\n"
        )

    @Test
    fun `思考能力关闭时 Anthropic 请求不发送 thinking 字段`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(streamResponse())
        server.start()

        AnthropicClient(
            okHttp = OkHttpClient(),
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "claude-sonnet-4-6",
            thinkingEnabled = false
        ).streamChat(
            listOf(ChatMessage("user", "hi")),
            onDelta = {},
            onDone = {}
        )

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("\"thinking\""))
        server.shutdown()
    }

    @Test
    fun `思考能力开启时 Anthropic 请求包含 enabled 与 budget_tokens`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(streamResponse())
        server.start()

        AnthropicClient(
            okHttp = OkHttpClient(),
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "claude-sonnet-4-6",
            maxTokens = 16384,
            thinkingEnabled = true
        ).streamChat(
            listOf(ChatMessage("user", "hi")),
            onDelta = {},
            onDone = {}
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":4096}"))
        server.shutdown()
    }
}
