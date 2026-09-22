package com.gamewishingwell.agent

import com.gamewishingwell.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最近用户指令窗口（RecentInstructionWindow）纯函数单测：
 * 轮划分（assistant 分界/确认卡不分界/末段未闭合=本轮丢弃）、交付标记
 * （成功回执才标，聊天/失败不标）、截断与省略计数、单行化。
 */
class RecentInstructionWindowTest {

    private val deliveryReply = "游戏已制作完成，并通过沙箱运行验证。（本次 Agent Loop 共 5 轮，耗时 1分30秒）"
    private val chatReply = "这个平台生成的是单机网页游戏，暂不支持联机。（耗时 3秒）"

    private fun user(text: String) = ChatMessage("user", text)
    private fun assistant(text: String) = ChatMessage("assistant", text)
    private fun confirmCard() = ChatMessage(ChatMessage.ROLE_CONFIRM_CARD, "{}")

    @Test
    fun `空会话返回 null`() {
        assertNull(RecentInstructionWindow.render(emptyList()))
    }

    @Test
    fun `只有本轮指令时返回 null`() {
        assertNull(RecentInstructionWindow.render(listOf(user("做一个打地鼠小游戏"))))
    }

    @Test
    fun `上一轮已交付的指令带已交付标记`() {
        val block = RecentInstructionWindow.render(
            listOf(user("做一个打地鼠小游戏"), assistant(deliveryReply), user("加连击计分"))
        )!!
        assertTrue(block.contains("1. 做一个打地鼠小游戏（已交付）"))
        assertTrue(!block.contains("加连击计分"))
    }

    @Test
    fun `聊天回合的指令不带交付标记`() {
        val block = RecentInstructionWindow.render(
            listOf(user("它能联机吗"), assistant(chatReply), user("那就做一个排行榜"))
        )!!
        assertTrue(block.contains("1. 它能联机吗"))
        assertTrue(!block.contains("已交付"))
    }

    @Test
    fun `确认门文本修正是同轮以最后一条为有效指令`() {
        // 首轮：请求 → 卡片 → 文本修正 → 卡片（锁定）→ 交付；修正轮的磋商消息不各自成轮
        val block = RecentInstructionWindow.render(
            listOf(
                user("做一个塔防游戏"),
                confirmCard(),
                user("不要商店系统"),
                confirmCard(),
                assistant(deliveryReply),
                user("加个暂停按钮")
            )
        )!!
        // 有效指令是"不要商店系统"（同轮最后一条），原始请求被修正覆盖不单列
        assertTrue(block.contains("1. 不要商店系统（已交付）"))
        assertTrue(!block.contains("做一个塔防游戏"))
        assertTrue(!block.contains("加个暂停按钮"))
    }

    @Test
    fun `修复轮指令作为本轮指令不进窗口`() {
        // fixWithError 会把修复指令以 user 消息追加（末段未闭合），符合"本轮指令"规则
        val block = RecentInstructionWindow.render(
            listOf(
                user("做一个贪吃蛇"), assistant(deliveryReply),
                user("游戏运行时报错，请精准修复该错误……")
            )
        )!!
        assertTrue(block.contains("1. 做一个贪吃蛇（已交付）"))
        assertTrue(!block.contains("报错"))
    }

    @Test
    fun `被后续指令推翻的历史指令原样保留由覆盖声明裁决`() {
        val block = RecentInstructionWindow.render(
            listOf(
                user("血条改成红色"), assistant(deliveryReply),
                user("还是改回绿色"), assistant(deliveryReply),
                user("加个Boss")
            )
        )!!
        assertTrue(block.contains("1. 血条改成红色（已交付）"))
        assertTrue(block.contains("2. 还是改回绿色（已交付）"))
    }

    @Test
    fun `超长指令截断到上限并单行化`() {
        val long = "修复：" + "报错内容x".repeat(200) + "\n第二行"
        // 用聊天回执收尾（无"已交付"标记），断言纯截断长度：1. + 500 + 省略号
        val block = RecentInstructionWindow.render(
            listOf(user(long), assistant(chatReply), user("下一轮"))
        )!!
        val line = block.lineSequence().first { it.startsWith("1.") }
        assertEquals(500 + "1. ".length + "…".length, line.length)
        assertTrue(!line.contains('\n'))
    }

    @Test
    fun `超过窗口大小时显示省略计数`() {
        val messages = buildList {
            for (i in 1..RecentInstructionWindow.SIZE + 2) {
                add(user("指令$i"))
                add(assistant(deliveryReply))
            }
            add(user("本轮指令"))
        }
        val block = RecentInstructionWindow.render(messages)!!
        // 最早的 2 条被省略，窗口内从指令 3 开始且相对编号从 1 起
        assertTrue(!block.contains("1. 指令1"))
        assertTrue(!block.contains("1. 指令2"))
        assertTrue(block.contains("1. 指令3"))
        assertTrue(block.contains("${RecentInstructionWindow.SIZE}. 指令${RecentInstructionWindow.SIZE + 2}"))
        assertTrue(block.contains("（更早的 2 条指令已省略）"))
    }
}
