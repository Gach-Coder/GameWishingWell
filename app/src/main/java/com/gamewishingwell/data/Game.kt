package com.gamewishingwell.data

import kotlinx.serialization.Serializable

@Serializable
data class GameMeta(
    val id: Long,
    val title: String,
    val description: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val playCount: Int = 0
)

@Serializable
data class GameIndex(
    val games: List<GameMeta> = emptyList()
)

/** 文件可视系统条目：游戏存储文件夹内一个文件/子目录的概要信息（仅展示，不打开）。 */
data class GameFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    /** 目录内的直接子项数（文件恒为 0）。 */
    val childCount: Int = 0,
    val lastModified: Long
)

/** 一次工具调用请求：id 与参数原样字符串由协议层产出，执行层自行解析参数 JSON。 */
@Serializable
data class ToolCallData(
    val id: String,
    val name: String,
    val arguments: String
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    /** assistant 消息携带的工具调用请求；其余角色恒为空列表。 */
    val toolCalls: List<ToolCallData> = emptyList(),
    /** role="tool" 的消息对应的调用 id；其余角色为 null。 */
    val toolCallId: String? = null
) {
    val isUser: Boolean get() = role == "user"
    val isSystem: Boolean get() = role == "system"
    val isTool: Boolean get() = role == "tool"

    companion object {
        /** 确认门卡片消息：content 为 IntentConfirmation 的精简 JSON，随聊天流持久化。 */
        const val ROLE_CONFIRM_CARD = "confirm_card"
        /** 工具结果消息的 role；仅存在于 Agent Loop 内部上下文，不入聊天流。 */
        const val ROLE_TOOL = "tool"
    }

    val isConfirmCard: Boolean get() = role == ROLE_CONFIRM_CARD
}
