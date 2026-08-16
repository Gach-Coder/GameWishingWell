package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameIntentTest {

    @Test
    fun `正则抽取维度方向系统与对标游戏`() {
        val intent = IntentEngine.infer("做一个2.5D横板跑酷游戏，要有关卡、收集金币和AI敌人", null)
        assertEquals(IntentSchema.INTENT_NEW_GAME, intent.intent)
        assertEquals("2.5D", intent.visualDimension)
        assertEquals("横板", intent.screenOrientation)
        assertTrue(intent.gameSystems.containsAll(listOf("关卡场景", "收集", "AI策略")))
        assertEquals("跑酷", intent.referenceGame)
        assertEquals("endless_runner", intent.templateId)
    }

    @Test
    fun `有已有游戏时识别为 modify_game`() {
        val intent = IntentEngine.infer("加个连击计分", "<html></html>")
        assertEquals(IntentSchema.INTENT_MODIFY_GAME, intent.intent)
    }

    @Test
    fun `非法枚举值被映射默认值`() {
        val bad = IntentSchema(intent = "unknown", visualDimension = "VR", screenOrientation = "斜的", gameSystems = listOf("不存在的系统"))
        val fixed = IntentSchemaValidator.validate(bad)
        assertEquals(IntentSchema.INTENT_NEW_GAME, fixed.intent)
        assertEquals("2D", fixed.visualDimension)
        assertEquals("竖版", fixed.screenOrientation)
        assertTrue(fixed.gameSystems.isEmpty())
    }

    @Test
    fun `模板映射不上返回 null`() {
        val intent = IntentEngine.infer("做一个完全没听过的游戏", null)
        assertNull(intent.templateId)
        assertNull(intent.templateSimilarity)
    }

    @Test
    fun `Lite LLM JSON 解析`() {
        val parsed = IntentSchemaValidator.parseLiteLlmReply(
            """{"intent":"new_game","visualDimension":"2D","screenOrientation":"横板","gameSystems":["战斗","道具"],"referenceGame":"打地鼠","confidence":0.9}"""
        )
        assertEquals("战斗", parsed?.gameSystems?.first())
        assertEquals("whack_a_mole", parsed?.templateId)
    }

    @Test
    fun `纯聊天意图不会进入游戏生成`() {
        assertEquals(IntentSchema.INTENT_CHAT, IntentEngine.infer("你好", null).intent)
    }

    @Test
    fun `确认门包含设计假设`() {
        val confirmation = IntentEngine.buildConfirmation("打地鼠", IntentEngine.infer("做一个打地鼠游戏", null))
        assertTrue(confirmation.summary.contains("竖版"))
        assertTrue(confirmation.designAssumptions.isNotEmpty())
    }
}
