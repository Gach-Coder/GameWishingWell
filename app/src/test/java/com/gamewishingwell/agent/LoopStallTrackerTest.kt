package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoopStallTracker 单测：被驳回的完成宣告链（2 次决策指令/3 次熔断）、侦查空转
 * （24 轮熔断/每 8 轮催促）、连续无工具轮（兼容降级路由）与清零语义。
 * 行为基准来自 09-06 08:40 回合的真实病理：6 连空转烧 184k 字符（第 2 次起应注入
 * 决策指令、第 3 次应熔断，收敛为 ≤3 轮）。
 */
class LoopStallTrackerTest {

    @Test
    fun `被驳回宣告第2次注入决策指令第3次熔断`() {
        val t = LoopStallTracker()
        // 首次驳回 = 正常修复环入口，不打扰
        assertTrue(t.noteDeclarationRejected() is StallAction.None)
        // 第 2 次 = 病理确诊前兆，注入决策指令
        val steer = t.noteDeclarationRejected()
        assertTrue(steer is StallAction.Steer)
        assertEquals(2, (steer as StallAction.Steer).count)
        // 第 3 次 = 确诊熔断（确定性沙箱下文件未变则判决必同）
        val fuse = t.noteDeclarationRejected()
        assertTrue(fuse is StallAction.Fuse)
        assertTrue((fuse as StallAction.Fuse).internalReason.contains("被驳回的完成宣告"))
    }

    @Test
    fun `任何写入清零被驳回宣告链`() {
        val t = LoopStallTracker()
        t.noteDeclarationRejected()
        t.noteDeclarationRejected() // Steer
        assertTrue(t.noteToolRound(mutated = true) is StallAction.None) // 行动即出狱
        // 清零后重新从 None 开始计
        assertTrue(t.noteDeclarationRejected() is StallAction.None)
        assertTrue(t.noteDeclarationRejected() is StallAction.Steer)
    }

    @Test
    fun `侦查轮不清零被驳回宣告链`() {
        val t = LoopStallTracker()
        t.noteDeclarationRejected()
        t.noteDeclarationRejected() // Steer
        // 读取轮（readfile/listfiles）不是行动：读过不改再宣告仍属空转
        assertTrue(t.noteToolRound(mutated = false) is StallAction.None)
        assertTrue(t.noteDeclarationRejected() is StallAction.Fuse)
    }

    @Test
    fun `侦查空转沿用24轮熔断与每8轮催促`() {
        val t = LoopStallTracker()
        var nudges = 0
        var fuseAt = -1
        for (i in 1..LoopStallTracker.FUSE_RECON_ROUNDS) {
            when (t.noteToolRound(mutated = false)) {
                is StallAction.Steer -> {
                    nudges++
                    assertEquals(0, i % LoopStallTracker.NUDGE_RECON_EVERY)
                }
                is StallAction.Fuse -> fuseAt = i
                StallAction.None -> {}
            }
        }
        // 24 轮内催促出现在第 8/16 轮；第 24 轮熔断（沿用旧 idleRounds 阈值，语义变纯）
        assertEquals(2, nudges)
        assertEquals(LoopStallTracker.FUSE_RECON_ROUNDS, fuseAt)
    }

    @Test
    fun `写入清零侦查空转计数`() {
        val t = LoopStallTracker()
        repeat(23) { t.noteToolRound(mutated = false) }
        assertTrue(t.noteToolRound(mutated = true) is StallAction.None)
        // 清零后同样轮数内不再熔断
        repeat(23) { assertTrue(t.noteToolRound(mutated = false) !is StallAction.Fuse) }
    }

    @Test
    fun `连续无工具轮供兼容降级路由判定`() {
        val t = LoopStallTracker()
        t.noteDeclared()
        assertEquals(1, t.consecutiveToolless)
        t.noteDeclared()
        assertEquals(2, t.consecutiveToolless) // ≥2 触发 !mutatedOnce 降级判定
        // 任何工具轮（含只读）清零：readfile 夹在纯文本之间不算连续
        t.noteToolRound(mutated = false)
        assertEquals(0, t.consecutiveToolless)
    }
}
