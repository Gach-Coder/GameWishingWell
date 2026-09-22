package com.gamewishingwell.agent

import com.gamewishingwell.data.ChatMessage

/**
 * 最近用户指令窗口（上下文最小化下的局部连续性通道）：
 *
 * 逐字保留最近 [SIZE] 条"有效用户指令"进入 Agent 上下文。轮的划分：两条 assistant
 * 消息之间的连续 user 消息视为同一轮（确认门的文本修正属同轮磋商，以最后一条为
 * 有效指令），末段未闭合的 user 消息是本轮指令——它已单独作为"用户指令"注入，
 * 不进窗口。四种回合入口（发送消息/确认门确认/报障修复/再试一次）均以 user 消息
 * 收尾会话消息流，该规则对全部入口成立。
 *
 * 设计约束：
 * - 纯确定性（无 LLM、无幻觉通道）：窗口是会话消息的运行时视图，随 messages
 *   持久化天然重启安全、随撤销截断天然回滚、随 GameAgent 实例天然多会话隔离；
 * - 交付标记只标"可确证"的：该轮之后首条 assistant 消息含 [TURN_DELIVERED_MARK]
 *   （成功回执）标"（已交付）"；失败/中断/聊天轮不标——failTurn 不追加 assistant
 *   消息、聊天与失败回执无结构化区分标记，宁缺毋假；
 * - 头部携带覆盖声明：较早指令可能已被后续指令推翻或已体现在代码中，以最新指令
 *   与 readfile 所见现状为准——防被推翻/未交付的旧指令构成误导。
 */
object RecentInstructionWindow {
    /**
     * 窗口大小：从 10 起步（本产品指令平均几十字，30 轮全量也仅 ~1.5K token，
     * 不足 system prompt 的一半）——按实测的误执行/引用信号再收窄或放宽。
     */
    const val SIZE = 10

    /** 单条指令截断上限：防超长报障/需求文本撑爆窗口。 */
    private const val ITEM_MAX_CHARS = 500

    /** 成功回执标记：窗口的"已交付"判定与交付结算（GameAgent）共用同一来源。 */
    const val TURN_DELIVERED_MARK = "本次 Agent Loop 共"

    /** 渲染注入块；无历史指令可展示时返回 null（调用方跳过注入）。 */
    fun render(messages: List<ChatMessage>): String? {
        val prior = effectiveInstructions(messages)
        if (prior.isEmpty()) return null
        val windowed = prior.takeLast(SIZE)
        val omitted = prior.size - windowed.size
        return buildString {
            append("【此前用户指令（按时间先后排列；较早的指令可能已被后续指令推翻或已体现在当前代码中，以最新指令与 readfile 所见代码现状为准）】\n")
            windowed.forEachIndexed { i, item ->
                append(i + 1).append(". ").append(singleLine(item.text))
                if (item.delivered) append("（已交付）")
                append('\n')
            }
            if (omitted > 0) append("（更早的 $omitted 条指令已省略）")
        }
    }

    private data class TurnInstruction(val text: String, val delivered: Boolean)

    /**
     * 有效指令序列（不含本轮）：runs 以 assistant 消息分界（confirm_card 不分界，
     * 它是确认门磋商的一部分而非回合结束）；每段 run 的最后一条 user 消息为该轮
     * 有效指令，交付判定看其后首条 assistant 消息；末段未闭合 run 是本轮指令，丢弃。
     */
    private fun effectiveInstructions(messages: List<ChatMessage>): List<TurnInstruction> {
        val turns = mutableListOf<TurnInstruction>()
        var pending: String? = null
        for (msg in messages) {
            when {
                msg.isUser -> pending = msg.content
                msg.role == "assistant" -> {
                    pending?.let { turns += TurnInstruction(it, msg.content.contains(TURN_DELIVERED_MARK)) }
                    pending = null
                }
                // system/tool/confirm_card 不参与分界与内容
            }
        }
        return turns
    }

    /** 单行化（换行/连续空白折叠为空格）+ 截断，保持编号列表可读。 */
    private fun singleLine(text: String): String {
        val collapsed = text.trim().replace(Regex("\\s+"), " ")
        return if (collapsed.length <= ITEM_MAX_CHARS) collapsed
        else collapsed.take(ITEM_MAX_CHARS) + "…"
    }
}
