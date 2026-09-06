package com.gamewishingwell.agent

/**
 * 工具模式 Agent Loop 的失速跟踪器：把"轮次行为异常"类判定收敛为单一决策出口，
 * 替代此前散落在循环体里的并行计数器（idleRounds 侦查空转、toollessRounds 兼容降级路由）。
 *
 * 管辖三类行为异常：
 * 1. 被驳回的完成宣告链（本类的核心新增）：模型反复"宣告完成 → 验证失败 → 不做任何
 *    修改再宣告"——沙箱为确定性执行，文件不变则判决必相同，第 2 次被驳回起注入
 *    决策强制指令（修改文件 / 修改断言并说明理由 / 再空转即终止 三选一），
 *    第 3 次熔断。实测该病理曾连续 6 轮空转、烧 184k 字符分析文本（08:40 回合），
 *    旧机制（同签名升级提示、24 轮无修改熔断）均无法接住；
 * 2. 侦查空转：连续多轮只调用读取类工具、零写入——沿用旧阈值（24 轮熔断、每 8 轮催促）；
 * 3. 连续无工具轮：供 !mutatedOnce 的兼容降级路由判定（模型不会 function calling）。
 *
 * 清零语义：任何写入（Mutated）清零被驳回宣告链与侦查计数；读取轮（Recon）只清零
 * 连续无工具轮、不清零被驳回宣告链——读过文件但不修改就再宣告，仍属空转。
 * 回合局部状态，每回合新建（与 orientationStrikes 同生命周期）。
 */
class LoopStallTracker {

    /** 连续无工具轮（含一切分支形态）。任何工具轮清零；供兼容降级路由读取。 */
    var consecutiveToolless: Int = 0
        private set

    private var rejectedDeclarations = 0
    private var reconRounds = 0

    /** 任意无工具轮（进入无工具分支即计）。被驳回的完成宣告轮已在分支顶部计入本计数。 */
    fun noteDeclared() {
        consecutiveToolless++
    }

    /**
     * 无工具轮且验证确实运行并失败（沙箱失败带回传错误 / 静态契约有 error）。
     * 第 [STEER_REJECTED_DECLARATIONS] 次返回决策指令，第 [FUSE_REJECTED_DECLARATIONS] 次熔断。
     */
    fun noteDeclarationRejected(): StallAction {
        rejectedDeclarations++
        return when {
            rejectedDeclarations >= FUSE_REJECTED_DECLARATIONS ->
                StallAction.Fuse("连续 $rejectedDeclarations 次被驳回的完成宣告（宣告-驳回-零修改空转熔断）")
            rejectedDeclarations >= STEER_REJECTED_DECLARATIONS ->
                StallAction.Steer(rejectedDeclarations)
            else -> StallAction.None
        }
    }

    /** 工具轮分类入口。[mutated] = 本轮发生任何工作区写入（含 scenarios.json）。 */
    fun noteToolRound(mutated: Boolean): StallAction {
        consecutiveToolless = 0
        if (mutated) {
            rejectedDeclarations = 0
            reconRounds = 0
            return StallAction.None
        }
        reconRounds++
        return when {
            reconRounds >= FUSE_RECON_ROUNDS ->
                StallAction.Fuse("连续 $reconRounds 轮无文件修改（侦查空转熔断）")
            reconRounds % NUDGE_RECON_EVERY == 0 ->
                StallAction.Steer(reconRounds)
            else -> StallAction.None
        }
    }

    companion object {
        /** 被驳回的完成宣告：第 2 次注入决策强制指令（首次驳回属正常修复环入口）。 */
        const val STEER_REJECTED_DECLARATIONS = 2

        /** 被驳回的完成宣告：第 3 次熔断（确定性沙箱下文件未变则判决必同，3 次即确诊）。 */
        const val FUSE_REJECTED_DECLARATIONS = 3

        /** 侦查空转熔断阈值（沿用旧 idleRounds 的 24 轮，语义变纯：只计读取轮）。 */
        const val FUSE_RECON_ROUNDS = 24

        /** 侦查空转催促间隔（沿用旧值的每 8 轮）。 */
        const val NUDGE_RECON_EVERY = 8
    }
}

/** 失速决策：无 / 注入引导消息（[Steer.count] 语义由调用点的策略上下文解释）/ 熔断。 */
sealed interface StallAction {
    object None : StallAction
    data class Steer(val count: Int) : StallAction
    data class Fuse(val internalReason: String) : StallAction
}
