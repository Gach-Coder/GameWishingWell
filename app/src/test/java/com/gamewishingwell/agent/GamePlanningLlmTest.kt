package com.gamewishingwell.agent

import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.llm.LlmClient
import com.gamewishingwell.llm.Protocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePlanningLlmTest {

    private class FakeLlmClient(private val reply: String) : LlmClient {
        override val protocol: Protocol = Protocol.OPENAI_COMPATIBLE

        override suspend fun streamChat(
            messages: List<ChatMessage>,
            onDelta: (String) -> Unit,
            onThinking: (String) -> Unit,
            onDone: () -> Unit,
            tools: List<com.gamewishingwell.llm.ToolSpec>,
            onToolCallDelta: (Int) -> Unit
        ): com.gamewishingwell.llm.LlmResponse {
            onDelta(reply)
            onDone()
            return com.gamewishingwell.llm.LlmResponse(text = reply)
        }
    }

    private val goldMinerReply = """
        ```json
        {
          "systems": [
            {
              "system": "道具",
              "implementation": "矿场里会出现黄金、石头和炸弹：黄金和石头越大越值钱，石头更廉价但更重，炸弹抓上来会倒计时爆炸。",
              "methods": ["黄金/石头/炸弹按比例随机分布", "钩子前端触碰道具即拾取", "炸弹倒计时结束后扣分"],
              "acceptanceBoundary": "三类道具均可被抓取，分值与爆炸惩罚正确",
              "layer": 0
            },
            {
              "system": "物理",
              "implementation": "钩子会左右摆动，点击后伸出并原路收回，重物回收更慢。",
              "methods": ["钩子绕支点摆动", "点击后沿当前角度伸出", "重物回收速度减半"],
              "acceptanceBoundary": "轻重物回收速度差异明显",
              "layer": 0
            },
            {
              "system": "商店经济",
              "implementation": "每关前后可以买炸药、力量药水和幸运草。",
              "methods": ["金币购买三种增益道具", "炸药可炸掉当前钩中重物"],
              "acceptanceBoundary": "购买扣费正确且增益生效",
              "layer": 1
            }
          ]
        }
        ```json
    """.trimIndent()

    @Test
    fun `策划层用 LLM 阐述每个系统在具体游戏中的实现方式`() = runBlocking {
        val schema = RecognitionEngine.recognize("做一个黄金矿工游戏，钩子物理摆动，有道具和商店经济", null).gameSchema
        val plan = PlanningEngine.draftWithLlm(schema, FakeLlmClient(goldMinerReply))

        assertEquals(schema.gameSystems, plan.gameSystems)
        val item = plan.implementations.first { it.system == "道具" }
        assertTrue(item.playerFacing.contains("黄金"))
        assertTrue(item.playerFacing.contains("石头"))
        assertTrue(item.playerFacing.contains("炸弹"))
        assertTrue(item.methods.any { it.contains("钩子前端") })
        assertEquals("三类道具均可被抓取，分值与爆炸惩罚正确", item.acceptanceBoundary)

        val physics = plan.implementations.first { it.system == "物理" }
        assertTrue(physics.playerFacing.contains("重物回收更慢"))
        assertTrue(plan.p0Features.any { it.contains("钩子前端") })

        val prompt = PlanningEngine.toPrompt(plan)
        assertTrue(prompt.contains("玩家说明：矿场里会出现黄金"))
        assertTrue(prompt.contains("黄金"))
        assertTrue(prompt.contains("炸弹"))
    }

    @Test
    fun `策划层 LLM 输出非法时回退静态模板实现`() = runBlocking {
        val schema = RecognitionEngine.recognize("做一个黄金矿工游戏，钩子物理摆动，有道具和商店经济", null).gameSchema
        val fallback = PlanningEngine.draftWithLlm(schema, FakeLlmClient("这不是 JSON"))

        // 对标模板种子已删除：回退到系统矩阵的通用实现（不再有黄金矿工专属文案）
        val item = fallback.implementations.first { it.system == "道具" }
        assertTrue(item.methods.any { it.contains("道具") || it.contains("拾取") })
        assertTrue(item.methods.isNotEmpty())
        assertTrue(fallback.acceptanceChecklist.any { it.contains("业务验收") })
    }

    @Test
    fun `策划层 LLM JSON 解析归一化系统名且清洗非法名`() {
        // 白名单从过滤器降级为归一化器：合法自造系统（领域知识的主要表达通道）保留，
        // 同义变体收敛，非法名（标点/超长）仍拒绝。
        val designs = PlanningEngine.parseLlmSystemDesigns(
            """{"systems":[{"system":"道具","implementation":"合法"},{"system":"外星科技","implementation":"自造保留"},{"system":"建造","implementation":"归一"},{"system":"打怪,升级","implementation":"非法"}]}"""
        )
        assertEquals(3, designs.size)
        assertTrue(designs.any { it.system == "道具" })
        assertTrue(designs.any { it.system == "外星科技" })
        assertTrue(designs.any { it.system == "自由建造" })
        assertFalse(designs.any { it.system.contains(",") })
    }

    // 注：旧确认门"重组只重策划变动 module"的管线（reorganizeWithLlm）从未接线，
    // 已随死代码移除——现行为是重组卡片重建后于确认时统一定稿（draftWithLlm 全量策划），
    // 由下方 finalize/取消勾选用例覆盖。

    @Test
    fun `确认时取消勾选的module移出范围不视作排除且不注入prompt`() = runBlocking {
        val schema = RecognitionEngine.recognize("做一个黄金矿工游戏，钩子物理摆动，有道具和商店经济", null).gameSchema
        val draft = PlanningEngine.draftWithLlm(schema, FakeLlmClient(goldMinerReply))

        val applied = PlanningEngine.schemaApplyingUnchecked(schema, setOf("物理"))
        val plan = PlanningEngine.finalize(
            schema = applied,
            confirmedDraft = draft,
            uncheckedModules = setOf("物理")
        )

        // 未勾选 module 移出实现范围，但不进入"玩家明确排除"清单
        assertTrue("物理" in applied.uncheckedSystems)
        assertFalse("物理" in applied.excludedSystems)
        assertFalse("物理" in plan.gameSystems)
        assertTrue(plan.implementations.none { it.system == "物理" })
        assertFalse("物理" in plan.excludedSystems)
        assertTrue(plan.p0Features.none { it.startsWith("物理：") })
        assertTrue(plan.acceptanceChecklist.none { it.contains("业务验收：物理") })

        // Agent Loop prompt 中完全不含未勾选 module 的任何痕迹（无实现项也无禁令）
        val prompt = PlanningEngine.toPrompt(plan)
        assertFalse(prompt.contains("物理"))

        // 其余 module 保留确认草案中的 LLM 策划文本
        val item = plan.implementations.first { it.system == "道具" }
        assertTrue(item.playerFacing.contains("炸弹"))
    }

    @Test
    fun `取消勾选的module不会被模板自动复活且文本点名可恢复`() {
        val first = RecognitionEngine.recognize("做一个黄金矿工游戏，钩子物理摆动，有道具和商店经济", null).gameSchema
        val confirmed = PlanningEngine.schemaApplyingUnchecked(first, setOf("物理"))
        assertFalse("物理" in confirmed.gameSystems)

        // 下一轮修改：gold_miner 模板建议含物理，但未勾选 module 不得被模板加回
        val next = RecognitionEngine.recognize("加一个技能系统，大招要炫酷", confirmed)
        assertFalse("物理" in next.gameSchema.gameSystems)
        assertTrue("技能" in next.gameSchema.gameSystems)
        assertTrue("物理" in next.gameSchema.uncheckedSystems)

        // 文本点名“加回物理”自动恢复到实现范围并移出 unchecked
        val back = RecognitionEngine.recognize("把物理加回来", next.gameSchema)
        assertTrue("物理" in back.gameSchema.gameSystems)
        assertFalse("物理" in back.gameSchema.uncheckedSystems)
    }
}
