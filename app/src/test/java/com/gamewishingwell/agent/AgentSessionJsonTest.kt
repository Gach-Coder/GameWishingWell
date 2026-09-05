package com.gamewishingwell.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionJsonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `会话与 Game Schema JSON 往返（含旧字段忽略）`() {
        val schema = GameSchema(
            visualDimension = "3D",
            screenOrientation = "横板",
            gameSystems = listOf("自由建造", "收集"),
            requestedSystems = listOf("自由建造"),
            confidence = 0.9
        )
        val confirmation = RecognitionEngine.buildConfirmation("制作一个我的世界游戏", schema)
        val session = GameSession(
            messages = listOf(ChatMessageLike.user("制作一个我的世界游戏")),
            gameSchema = schema,
            pendingConfirmation = confirmation,
            qualityTier = QualityTier.PREMIUM
        )
        val encoded = json.encodeToString(session)
        // 旧会话 JSON 携带的已删除对标字段（referenceGame/templateId 等）必须被忽略而不是报错
        val legacy = encoded.replace("\"qualityTier\"", "\"referenceGame\":\"我的世界\",\"templateId\":\"sandbox_voxel\",\"qualityTier\"")
        val decoded = json.decodeFromString<GameSession>(legacy)
        assertEquals(schema, decoded.gameSchema)
        assertEquals(QualityTier.PREMIUM, decoded.qualityTier)
        assertTrue(decoded.pendingConfirmation != null)
    }

    @Test
    fun `确认卡内容往返保留形态字段`() {
        val schema = GameSchema(visualDimension = "3D", screenOrientation = "横板", gameSystems = listOf("塔防"))
        val confirmation = RecognitionEngine.buildConfirmation("做一个塔防游戏", schema)
        val card = IntentConfirmation.cardContent(confirmation)
        val restored = IntentConfirmation.fromCardContent(card)
        assertTrue(restored != null)
        assertEquals("3D", restored!!.visualDimension)
        assertEquals("横板", restored.screenOrientation)
    }
}

private object ChatMessageLike {
    fun user(text: String) = com.gamewishingwell.data.ChatMessage("user", text)
}
