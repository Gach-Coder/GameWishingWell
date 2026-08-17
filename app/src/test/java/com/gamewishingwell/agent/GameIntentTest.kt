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

    @Test
    fun `确认门回显策划草案且不向玩家解释引擎技术细节`() {
        val intent = IntentEngine.infer("做一个没有听过的2D竖版解谜游戏", null)
        val draft = PlanningEngine.draft(intent)
        val confirmation = IntentEngine.buildConfirmation("做一个没有听过的2D竖版解谜游戏", intent, draft)
        assertEquals(draft.gameSystems, confirmation.draftPlan?.gameSystems)
        val puzzle = confirmation.systemExplanations.first { it.startsWith("解谜：") }
        assertTrue(puzzle.contains("谜题规则"))
        assertTrue(puzzle.contains("验收边界"))
        assertTrue(!puzzle.contains("Canvas"))
        assertTrue(!puzzle.contains("requestAnimationFrame"))
        assertTrue(!puzzle.contains("精灵"))
        assertTrue(!puzzle.contains("布局"))
    }

    @Test
    fun `确认门逐项解释最终要实现的系统`() {
        val schema = IntentEngine.infer("做一个塔防游戏", null)
        val confirmation = IntentEngine.buildConfirmation("做一个塔防游戏", schema)
        assertTrue(confirmation.summary.contains("画面维度"))
        assertTrue(confirmation.summary.contains("画面方向"))
        assertTrue(confirmation.summary.contains("塔防"))
        assertTrue(confirmation.systemExplanations.any { it.startsWith("塔防：") })
        // 塔防模板补全的系统也要解释
        assertTrue(confirmation.systemExplanations.any { it.startsWith("战斗：") })
    }

    @Test
    fun `确认门解释包含实现方法与业务验收边界`() {
        val schema = IntentEngine.infer("做一个2D竖版塔防游戏", null)
        val confirmation = IntentEngine.buildConfirmation("做一个2D竖版塔防游戏", schema)
        val tower = confirmation.systemExplanations.first { it.startsWith("塔防：") }
        assertTrue(tower.contains("防御塔"))
        assertTrue(tower.contains("波次"))
        assertTrue(tower.contains("验收边界"))
        assertTrue(tower.contains("基地生命"))
    }

    @Test
    fun `所有对标模板的补全系统都有具体实现细节`() {
        TemplateLibrary.ALL.forEach { ref ->
            ref.suggestedSystems.forEach { system ->
                assertTrue(
                    "模板 ${ref.id} 的系统 $system 缺少具体实现细节",
                    TemplateSystemCatalog.resolve(ref.id, system) != null
                )
            }
        }
    }

    @Test
    fun `对标游戏模板使用该游戏的具体玩法解释系统`() {
        val schema = IntentEngine.infer("做一个黄金矿工游戏", null)
        assertEquals("gold_miner", schema.templateId)
        val confirmation = IntentEngine.buildConfirmation("做一个黄金矿工游戏", schema)
        val item = confirmation.systemExplanations.first { it.startsWith("道具：") }
        assertTrue(item.contains("黄金"))
        assertTrue(item.contains("石头"))
        assertTrue(item.contains("炸弹"))
        assertTrue(item.contains("钩子前端"))
        assertTrue(item.contains("触碰"))
        assertTrue(item.contains("验收边界"))
        assertTrue("道具" in IntentEngine.plannedSystems(schema))
    }

    @Test
    fun `未命中模板时仍使用通用实现解释`() {
        val schema = IntentEngine.infer("做一个没有听过的2D竖版解谜游戏", null)
        assertNull(schema.templateId)
        val confirmation = IntentEngine.buildConfirmation("做一个没有听过的2D竖版解谜游戏", schema)
        val puzzle = confirmation.systemExplanations.first { it.startsWith("解谜：") }
        assertTrue(puzzle.contains("谜题规则"))
        assertTrue(puzzle.contains("验收边界"))
    }

    @Test
    fun `首次输入明确排除的系统会进入排除清单并回显`() {
        val intent = IntentEngine.infer("做一个塔防游戏，不要AI策略", null)
        assertTrue("AI策略" in intent.excludedSystems)
        assertTrue("AI策略" !in IntentEngine.plannedSystems(intent))
        val confirmation = IntentEngine.buildConfirmation("做一个塔防游戏，不要AI策略", intent)
        assertTrue(confirmation.summary.contains("已明确排除系统"))
        assertTrue(confirmation.excludedSystems.contains("AI策略"))
        assertTrue(confirmation.designAssumptions.any { it.contains("不得实现") })
    }

    @Test
    fun `玩家修正 2D 能覆盖已确认的 3D`() {
        val confirmed = IntentSchema(
            intent = IntentSchema.INTENT_NEW_GAME,
            visualDimension = IntentSchema.DIMENSION_3D,
            screenOrientation = IntentSchema.ORIENTATION_LANDSCAPE,
            gameSystems = listOf("竞速")
        )
        val corrected = IntentEngine.mergeCorrection(
            confirmed,
            IntentEngine.infer("改成2D竖版", null),
            "改成2D竖版"
        )
        assertEquals(IntentSchema.DIMENSION_2D, corrected.visualDimension)
        assertEquals(IntentSchema.ORIENTATION_PORTRAIT, corrected.screenOrientation)
    }

    @Test
    fun `玩家修正换成新对标游戏时不继承旧模板系统`() {
        val confirmed = IntentEngine.infer("做一个塔防游戏", null)
        val corrected = IntentEngine.mergeCorrection(
            confirmed,
            IntentEngine.infer("换成2048", null),
            "换成2048"
        )
        assertEquals("merge_2048", corrected.templateId)
        assertTrue("塔防" !in IntentEngine.plannedSystems(corrected))
        assertTrue("合成" in IntentEngine.plannedSystems(corrected))
    }

    @Test
    fun `黄金矿工修正去掉道具后其余系统仍用黄金矿工具体实现`() {
        val confirmed = IntentEngine.infer("做一个黄金矿工游戏", null)
        val corrected = IntentEngine.mergeCorrection(
            confirmed,
            IntentEngine.infer("不要道具", null),
            "不要道具"
        )
        assertEquals("gold_miner", corrected.templateId)
        assertTrue("道具" !in IntentEngine.plannedSystems(corrected))
        val confirmation = IntentEngine.buildConfirmation("做一个黄金矿工游戏，不要道具", corrected)
        val physics = confirmation.systemExplanations.first { it.startsWith("物理：") }
        assertTrue(physics.contains("钩子"))
        assertTrue(physics.contains("回收"))
    }

    @Test
    fun `玩家修正可去掉模板补全系统且保留对标模板`() {
        val confirmed = IntentEngine.infer("做一个塔防游戏", null)
        val corrected = IntentEngine.mergeCorrection(
            confirmed,
            IntentEngine.infer("不要AI策略", null),
            "不要AI策略"
        )
        assertTrue("AI策略" !in corrected.gameSystems)
        assertTrue("AI策略" !in IntentEngine.plannedSystems(corrected))
        assertTrue("塔防" in corrected.gameSystems)
        assertEquals("tower_defense", corrected.templateId)
    }
}
