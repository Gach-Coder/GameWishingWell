package com.gamewishingwell.agent

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent Loop 运行细节日志（辅助调试材料，前端不展示）：
 * 每个用户回合一个独立日志文件，写在游戏文件夹的 log/ 子目录下——
 * 已保存游戏 filesDir/games/<id>/log/、草稿 filesDir/drafts/log/
 * （文件夹可视系统逐级浏览即可见）；文件名 = 回合开始时间，如 20260907_115400.log。
 *
 * 生命周期契约：
 * - 只能由回合入口创建与修改：[beginTurn] 挂在四类用户回合（发送消息/确认门确认/
 *   报障修复/再试一次）的统一起算点 markTurnStart，回合收尾（生成任务完成）经
 *   [endTurn] 关闭——回合外的零散日志一律丢弃，不再有全局 agent.log；
 * - 只能随游戏删除顺带删除：GameRepository.deleteGame / clearDraft 递归删除
 *   游戏文件夹（含 log/），没有任何独立的日志清理或轮转路径。
 *
 * 记录内容：每轮 LLM 调用、工具调用与观察、产品契约/沙箱校验细节、升级提示、
 * 退出原因等（含 UI 纪律禁止展示的错误原文，因此仅限本地文件）。
 * 写入是 best-effort：任何日志失败都被吞掉，绝不影响 Agent Loop 本身。
 */
class AgentLog {

    private val lock = Any()
    private val lineFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile
    private var current: File? = null

    /** 回合开始：在 [turnDir]（游戏文件夹的 log/ 目录）下创建本回合专属日志文件并打开。 */
    fun beginTurn(turnDir: File) {
        val file = runCatching {
            turnDir.mkdirs()
            uniqueLogFile(turnDir)
        }.getOrNull() ?: return
        synchronized(lock) { current = file }
    }

    /** 回合收尾：关闭当前文件；此后的日志写入被丢弃，等待下一回合的新文件。 */
    fun endTurn() {
        synchronized(lock) { current = null }
    }

    fun log(scope: String, message: String) {
        val f = current ?: return
        try {
            synchronized(lock) {
                f.appendText("${lineFmt.format(Date())} [$scope] $message\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // 日志失败不影响主流程
        }
    }

    /** 同一秒内连开两个回合（会话串行，理论不发生）时加序号避免覆盖。 */
    private fun uniqueLogFile(dir: File): File {
        val base = fileFmt.format(Date())
        var f = File(dir, "$base.log")
        var seq = 1
        while (f.exists()) f = File(dir, "${base}_${++seq}.log")
        return f
    }
}
