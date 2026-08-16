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
        assertTrue(plan.acceptanceChecklist.any { it.contains("静态校验") })
        assertTrue(plan.complexityBudget.estimatedLines > 0)
    }

    @Test
    fun `P0-only 裁剪 P1P2`() {
        val plan = PlanningEngine.build(IntentEngine.infer("做一个塔防游戏，有商店和技能", null))
        val p0 = PlanningEngine.p0Only(plan)
        assertEquals(emptyList<String>(), p0.p1Features)
        assertEquals(emptyList<String>(), p0.p2Features)
        assertTrue(p0.acceptanceChecklist.any { it.contains("P0-only") })
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
