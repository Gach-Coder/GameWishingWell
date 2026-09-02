package com.gamewishingwell.llm

import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.data.ToolCallData

class LlmError(message: String) : Exception(message)

/** 工具定义：parameters 为 JSON Schema 的原样 JSON 字符串，由各协议客户端自行嵌入请求。 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: String
)

/** 一次流式调用的最终结果：文本 + 模型发起的工具调用（无则为空列表）。 */
data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCallData> = emptyList()
) {
    val usedTools: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * 统一的流式 LLM 客户端接口。
 * 流式过程中通过 [onDelta] 逐个字符推送最终内容，[onThinking] 推送模型的思考过程
 * （推理模型如 deepseek-v4-flash 会先输出 reasoning_content；按 instruct.txt 约定，
 * 前端不展示源代码与思考原文，只把流用于内部进度判断），
 * 结束调用 [onDone]；网络/API 错误以异常抛出。
 *
 * [tools] 非空时请求携带工具定义，返回的 [LlmResponse.toolCalls] 为模型发起的调用；
 * 工具结果由调用方以 role="tool" 的 [ChatMessage]（带 toolCallId）回填到下一轮消息中。
 */
interface LlmClient {
    val protocol: Protocol

    suspend fun streamChat(
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit = {},
        onDone: () -> Unit,
        tools: List<ToolSpec> = emptyList()
    ): LlmResponse
}

/**
 * 合并相邻的相同 role 纯文本消息（Anthropic 要求消息交替，OpenAI 也接受合并后的紧凑上下文）。
 * 携带 toolCalls / toolCallId 的消息不参与合并：工具调用与结果必须逐条精确配对。
 */
internal fun mergeConsecutive(messages: List<ChatMessage>): List<ChatMessage> =
    messages.fold(mutableListOf()) { acc, m ->
        val canMerge = m.toolCalls.isEmpty() && m.toolCallId == null &&
            m.role != ChatMessage.ROLE_TOOL
        if (canMerge && acc.isNotEmpty() && acc.last().role == m.role && acc.last().toolCalls.isEmpty() && acc.last().toolCallId == null) {
            val last = acc.removeAt(acc.size - 1)
            acc.add(last.copy(content = last.content + "\n\n" + m.content))
        } else {
            acc.add(m)
        }
        acc
    }
