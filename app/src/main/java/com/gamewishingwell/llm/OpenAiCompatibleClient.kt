package com.gamewishingwell.llm

import com.gamewishingwell.data.ChatMessage
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容协议的流式客户端（适用于 DeepSeek / Kimi(Moonshot) / OpenAI 等）。
 * 接口：POST {baseUrl}/chat/completions，SSE 输出 data: {...} / data: [DONE]。
 *
 * 工具调用：请求体携带 tools（function calling）；SSE 中 delta.tool_calls 按 index
 * 分片到达（id/name 可能只出现一次、arguments 逐段追加），此处聚合成完整调用后
 * 随 [LlmResponse] 返回。网关不支持 tools 字段时（4xx 且错误信息提及），
 * 去掉该字段重试一次，模型将以纯文本回答——调用方可据此降级。
 *
 * 输出长度不设上限：请求不携带 max_tokens（由网关按模型上限自行裁定），
 * 截断输出只会制造非法 JSON 的工具参数与残缺代码。
 */
class OpenAiCompatibleClient(
    private val okHttp: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    /** 是否发送 thinking={"type":"disabled"} 关闭推理模型的思考过程（由设置页思考开关驱动，默认关闭）。 */
    private val disableThinking: Boolean = false
) : LlmClient {

    override val protocol: Protocol = Protocol.OPENAI_COMPATIBLE

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
        // 降级重试的安全护栏：当前 LlmError 只会从"非 2xx 响应"抛出（尚未推送任何
        // 增量），重发请求不会重复回调；一旦未来加入流中错误（已推增量后抛出），
        // 重发会让调用方收到两次拼接的内容——此处直接失败，交给上层 callLlm 重试。
        var deltasEmitted = false
        val guardedOnDelta: (String) -> Unit = { s ->
            deltasEmitted = true
            onDelta(s)
        }
        return runInterruptible(Dispatchers.IO) {
            try {
                executeStream(
                    buildRequest(messages, includeThinking = true, tools = tools),
                    guardedOnDelta, onThinking, onDone, activeCall
                )
            } catch (e: LlmError) {
                if (Thread.currentThread().isInterrupted || deltasEmitted) throw e
                val msg = e.message ?: ""
                when {
                    // 个别网关不支持 thinking 字段，去掉后重试
                    msg.contains("thinking", ignoreCase = true) ->
                        executeStream(
                            buildRequest(messages, includeThinking = false, tools = tools),
                            guardedOnDelta, onThinking, onDone, activeCall
                        )
                    // 网关不支持 function calling：去掉 tools 重试，由调用方按纯文本回复降级处理
                    tools.isNotEmpty() && msg.contains(Regex("tool|function", RegexOption.IGNORE_CASE)) ->
                        executeStream(
                            buildRequest(messages, includeThinking = true, tools = emptyList()),
                            guardedOnDelta, onThinking, onDone, activeCall
                        )
                    else -> throw e
                }
            }
        }
    }

    private fun buildRequest(
        messages: List<ChatMessage>,
        includeThinking: Boolean,
        tools: List<ToolSpec>
    ): Request {
        val body = Json.encodeToString(
            OpenAiChatRequest(
                model = model,
                stream = true,
                thinking = if (includeThinking && disableThinking) ThinkingConfig("disabled") else null,
                messages = mergeConsecutive(messages).map { it.toWire() },
                tools = tools.takeIf { it.isNotEmpty() }?.map { it.toWire() }
            )
        )
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun executeStream(
        request: Request,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit,
        onDone: () -> Unit,
        activeCall: AtomicReference<Call?>
    ): LlmResponse {
        val call = okHttp.newCall(request)
        activeCall.set(call)
        try {
            call.execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(300) ?: ""
                throw LlmError("API 错误 ${response.code}：$err")
            }
            val source = response.body!!.source()
            // 默认用户可无限等待：不做内容停滞超时，服务端多久没有新内容都继续等；
            // 用户主动按停止键时由 runInterruptible 中断阻塞读取。
            var reasoningChars = 0L
            var contentChars = 0L
            var lastFinishReason: String? = null
            var lastPayloadTail: String? = null
            var endedByDone = false
            val text = StringBuilder()
            val aggregator = ToolCallAggregator()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") { endedByDone = true; break }
                    lastPayloadTail = payload.takeLast(120)
                    try {
                        val el = Json.parseToJsonElement(payload).jsonObject
                        lastFinishReason = el["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("finish_reason")?.jsonPrimitive?.contentOrNull ?: lastFinishReason
                        val delta = el["choices"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("delta")?.jsonObject
                        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                        val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                        val toolCallDeltas = delta?.get("tool_calls")?.jsonArray
                        if (toolCallDeltas != null) aggregator.accept(toolCallDeltas)
                        if (content != null) {
                            text.append(content)
                            contentChars += content.length
                            onDelta(content)
                        }
                        if (reasoning != null) {
                            reasoningChars += reasoning.length
                            onThinking(reasoning)
                        }
                    } catch (e: Exception) { /* 忽略单帧解析失败 */ }
                }
            }
            val toolCalls = aggregator.build()
            android.util.Log.d(
                "OpenAiClient",
                "流结束: done=$endedByDone 推理字符=$reasoningChars 内容字符=$contentChars " +
                    "工具调用=${toolCalls.size} finish_reason=$lastFinishReason 尾帧=$lastPayloadTail"
            )
            // 连接被中途掐断（网关/代理/移动网络抖动）时 readUtf8Line 返回 null 正常退出循环——
            // 零内容 + 零工具调用 + 未收到 [DONE] 属于"假成功"：不抛错就会绕过统一重试，
            // 上层只能看到空回复/非法工具参数，表象是空转或莫名失败。显性化为可重试
            // IOException（已带部分内容时保持旧行为：尽力返回已收内容，由上层处置）。
            if (!endedByDone && contentChars == 0L && reasoningChars == 0L && toolCalls.isEmpty()) {
                throw IOException("连接提前关闭：未收到 [DONE] 且无任何内容（网关/网络瞬断）")
            }
            onDone()
            return LlmResponse(text = text.toString(), toolCalls = toolCalls)
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    /** OpenAI 线格式消息：assistant 携带 tool_calls 数组，工具结果走 role="tool" + tool_call_id。
     *  注意：type 字段不能带默认值——默认 Json 配置 encodeDefaults=false，
     *  带默认值的字段不会写入请求体，缺 "type":"function" 会被严格网关 4xx 拒绝或被静默忽略（工具调用全失效）。 */
    private fun ChatMessage.toWire(): OpenAiMessage = OpenAiMessage(
        role = if (role == "tool") "tool" else role,
        content = content.ifBlank { if (toolCalls.isNotEmpty() || role == "tool") null else content },
        tool_calls = toolCalls.takeIf { it.isNotEmpty() }?.map { tc ->
            OpenAiToolCall(id = tc.id, type = "function", function = OpenAiFunctionCall(name = tc.name, arguments = tc.arguments))
        },
        tool_call_id = toolCallId
    )

    private fun ToolSpec.toWire(): OpenAiToolDef = OpenAiToolDef(
        type = "function",
        function = OpenAiFunctionSpec(
            name = name,
            description = description,
            parameters = Json.parseToJsonElement(parameters)
        )
    )

    @Serializable
    private data class OpenAiChatRequest(
        val model: String,
        val stream: Boolean,
        val thinking: ThinkingConfig? = null,
        val messages: List<OpenAiMessage>,
        val tools: List<OpenAiToolDef>? = null
    )

    @Serializable
    private data class OpenAiMessage(
        val role: String,
        val content: String? = null,
        val tool_calls: List<OpenAiToolCall>? = null,
        val tool_call_id: String? = null
    )

    @Serializable
    private data class OpenAiToolCall(
        val id: String,
        val type: String,
        val function: OpenAiFunctionCall
    )

    @Serializable
    private data class OpenAiFunctionCall(
        val name: String,
        val arguments: String
    )

    @Serializable
    private data class OpenAiToolDef(
        val type: String,
        val function: OpenAiFunctionSpec
    )

    @Serializable
    private data class OpenAiFunctionSpec(
        val name: String,
        val description: String,
        val parameters: JsonElement
    )

    @Serializable
    private data class ThinkingConfig(val type: String)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * OpenAI 流式 tool_calls 聚合器：delta 里的每个元素带 index，id/name 通常只在首帧出现，
 * arguments 以任意位置截断的分片多次到达；必须按 index 累积到流结束才得到完整 JSON。
 */
private class ToolCallAggregator {

    private data class Partial(
        var id: String? = null,
        var name: StringBuilder = StringBuilder(),
        var arguments: StringBuilder = StringBuilder()
    )

    private val parts = LinkedHashMap<Int, Partial>()

    fun accept(deltaArray: JsonArray) {
        for (element in deltaArray) {
            val obj = element as? JsonObject ?: continue
            val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: run {
                    // 个别实现不带 index：优先按已见过的 id 归位，否则视作新调用
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull
                    val known = id?.let { wanted -> parts.entries.firstOrNull { it.value.id == wanted }?.key }
                    known ?: parts.size
                }
            val partial = parts.getOrPut(index) { Partial() }
            obj["id"]?.jsonPrimitive?.contentOrNull?.let { partial.id = it }
            obj["function"]?.jsonObject?.let { fn ->
                fn["name"]?.jsonPrimitive?.contentOrNull?.let { partial.name.append(it) }
                fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { partial.arguments.append(it) }
            }
        }
    }

    fun build(): List<com.gamewishingwell.data.ToolCallData> = parts.values
        .filter { it.name.isNotEmpty() || it.arguments.isNotEmpty() }
        .mapIndexed { idx, p ->
            com.gamewishingwell.data.ToolCallData(
                id = p.id?.takeIf { it.isNotBlank() } ?: "call_$idx",
                name = p.name.toString(),
                arguments = p.arguments.toString().ifBlank { "{}" }
            )
        }
}
