package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePlanTest {

    private fun schemaOf(text: String): GameSchema = RecognitionEngine.recognize(text, null).gameSchema

    @Test
    fun `build 由纯文本系统清单构建策划方案`() {
        val schema = schemaOf("做一个塔防游戏，塔台可以升级，敌人按波次进攻，还有商店经济")
        val plan = PlanningEngine.build(schema)
        assertTrue(plan.gameSystems.contains("塔防"))
        assertEquals("塔防", plan.primarySystem)
        assertTrue(plan.templateClass.contains("塔防"))
        assertTrue(plan.acceptanceChecklist.any { it.contains("塔防") })
        assertTrue(PlanningEngine.toPrompt(plan).contains("primary_system:塔防"))
    }

    @Test
    fun `allowEmptySystems 不做默认系统兜底（直接制作路径）`() {
        // 契约：无显性系统时系统由生成 LLM 按档位自行推导——
        // 硬塞"反应躲避+收集"兜底会框住设计（我的世界被做成守关的旧病）。
        val plan = PlanningEngine.build(GameSchema(visualDimension = "3D"), allowEmptySystems = true)
        assertTrue(plan.gameSystems.isEmpty())
        assertTrue(plan.implementations.isEmpty())
        assertEquals("核心玩法", plan.primarySystem)
        // 默认路径（未开 allowEmptySystems）仍保留兜底，供模糊需求使用
        val fallbackPlan = PlanningEngine.build(GameSchema())
        assertTrue(fallbackPlan.gameSystems.isNotEmpty())
    }

    @Test
    fun `全部取消勾选时进入裸需求模式且不出现无关系统名`() {
        // 勾选只做加法不做减法：全不勾选 = 用户原话直接交给 Agent，
        // 不套用预设系统清单、不硬排除任何系统，生成照常进行。
        val schema = schemaOf("做一个塔防游戏，敌人按波次进攻")
        val allUnchecked = schema.gameSystems.toSet()
        val effective = PlanningEngine.schemaApplyingUnchecked(schema, allUnchecked)
        val plan = PlanningEngine.finalize(
            schema = effective,
            confirmedDraft = PlanningEngine.build(schema),
            uncheckedModules = allUnchecked
        )
        assertTrue(plan.gameSystems.none { it in allUnchecked })
        assertEquals("核心玩法", plan.primarySystem)
        assertTrue(plan.excludedSystems.isEmpty())
        assertTrue(GamePrompt.planContext(plan).contains("未勾选任何游戏系统"))
        assertTrue(GamePrompt.planContext(plan).contains("以用户指令与对标游戏锚定为准"))
        assertFalse(GamePrompt.planContext(plan).contains("不要套用任何游戏模板"))
    }

    @Test
    fun `裸需求 planContext 注入知名游戏忠实度规则`() {
        val plan = PlanningEngine.build(GameSchema(), allowEmptySystems = true)
        val ctx = GamePrompt.planContext(plan)
        assertTrue(ctx.contains("未勾选任何游戏系统"))
        // 忠实度规则由生成上下文（buildToolContextMessage/buildGenerationMessages）注入
        assertTrue(GamePrompt.KNOWN_GAME_FIDELITY_RULE.contains("我的世界"))
        assertTrue(GamePrompt.KNOWN_GAME_FIDELITY_RULE.contains("禁止替换成另一种玩法的通用小游戏"))
    }

    @Test
    fun `重组时需要重新策划的模块为新增或本轮点名`() {
        val first = PlanningEngine.build(schemaOf("做一个塔防游戏，敌人按波次进攻"))
        val second = schemaOf("再加个商店经济")
        val touched = setOf("商店经济")
        val replan = PlanningEngine.modulesNeedingReplan(second, first, touched)
        assertTrue(replan.contains("商店经济"))
        assertFalse(replan.contains("塔防"))
    }
}
