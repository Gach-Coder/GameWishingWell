package com.gamewishingwell.llm

import com.gamewishingwell.data.ChatMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnthropicClientTest {

    private fun streamResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            // 协议忠实事件：delta 必带 type，流以 message_stop 收尾（缺 type 的夹具
            // 此前靠"静默空响应不报错"通过，现在会被空流守卫正确拦截）
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n"
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

    @Test
    fun `tool_use block 分片聚合与请求携带工具结果`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"writefile\",\"input\":{}}}\n\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}\n\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"index.html\\\",\\\"content\\\":\\\"<html>\\\"}\"}}\n\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"已写入\"}}\n\n" +
                        "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                        "data: {\"type\":\"message_stop\"}\n\n"
                )
        )
        server.start()

        val client = AnthropicClient(
            okHttp = OkHttpClient(),
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "claude-sonnet-4-6"
        )
        // 历史中已含上一轮的 tool_use 与工具结果：请求体应转换为 content blocks 回传
        val history = mutableListOf(
            ChatMessage("user", "做个游戏"),
            ChatMessage("assistant", "我来写入文件", toolCalls = listOf(com.gamewishingwell.data.ToolCallData("toolu_1", "writefile", "{}"))),
            ChatMessage(ChatMessage.ROLE_TOOL, "已写入 index.html（v1）", toolCallId = "toolu_1")
        )
        val resp = client.streamChat(
            history,
            onDelta = {},
            onDone = {},
            tools = listOf(ToolSpec("writefile", "写文件", """{"type":"object"}"""))
        )
        assertEquals("已写入", resp.text)
        assertEquals(1, resp.toolCalls.size)
        assertEquals("toolu_1", resp.toolCalls[0].id)
        assertEquals("writefile", resp.toolCalls[0].name)
        assertEquals("{\"path\":\"index.html\",\"content\":\"<html>\"}", resp.toolCalls[0].arguments)

        // 请求体：tools 定义 + assistant tool_use block + user tool_result block
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"tools\":["))
        assertTrue(body.contains("\"input_schema\":{\"type\":\"object\"}"))
        assertTrue(body.contains("\"type\":\"tool_use\""))
        assertTrue(body.contains("\"type\":\"tool_result\""))
        assertTrue(body.contains("\"tool_use_id\":\"toolu_1\""))

        server.shutdown()
    }

    @Test
    fun `SSE error 事件抛 LlmError 而非静默返回空文本`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n" +
                        "data: {\"type\":\"message_stop\"}\n\n"
                )
        )
        server.start()

        val client = AnthropicClient(
            okHttp = OkHttpClient(),
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "claude-sonnet-4-6"
        )
        val thrown = try {
            client.streamChat(listOf(ChatMessage("user", "hi")), onDelta = {}, onDone = {})
            null
        } catch (e: LlmError) {
            e
        }
        // 曾经被静默吞掉、以空文本"正常"返回——上层只能看到空回复无法归因。
        assertNotNull(thrown)
        assertTrue(thrown!!.message!!.contains("overloaded_error"))
        server.shutdown()
    }
    @Test
    fun `零内容且无 message_stop 的流判为可重试网络异常`() = runBlocking {
        val server = MockWebServer()
        // 连接被中途掐断：200 + SSE 头 + 零事件即关流（无 message_stop）——
        // 此前会作为"空回复"正常返回，绕过统一重试
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("")
        )
        server.start()

        try {
            AnthropicClient(
                okHttp = OkHttpClient(),
                apiKey = "test-key",
                baseUrl = server.url("/").toString().trimEnd('/'),
                model = "claude-sonnet-4-6"
            ).streamChat(listOf(ChatMessage("user", "hi")), onDelta = {}, onDone = {})
            fail("应当抛出 IOException（可重试网络异常）")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("连接提前关闭"))
        }

        server.shutdown()
    }

    @Test
    fun `带 message_stop 的空回复是合法空响应`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_stop\"}\n\n")
        )
        server.start()

        val resp = AnthropicClient(
            okHttp = OkHttpClient(),
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "claude-sonnet-4-6"
        ).streamChat(listOf(ChatMessage("user", "hi")), onDelta = {}, onDone = {})
        assertEquals("", resp.text)
        assertTrue(resp.toolCalls.isEmpty())

        server.shutdown()
    }
}
