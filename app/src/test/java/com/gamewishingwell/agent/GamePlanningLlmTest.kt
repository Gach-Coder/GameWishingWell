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
            tools: List<com.gamewishingwell.llm.ToolSpec>
        ): com.gamewishingwell.llm.LlmResponse {
            onDelta(reply)
            onDone()
            return com.gamewishingwell.llm.LlmResponse(text = reply)
        }
    }

    /** 记录每次调用的 user 提示词，用于断言“只为需重策划的 module 调用 LLM”。 */
    private class RecordingLlmClient(var reply: String = "") : LlmClient {
        override val protocol: Protocol = Protocol.OPENAI_COMPATIBLE
        val prompts = mutableListOf<String>()

        override suspend fun streamChat(
            messages: List<ChatMessage>,
            onDelta: (String) -> Unit,
            onThinking: (String) -> Unit,
            onDone: () -> Unit,
            tools: List<com.gamewishingwell.llm.ToolSpec>
        ): com.gamewishingwell.llm.LlmResponse {
            prompts += messages.filter { it.role == "user" }.joinToString("\n") { it.content }
            if (reply.isNotEmpty()) onDelta(reply)
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

    @Test
    fun `重组时删除module直接移除且不调用LLM`() = runBlocking {
        val schema1 = RecognitionEngine.recognize("做一个黄金矿工游戏，钩子物理摆动，有道具和商店经济", null).gameSchema
        val plan1 = PlanningEngine.draftWithLlm(schema1, FakeLlmClient(goldMinerReply))

        val second = RecognitionEngine.recognize("不要道具", schema1)
        val llm = RecordingLlmClient()
        val plan2 = PlanningEngine.reorganizeWithLlm(
            schema = second.gameSchema,
            previousPlan = plan1,
            touchedModules = second.appliedPatch!!.let {
                (it.gameSystems + it.reAddSystems).distinct()
            },
            llm = llm
        )

        // 删除 module：直接移除，无 LLM 调用
        assertTrue(llm.prompts.isEmpty())
        assertTrue("道具" !in plan2.gameSystems)
        assertTrue(plan2.implementations.none { it.system == "道具" })
        assertTrue("道具" in plan2.excludedSystems)
        // 未涉及的 module 沿用上一版 LLM 策划结果
        assertEquals(
            plan1.implementations.first { it.system == "物理" },
            plan2.implementations.first { it.system == "物理" }
        )
        assertEquals(
            plan1.implementations.first { it.system == "商店经济" },
            plan2.implementations.first { it.system == "商店经济" }
        )
    }

    @Test
    fun `重组时新增或修改的module由LLM重新策划其余沿用上一版`() = runBlocking {
        val schema1 = RecognitionEngine.recognize("做一个黄金矿工游戏，钩子物理摆动，有道具和商店经济", null).gameSchema
        val plan1 = PlanningEngine.draftWithLlm(schema1, FakeLlmClient(goldMinerReply))

        val second = RecognitionEngine.recognize("加一个技能系统，大招要炫酷", schema1)
        val llm = RecordingLlmClient(
            """{"systems":[{"system":"技能","implementation":"玩家可释放范围大招，冷却10秒，命中敌人有明显反馈。",
               "methods":["大招范围伤害","冷却10秒"],"acceptanceBoundary":"技能可主动释放且有冷却","layer":1}]}"""
        )
        val plan2 = PlanningEngine.reorganizeWithLlm(
            schema = second.gameSchema,
            previousPlan = plan1,
            touchedModules = second.appliedPatch!!.let {
                (it.gameSystems + it.reAddSystems).distinct()
            },
            llm = llm,
            currentUserRequest = "加一个技能系统，大招要炫酷"
        )

        // 只为新增/修改的 module 调一次 LLM，且提示词只把该子集列为待策划项
        assertEquals(1, llm.prompts.size)
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("技能"))
        assertTrue(Regex("<modules_to_plan>\\s*技能\\s*</modules_to_plan>").containsMatchIn(prompt))
        assertFalse(prompt.contains("- 商店经济：种子方法"))
        // 修改 module 的范围提示
        assertTrue(prompt.contains("其余系统沿用已确认方案"))

        // 新增 module 使用本轮 LLM 策划结果
        val skill = plan2.implementations.first { it.system == "技能" }
        assertTrue(skill.playerFacing.contains("大招"))
        assertEquals("技能可主动释放且有冷却", skill.acceptanceBoundary)
        // 未涉及 module 原样沿用上一版 LLM 策划
        assertEquals(
            plan1.implementations.first { it.system == "道具" },
            plan2.implementations.first { it.system == "道具" }
        )
        assertEquals(plan1.gameSystems + listOf("技能"), plan2.gameSystems)
    }

    @Test
    fun `修改已有module时只重新策划被点名的module`() = runBlocking {
        val schema1 = RecognitionEngine.recognize("做一个黄金矿工游戏，钩子物理摆动，有道具和商店经济", null).gameSchema
        val plan1 = PlanningEngine.draftWithLlm(schema1, FakeLlmClient(goldMinerReply))

        val second = RecognitionEngine.recognize("把道具改成只有黄金和石头", schema1)
        val llm = RecordingLlmClient(
            """{"systems":[{"system":"道具","implementation":"矿场里只有黄金和石头：越大越值钱，石头更廉价。",
               "methods":["黄金/石头随机分布","钩子前端触碰即拾取"],"acceptanceBoundary":"两类道具均可被抓取且分值正确","layer":0}]}"""
        )
        val plan2 = PlanningEngine.reorganizeWithLlm(
            schema = second.gameSchema,
            previousPlan = plan1,
            touchedModules = second.appliedPatch!!.let {
                (it.gameSystems + it.reAddSystems).distinct()
            },
            llm = llm,
            currentUserRequest = "把道具改成只有黄金和石头"
        )

        assertEquals(1, llm.prompts.size)
        assertTrue(Regex("<modules_to_plan>\\s*道具\\s*</modules_to_plan>").containsMatchIn(llm.prompts.single()))
        val item = plan2.implementations.first { it.system == "道具" }
        assertTrue(item.playerFacing.contains("只有黄金和石头"))
        assertFalse(item.playerFacing.contains("炸弹"))
        // 未点名 module 沿用上一版
        assertEquals(
            plan1.implementations.first { it.system == "物理" },
            plan2.implementations.first { it.system == "物理" }
        )
    }

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
