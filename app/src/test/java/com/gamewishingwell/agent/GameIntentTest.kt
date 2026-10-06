package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 意图层测试：判定只走 LLM（inferLocally 正则已删除——正则无法区分"关闭弹窗"
 * 是诉求还是被谈论的对象，误判代价是整轮 Agent Loop 白跑或开发请求被打发）。
 * 这里锁定：LLM 回复解析、历史标签归一、低置信拒绝，以及兜底恒偏 dev。
 */
class GameIntentTest {

    @Test
    fun `LLM 回复解析 dev 与 chat`() {
        val parsed = IntentLayer.parseLiteLlmReply("""{"intent":"chat","confidence":0.9,"reason":"问候"}""")
        assertEquals(IntentDecision.INTENT_CHAT, parsed?.intent)
        assertTrue(parsed?.isChat == true)
        assertFalse(parsed?.isDevelop == true)
        val dev = IntentLayer.parseLiteLlmReply("""{"intent":"dev","confidence":0.95,"reason":"要做游戏"}""")
        assertEquals(IntentDecision.INTENT_DEV, dev?.intent)
        assertTrue(dev?.isDevelop == true)
        // 历史标签归一：旧三分类的 fix_bug / 旧二分类的 develop 都表示“要动游戏” → dev
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.parseLiteLlmReply("""{"intent":"fix_bug","confidence":0.9}""")?.intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.parseLiteLlmReply("""{"intent":"develop","confidence":0.9}""")?.intent)
        assertNull(IntentLayer.parseLiteLlmReply("""{"intent":"外星意图","confidence":0.9}"""))
        // LLM 自报低置信度视为不可信（调用方回落 dev 兜底）
        assertNull(IntentLayer.parseLiteLlmReply("""{"intent":"chat","confidence":0.3}"""))
        // Markdown 围栏与前后噪声容忍
        assertEquals(
            IntentDecision.INTENT_CHAT,
            IntentLayer.parseLiteLlmReply("```json\n{\"intent\":\"chat\",\"confidence\":0.9}\n```")?.intent
        )
    }

    @Test
    fun `兜底恒偏 dev 且低置信`() {
        // LLM 不可用/输出不可解析时的兜底：dev（dev 误判成 chat 致命——开发请求被
        // 一句回复打发；chat 误判成 dev 温和——多一轮识别即回退聊天）、低置信不短路。
        val fb = IntentLayer.fallback()
        assertEquals(IntentDecision.INTENT_DEV, fb.intent)
        assertTrue(fb.isDevelop)
        assertTrue("兜底置信度必须低于 LLM 判定，不构成高置信短路：conf=${fb.confidence}", fb.confidence < 0.8)
    }

    @Test
    fun `纯文本显式形态抽取`() {
        assertEquals("3D", IntentEngine.explicitDimension("做一个3D的射击游戏"))
        assertEquals("2.5D", IntentEngine.explicitDimension("2.5d 斜视角 RPG"))
        assertEquals("2D", IntentEngine.explicitDimension("二维平面的贪吃蛇"))
        assertNull(IntentEngine.explicitDimension("制作一个我的世界游戏"))
        assertEquals("横板", IntentEngine.explicitOrientation("要横屏的跑酷"))
        assertEquals("竖版", IntentEngine.explicitOrientation("竖屏小游戏"))
        assertNull(IntentEngine.explicitOrientation("制作一个我的世界游戏"))
    }

    @Test
    fun `纯文本否定与恢复系统抽取`() {
        assertEquals(setOf("道具"), IntentEngine.negatedSystems("不要道具了"))
        // 恢复语序为后置（正则从"还是要"起点向后捕获，前置于否定词的系统名不参与）
        assertTrue(IntentEngine.reAddSystems("还是要保留道具系统").contains("道具"))
        assertTrue(IntentEngine.negatedSystems("制作一个我的世界游戏").isEmpty())
    }
}
