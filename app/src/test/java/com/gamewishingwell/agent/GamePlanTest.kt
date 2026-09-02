package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePlanTest {

    @Test
    fun `策划层生成 P0P1P2 分层与验收清单`() {
        val intent = IntentEngine.infer("做一个打地鼠游戏", null)
        val plan = PlanningEngine.build(intent)
        assertTrue(plan.templateClass.contains("竖版"))
        assertTrue(plan.p0Features.isNotEmpty())
        assertTrue(plan.p1Features.isNotEmpty() || plan.p2Features.isNotEmpty())
        assertTrue(plan.acceptanceChecklist.any { it.contains("基础校验") })
        assertTrue(plan.acceptanceChecklist.none { it.contains("冒烟") })
        assertTrue(plan.complexityBudget.estimatedLines > 0)
    }

    @Test
    fun `策划草案与决策定稿共用同一套已确认玩法`() {
        val intent = IntentEngine.infer("做一个黄金矿工游戏", null)
        val draft = PlanningEngine.draft(intent)
        val finalized = PlanningEngine.finalize(intent)
        assertEquals(draft.gameSystems, finalized.gameSystems)
        assertEquals(draft.implementations, finalized.implementations)
        assertTrue(finalized.implementations.any { it.system == "道具" && it.layer == 0 })
    }

    @Test
    fun `局部修改旧游戏时继承上一版系统范围`() {
        val previous = PlanningEngine.build(IntentEngine.infer("做一个塔防游戏", null))
        val localModify = IntentEngine.infer("加个连击计分", "<html></html>")
        val merged = PlanningEngine.inheritExistingDesign(localModify, previous)
        assertEquals(previous.gameSystems, merged.gameSystems)

        val removeStore = IntentEngine.infer("不要商店经济", "<html></html>")
        val withoutStore = PlanningEngine.inheritExistingDesign(removeStore, previous)
        assertTrue("商店经济" !in withoutStore.gameSystems)
        assertTrue("塔防" in withoutStore.gameSystems)

        val previous3d = previous.copy(visualDimension = IntentSchema.DIMENSION_3D)
        val keep3d = PlanningEngine.inheritExistingDesign(
            IntentEngine.infer("加个连击计分", "<html></html>"),
            previous3d,
            "加个连击计分"
        )
        assertEquals(IntentSchema.DIMENSION_3D, keep3d.visualDimension)
    }

    @Test
    fun `策划层输出系统实现清单与排除清单`() {
        val intent = IntentEngine.infer("做一个2D竖版塔防游戏，不要商店经济", null)
        val plan = PlanningEngine.build(intent)
        val tower = plan.implementations.first { it.system == "塔防" }
        assertTrue(tower.methods.any { it.contains("防御塔") })
        assertTrue(tower.acceptanceBoundary.contains("基地生命"))
        // 排除清单是纯加法：只有用户文本明确说"不要"的系统才进禁止清单
        assertTrue("商店经济" in plan.excludedSystems)
        assertTrue("AI策略" in plan.gameSystems)
        // 未勾选/未提及的系统不做任何禁止
        assertTrue("音乐节奏" !in plan.excludedSystems)
        assertTrue("弹幕射击" !in plan.excludedSystems)
        assertTrue(plan.excludedApproaches.any { it.contains("3D") })
        assertTrue(PlanningEngine.toPrompt(plan).contains("implementations"))
        assertTrue(PlanningEngine.toPrompt(plan).contains("excluded_systems"))
        assertTrue(PlanningEngine.toPrompt(plan).contains("excluded_approaches"))
    }

    @Test
    fun `黄金矿工的具体道具实现会进入策划 Schema 与 prompt`() {
        val intent = IntentEngine.infer("做一个黄金矿工游戏", null)
        val plan = PlanningEngine.build(intent)
        val item = plan.implementations.first { it.system == "道具" }
        assertTrue(item.methods.any { it.contains("黄金") && it.contains("石头") && it.contains("炸弹") })
        assertTrue(item.methods.any { it.contains("钩子前端") && it.contains("触碰") })
        assertTrue(item.acceptanceBoundary.contains("钩子触碰抓取"))
        assertEquals(0, item.layer)
        assertTrue(plan.p0Features.any { it.contains("钩子前端与道具做圆形碰撞检测") })
        val prompt = PlanningEngine.toPrompt(plan)
        assertTrue(prompt.contains("黄金"))
        assertTrue(prompt.contains("炸弹"))
        assertTrue(prompt.contains("拾取判定"))
    }

    @Test
    fun `全部取消勾选时进入裸需求模式且不出现无关系统名`() {
        // 勾选只做加法不做减法：全不勾选 = 用户原话直接交给 Agent，
        // 不套用预设系统清单、不硬排除任何系统，生成照常进行。
        val intent = IntentEngine.infer("做一个塔防游戏", null)
        val schema = intent.toGameSchema()
        val allUnchecked = schema.gameSystems.toSet()
        val effective = PlanningEngine.schemaApplyingUnchecked(schema, allUnchecked)
        val plan = PlanningEngine.finalize(
            schema = effective,
            confirmedDraft = PlanningEngine.build(schema),
            uncheckedModules = allUnchecked
        )
        assertTrue(plan.gameSystems.none { it in allUnchecked })
        assertEquals("核心玩法", plan.primarySystem)
        assertTrue(PlanningEngine.toPrompt(plan).contains("primary_system:核心玩法"))
        // 裸需求模式：未勾选的系统不进硬排除清单，其余白名单系统也不得整体封禁
        assertTrue(plan.excludedSystems.isEmpty())
        assertTrue(GamePrompt.planContext(plan).contains("未勾选任何游戏系统"))
        assertTrue(GamePrompt.planContext(plan).contains("以用户指令为准"))
    }

}

class QualityGateTest {
    @Test
    fun `静态错误不得翻案`() {
        val plan = PlanningEngine.build(IntentEngine.infer("做一个打地鼠游戏", null))
        val html = "<html><body><canvas id='g'></canvas><script>var x = ;</script></body></html>"
        val report = GameValidator.validate(html)
        val verdict = QualityGate.evaluate(report, plan, html)
        assertTrue(!verdict.pass)
        assertTrue(verdict.fails.any { it.item.contains("校验报错") })
    }
}
