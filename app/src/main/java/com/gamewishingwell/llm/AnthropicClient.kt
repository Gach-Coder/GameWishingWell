package com.gamewishingwell.llm

import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.data.ToolCallData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicReference

/**
 * Anthropic Messages API 的流式客户端。
 * 接口：POST {baseUrl}/v1/messages，SSE 输出 event:/data:，正文取 content_block_delta 的 text。
 *
 * 工具调用：请求 tools=[{name,description,input_schema}]；assistant 的调用以
 * content block（type=tool_use）出现，参数 JSON 在 content_block_delta 的
 * input_json_delta.partial_json 中分片到达，按 block index 聚合后随 [LlmResponse] 返回。
 * 工具结果以 user 消息的 tool_result block 回填（连续结果合并进同一条 user 消息，
 * 满足角色交替要求）。
 */
class AnthropicClient(
    private val okHttp: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val maxTokens: Int = 8192,
    /** 是否请求 Anthropic extended thinking；false 时省略 thinking 字段（服务端默认关闭）。 */
    private val thinkingEnabled: Boolean = false
) : LlmClient {

    override val protocol: Protocol = Protocol.ANTHROPIC

    override suspend fun streamChat(
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit,
        onDone: () -> Unit,
        tools: List<ToolSpec>
    ): LlmResponse {
        // runInterruptible + activeCall.cancel()：停止键取消协程时会中断阻塞中的 OkHttp SSE 读取。
        // 这里不设任何超时，默认用户可无限等待；只有用户主动停止才会取消底层网络调用。
        val activeCall = AtomicReference<Call?>(null)
        currentCoroutineContext()[Job]?.invokeOnCompletion {
            activeCall.get()?.cancel()
        }
        return runInterruptible(Dispatchers.IO) {
            val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
            val normalized = normalizeAlternation(convertToBlocks(messages.filterNot { it.role == "system" }))

            val requestBody = Json.encodeToString(
                AnthropicChatRequest(
                    model = model,
                    max_tokens = maxTokens,
                    stream = true,
                    system = system,
                    messages = normalized,
                    tools = tools.map { t ->
                        AnthropicToolSpec(
                            name = t.name,
                            description = t.description,
                            input_schema = Json.parseToJsonElement(t.parameters)
                        )
                    },
                    thinking = if (thinkingEnabled) {
                        // budget_tokens 必须小于 max_tokens，且至少 1024；单文件游戏给足思考预算。
                        val budget = (maxTokens - 1024).coerceIn(1024, 4096)
                        AnthropicThinkingConfig(type = "enabled", budget_tokens = budget)
                    } else {
                        null
                    }
                )
            )
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Accept", "text/event-stream")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val call = okHttp.newCall(request)
            activeCall.set(call)
            try {
                call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(300) ?: ""
                    throw LlmError("API 错误 ${response.code}：$err")
                }
                val source = response.body!!.source()
                // 默认用户可无限等待：不做内容停滞超时，用户主动按停止键时由 runInterruptible 中断读取。
                val text = StringBuilder()
                val blocks = HashMap<Int, PartialToolBlock>()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    handleEvent(payload, text, blocks, onDelta)
                }
                onDone()
                LlmResponse(
                    text = text.toString(),
                    toolCalls = blocks.entries.sortedBy { it.key }.map { (_, b) ->
                        ToolCallData(
                            id = b.id.ifBlank { "toolu_${b.name.hashCode().toUInt()}" },
                            name = b.name,
                            arguments = b.arguments.toString().ifBlank { "{}" }
                        )
                    }
                )
                }
            } finally {
                activeCall.compareAndSet(call, null)
            }
        }
    }

    private class PartialToolBlock(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    private fun handleEvent(
        payload: String,
        text: StringBuilder,
        blocks: HashMap<Int, PartialToolBlock>,
        onDelta: (String) -> Unit
    ) {
        val obj = try {
            Json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            return
        }
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "content_block_start" -> {
                val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
                val block = obj["content_block"]?.jsonObject ?: return
                if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                    blocks[index] = PartialToolBlock(
                        id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
            }
            "content_block_delta" -> {
                val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
                val delta = obj["delta"]?.jsonObject ?: return
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> {
                        val chunk = delta["text"]?.jsonPrimitive?.contentOrNull ?: return
                        text.append(chunk)
                        onDelta(chunk)
                    }
                    "input_json_delta" -> {
                        val chunk = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: return
                        blocks[index]?.arguments?.append(chunk)
                    }
                }
            }
        }
    }

    // ---------- 消息到 content blocks 的转换 ----------

    /** 工具结果 block 连续出现时合并进同一条 user 消息（Anthropic 要求角色交替）。 */
    private fun convertToBlocks(messages: List<ChatMessage>): List<AnthropicMessage> {
        val result = mutableListOf<AnthropicMessage>()
        for (m in messages) {
            when {
                m.isTool -> {
                    val block = buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", m.toolCallId.orEmpty())
                        put("content", m.content)
                    }
                    val last = result.lastOrNull()
                    val lastBlocks = last?.content as? JsonArray
                    if (last != null && last.role == "user" && lastBlocks != null && lastBlocks.all { isToolResultBlock(it) }) {
                        result[result.size - 1] = AnthropicMessage("user", JsonArray(lastBlocks + block))
                    } else {
                        result.add(AnthropicMessage("user", JsonArray(listOf(block))))
                    }
                }
                m.role == "assistant" && m.toolCalls.isNotEmpty() -> {
                    val blocks = buildJsonArray {
                        if (m.content.isNotBlank()) {
                            add(buildJsonObject { put("type", "text"); put("text", m.content) })
                        }
                        m.toolCalls.forEach { tc ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", tc.id)
                                put("name", tc.name)
                                put("input", parseInput(tc.arguments))
                            })
                        }
                    }
                    result.add(AnthropicMessage("assistant", blocks))
                }
                else -> {
                    val role = if (m.role == "assistant") "assistant" else "user"
                    result.add(AnthropicMessage(role, JsonPrimitive(m.content)))
                }
            }
        }
        return result
    }

    private fun isToolResultBlock(el: JsonElement): Boolean =
        el is JsonObject && el["type"]?.jsonPrimitive?.contentOrNull == "tool_result"

    private fun parseInput(arguments: String): JsonElement = try {
        if (arguments.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(arguments)
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

    /** 保证首条为 user 且角色严格交替；相邻同角色内容合并为 blocks 数组。 */
    private fun normalizeAlternation(messages: List<AnthropicMessage>): List<AnthropicMessage> {
        var list = messages
        if (list.isNotEmpty() && list.first().role != "user") {
            list = listOf(AnthropicMessage("user", JsonPrimitive("请继续。"))) + list
        }
        val result = mutableListOf<AnthropicMessage>()
        for (m in list) {
            val last = result.lastOrNull()
            if (last != null && last.role == m.role) {
                result[result.size - 1] = AnthropicMessage(m.role, mergeContents(last.content, m.content))
            } else {
                result.add(m)
            }
        }
        return result
    }

    private fun mergeContents(a: JsonElement, b: JsonElement): JsonArray = buildJsonArray {
        fun addAll(el: JsonElement) {
            when {
                el is JsonArray -> el.forEach { add(it) }
                el is JsonPrimitive && el.content.isNotBlank() ->
                    add(buildJsonObject { put("type", "text"); put("text", el.content) })
                else -> add(el)
            }
        }
        addAll(a)
        addAll(b)
    }

    @Serializable
    private data class AnthropicMessage(
        val role: String,
        val content: JsonElement
    )

    @Serializable
    private data class AnthropicToolSpec(
        val name: String,
        val description: String,
        val input_schema: JsonElement
    )

    @Serializable
    private data class AnthropicChatRequest(
        val model: String,
        val max_tokens: Int,
        val stream: Boolean,
        val system: String = "",
        val messages: List<AnthropicMessage>,
        val tools: List<AnthropicToolSpec> = emptyList(),
        val thinking: AnthropicThinkingConfig? = null
    )

    @Serializable
    private data class AnthropicThinkingConfig(
        val type: String,
        val budget_tokens: Int
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
