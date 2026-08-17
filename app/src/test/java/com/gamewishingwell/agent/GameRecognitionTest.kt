package com.gamewishingwell.agent

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecognitionTest {

    @Test
    fun `意图层只输出 develop 或 chat 两个意图`() {
        assertEquals(IntentDecision.INTENT_DEVELOP, IntentLayer.inferLocally("做一个打地鼠游戏").intent)
        assertEquals(IntentDecision.INTENT_CHAT, IntentLayer.inferLocally("你好").intent)
        assertEquals(IntentDecision.INTENT_DEVELOP, IntentLayer.inferLocally("加个连击计分").intent)

        val parsed = IntentLayer.parseLiteLlmReply("""{"intent":"chat","confidence":0.9,"reason":"问候"}""")
        assertEquals(IntentDecision.INTENT_CHAT, parsed?.intent)
        assertTrue(parsed?.isChat == true)
    }

    @Test
    fun `首次识别创建会话级 Game Schema 并与下游共享`() {
        val userText = "做一个2.5D横板跑酷游戏，要有关卡、收集金币和AI敌人"
        val result = RecognitionEngine.recognize(
            userText,
            existingSchema = null
        )

        assertTrue(result.isNewGame)
        assertFalse(result.emptyEntities)
        val schema = result.gameSchema
        assertEquals("2.5D", schema.visualDimension)
        assertEquals("横板", schema.screenOrientation)
        assertTrue(schema.gameSystems.containsAll(listOf("关卡场景", "收集", "AI策略")))
        assertEquals("endless_runner", schema.templateId)
        assertEquals(userText, schema.firstUserRequest)
        assertEquals(userText, schema.lastUserRequest)
        assertEquals(schema, GameSchemaValidator.validate(schema))

        // 下游策划层直接消费同一份 Game Schema JSON
        val plan = PlanningEngine.build(schema)
        assertEquals(schema.gameSystems, plan.gameSystems)
        assertTrue(plan.implementations.isNotEmpty())
    }

    @Test
    fun `非空 Game Schema 默认锚定同一个游戏并继续修改`() {
        val first = RecognitionEngine.recognize("做一个黄金矿工游戏", null).gameSchema
        val modified = RecognitionEngine.recognize("不要道具", first)

        assertEquals("不要道具", modified.gameSchema.lastUserRequest)
        assertFalse(modified.isNewGame)
        assertFalse(modified.emptyEntities)
        assertTrue("道具" !in modified.gameSchema.gameSystems)
        assertTrue("道具" in modified.gameSchema.excludedSystems)
        assertEquals("gold_miner", modified.gameSchema.templateId)
        assertTrue("物理" in modified.gameSchema.gameSystems)
    }

    @Test
    fun `修改轮换成新对标游戏时会切换模板系统`() {
        val first = RecognitionEngine.recognize("做一个塔防游戏", null).gameSchema
        val switched = RecognitionEngine.recognize("换成2048", first)

        assertFalse(switched.isNewGame)
        assertEquals("merge_2048", switched.gameSchema.templateId)
        assertTrue("合成" in switched.gameSchema.gameSystems)
        assertTrue("塔防" !in switched.gameSchema.gameSystems)
    }

    @Test
    fun `识别层抽取不到实体时回退为普通聊天`() {
        assertTrue(RecognitionEngine.recognize("你好", null).emptyEntities)

        val anchored = RecognitionEngine.recognize("做一个打地鼠游戏", null).gameSchema
        val chat = RecognitionEngine.recognize("谢谢", anchored)
        assertTrue(chat.emptyEntities)
        assertEquals(anchored, chat.gameSchema)
    }

    @Test
    fun `无实体但明确要求做游戏时仍创建默认 Game Schema`() {
        val result = RecognitionEngine.recognize("给我做一个游戏", null)
        assertFalse(result.emptyEntities)
        assertTrue(result.gameSchema.gameSystems.isNotEmpty())
    }

    @Test
    fun `局部修改词在已锚定游戏上不会丢失原有系统`() {
        val first = RecognitionEngine.recognize("做一个塔防游戏", null).gameSchema
        val modified = RecognitionEngine.recognize("加个连击计分", first)
        assertFalse(modified.emptyEntities)
        assertEquals(first.gameSystems, modified.gameSchema.gameSystems)
        assertTrue("塔防" in modified.gameSchema.gameSystems)
    }

    @Test
    fun `确认门用策划层 LLM 的玩家视角阐述且不泄露技术词`() {
        val schema = RecognitionEngine.recognize("做一个黄金矿工游戏", null).gameSchema
        val draft = PlanningEngine.build(schema)
        val confirmation = RecognitionEngine.buildConfirmation(
            "做一个黄金矿工游戏",
            schema,
            draft,
            isNewGame = true
        )
        assertTrue(confirmation.isNewGame)
        assertEquals(schema, confirmation.gameSchema)
        assertNull(confirmation.intent)
        val item = confirmation.systemExplanations.first { it.startsWith("道具：") }
        assertTrue(item.contains("黄金"))
        assertTrue(item.contains("石头"))
        assertTrue(item.contains("炸弹"))
        assertTrue(item.contains("验收边界"))
        assertFalse(item.contains("Canvas"))
        assertFalse(item.contains("requestAnimationFrame"))
    }

    @Test
    fun `Game Schema JSON 可持久化往返`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val schema = RecognitionEngine.recognize("做一个黄金矿工游戏", null).gameSchema
        val encoded = json.encodeToString(schema)
        val decoded = json.decodeFromString<GameSchema>(encoded)
        assertEquals(schema, decoded)
    }
}
