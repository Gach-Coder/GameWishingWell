package com.gamewishingwell.llm

import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.data.ToolCallData
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
    fun `请求不携带 max_tokens 不限制输出长度`() = runBlocking {
        val server = MockWebServer()
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

        // 输出长度不设限：请求体不携带 max_tokens，由网关按模型上限裁定
        val recorded = server.takeRequest()
        assertFalse(recorded.body.readUtf8().contains("\"max_tokens\""))
        assertEquals(1, server.requestCount)

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
    fun `零内容且无 DONE 的流判为可重试网络异常`() = runBlocking {
        val server = MockWebServer()
        // 连接被中途掐断：200 + SSE 头 + 零事件即关流（无 [DONE]）——
        // 此前会作为"空回复"正常返回，绕过统一重试，表象是空转或非法工具参数
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("")
        )
        server.start()

        try {
            client(server).streamChat(listOf(ChatMessage("user", "hi")), onDelta = {}, onDone = {})
            fail("应当抛出 IOException（可重试网络异常）")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("连接提前关闭"))
        }

        server.shutdown()
    }

    @Test
    fun `带 DONE 的空回复是合法空响应不报错`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n")
        )
        server.start()

        val resp = client(server).streamChat(listOf(ChatMessage("user", "hi")), onDelta = {}, onDone = {})
        assertEquals("", resp.text)
        assertTrue(resp.toolCalls.isEmpty())

        server.shutdown()
    }

    @Test
    fun `流式 tool_calls 分片聚合`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    // id/name 只在首帧出现；arguments 分两片到达，中间任意截断
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"editfile\",\"arguments\":\"{\\\"path\"}}]}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\":\\\"index.html\\\"}\"}}]}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        server.start()

        val resp = client(server).streamChat(
            listOf(ChatMessage("user", "改一下")),
            onDelta = {},
            onDone = {},
            tools = listOf(ToolSpec("editfile", "增量修改", """{"type":"object"}"""))
        )
        assertEquals(1, resp.toolCalls.size)
        assertEquals("call_1", resp.toolCalls[0].id)
        assertEquals("editfile", resp.toolCalls[0].name)
        assertEquals("{\"path\":\"index.html\"}", resp.toolCalls[0].arguments)
        assertTrue(resp.usedTools)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"tools\":["))
        // 线格式契约：每个工具定义必须带 "type":"function"（曾因 encodeDefaults=false 被静默丢弃，
        // 导致严格网关 4xx→去掉 tools 重试→永远降级兼容模式）
        assertTrue(body.contains("\"type\":\"function\""))
        assertTrue(body.contains("\"name\":\"editfile\""))
        assertTrue(body.contains("\"parameters\":{\"type\":\"object\"}"))

        server.shutdown()
    }

    @Test
    fun `assistant 工具调用历史回传线格式完整`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n")
        )
        server.start()

        client(server).streamChat(
            listOf(
                ChatMessage("user", "hi"),
                ChatMessage(
                    "assistant", "",
                    toolCalls = listOf(ToolCallData(id = "call_1", name = "writefile", arguments = "{}"))
                ),
                ChatMessage(ChatMessage.ROLE_TOOL, "写入成功", toolCallId = "call_1")
            ),
            onDelta = {},
            onDone = {}
        )

        val body = server.takeRequest().body.readUtf8()
        // 历史 assistant.tool_calls 同样必须带 "type":"function"，工具结果必须带 tool_call_id
        assertTrue(body.contains("\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\""))
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""))

        server.shutdown()
    }

    @Test
    fun `网关不支持 tools 时去掉字段重试并返回纯文本`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"tools is not supported\"}"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n")
        )
        server.start()

        val resp = client(server).streamChat(
            listOf(ChatMessage("user", "hi")),
            onDelta = {},
            onDone = {},
            tools = listOf(ToolSpec("readfile", "读文件", """{"type":"object"}"""))
        )
        assertEquals("OK", resp.text)
        assertTrue(resp.toolCalls.isEmpty())

        val first = server.takeRequest()
        assertTrue(first.body.readUtf8().contains("\"tools\":["))
        val second = server.takeRequest()
        assertFalse(second.body.readUtf8().contains("\"tools\""))

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
