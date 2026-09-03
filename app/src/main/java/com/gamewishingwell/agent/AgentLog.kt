package com.gamewishingwell.agent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent Loop 运行细节日志：只写入应用私有目录（filesDir/agent/logs/agent.log），
 * 作为 adb pull 的辅助调试材料——不在前端展示、不进入任何用户可见文案。
 * 记录内容：每轮 LLM 调用、工具调用与观察、产品契约/沙箱校验细节、升级提示、
 * 退出原因等（含 UI 纪律禁止展示的错误原文，因此仅限本地文件）。
 *
 * 写入是 best-effort：任何日志失败都被吞掉，绝不影响 Agent Loop 本身。
 * 单文件 2MB 轮转，保留 3 份历史（agent.log.1~3）。
 */
object AgentLog {

    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val KEEP_ROTATED = 3

    @Volatile
    private var dir: File? = null
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) = initDir(File(context.filesDir, "agent/logs"))

    /** 测试/JVM 注入目录。 */
    fun initDir(directory: File) {
        directory.mkdirs()
        dir = directory
    }

    fun log(scope: String, message: String) {
        val d = dir ?: return
        try {
            synchronized(lock) {
                val f = File(d, "agent.log")
                if (f.length() > MAX_BYTES) rotate(d)
                f.appendText("${fmt.format(Date())} [$scope] $message\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // 日志失败不影响主流程
        }
    }

    private fun rotate(d: File) {
        ((KEEP_ROTATED - 1) downTo 1).forEach { i ->
            val from = File(d, "agent.log.$i")
            val to = File(d, "agent.log.${i + 1}")
            if (from.exists()) from.renameTo(to)
        }
        File(d, "agent.log").renameTo(File(d, "agent.log.1"))
    }

    /** 日志目录（供文档/说明 adb pull 路径用）。 */
    fun dirPath(): String? = dir?.absolutePath
}
