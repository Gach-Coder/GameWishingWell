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

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
) {
    val isUser: Boolean get() = role == "user"
    val isSystem: Boolean get() = role == "system"

    companion object {
        /** 确认门卡片消息：content 为 IntentConfirmation 的精简 JSON，随聊天流持久化。 */
        const val ROLE_CONFIRM_CARD = "confirm_card"
    }

    val isConfirmCard: Boolean get() = role == ROLE_CONFIRM_CARD
}
