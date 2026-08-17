package com.gamewishingwell.agent

import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.llm.LlmClient
import com.gamewishingwell.llm.Protocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePlanningLlmTest {

    private class FakeLlmClient(private val reply: String) : LlmClient {
        override val protocol: Protocol = Protocol.OPENAI_COMPATIBLE

        override suspend fun streamChat(
            messages: List<ChatMessage>,
            onDelta: (String) -> Unit,
            onThinking: (String) -> Unit,
            onDone: () -> Unit
        ) {
            onDelta(reply)
            onDone()
        }
    }

    @Test
    fun `策划层用 LLM 阐述每个系统在具体游戏中的实现方式`() = runBlocking {
        val schema = RecognitionEngine.recognize("做一个黄金矿工游戏", null).gameSchema
        val reply = """
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

        val plan = PlanningEngine.draftWithLlm(schema, FakeLlmClient(reply))

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
        val schema = RecognitionEngine.recognize("做一个黄金矿工游戏", null).gameSchema
        val fallback = PlanningEngine.draftWithLlm(schema, FakeLlmClient("这不是 JSON"))

        val item = fallback.implementations.first { it.system == "道具" }
        assertTrue(item.methods.any { it.contains("黄金") })
        assertTrue(item.methods.any { it.contains("钩子前端") })
        assertTrue(fallback.acceptanceChecklist.any { it.contains("业务验收") })
    }

    @Test
    fun `策划层 LLM JSON 解析拒绝白名单外系统`() {
        val designs = PlanningEngine.parseLlmSystemDesigns(
            """{"systems":[{"system":"道具","implementation":"合法"},{"system":"外星科技","implementation":"非法"}]}"""
        )
        assertEquals(1, designs.size)
        assertEquals("道具", designs.single().system)
    }
}
