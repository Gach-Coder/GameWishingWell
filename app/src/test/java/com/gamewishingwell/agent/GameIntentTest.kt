package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameIntentTest {

    @Test
    fun `意图层输出 dev chat 两个意图`() {
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("做一个打地鼠游戏").intent)
        assertEquals(IntentDecision.INTENT_CHAT, IntentLayer.inferLocally("你好").intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("加个连击计分").intent)
        // 修复类词汇不再参与路由：文本报告的异常统一按 dev 走正常管线，
        // 快车道由会话状态（运行时错误签名）推导，不靠猜文本。
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("游戏报错了，点开始没反应，修复一下").intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("修复bug").intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("闪退了帮我修修").intent)
        // 制作新游戏的指令优先于修复词：“修汽车”是游戏内容，不是修 bug
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("做一个修汽车的装修游戏").intent)
        assertTrue(IntentLayer.inferLocally("修复bug").isDevelop)
        assertFalse(IntentLayer.inferLocally("你好").isDevelop)
        // 短问句陷阱：开发诉求不得被疑问前缀误判成 chat 并以高置信度短路
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("能不能简单一点").intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("再难一点").intent)

        val parsed = IntentLayer.parseLiteLlmReply("""{"intent":"chat","confidence":0.9,"reason":"问候"}""")
        assertEquals(IntentDecision.INTENT_CHAT, parsed?.intent)
        assertTrue(parsed?.isChat == true)
        // 历史标签归一：旧三分类的 fix_bug / 旧二分类的 develop 都表示“要动游戏” → dev
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.parseLiteLlmReply("""{"intent":"fix_bug","confidence":0.9}""")?.intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.parseLiteLlmReply("""{"intent":"develop","confidence":0.9}""")?.intent)
        assertNull(IntentLayer.parseLiteLlmReply("""{"intent":"外星意图","confidence":0.9}"""))
        // LLM 自报低置信度视为不可信，回落本地默认（dev）
        assertNull(IntentLayer.parseLiteLlmReply("""{"intent":"chat","confidence":0.3}"""))
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
