package com.gamewishingwell.agent

import java.io.File

/**
 * 参照模板摘要器（③ 预回合摘要注入的"帮工"）：把一份模板游戏的全部源码交给
 * 独立的子代理 LLM 调用，产出 2~4KB 的技术摘要注入首生成回合的上下文——
 * 主循环每轮重发上下文，30KB 参照原文的真实成本 = 30KB × 轮次数（事故回合
 * promptChars 一路涨到 58K），摘要调用一次性且可缓存，成本约为其十分之一。
 *
 * 主 Agent 的改动面永远只有"多一段动态 prompt"（本模块的全部产出），工具、
 * 沙箱、侦查链一概不感知——这是该方案低风险的根源，新增维护点全部在内容
 * 生产端（本文件）。
 */
object GameReferenceDigest {

    /** 子代理输入的源码总量上限（字符）：超大游戏截断尾部并如实标注，防单次调用过载。 */
    const val MAX_INPUT_CHARS = 120_000

    /** 摘要输出上限（字符）：注入主循环的部分再截一次，摘要必须是"笔记"不是"副本"。 */
    const val MAX_DIGEST_CHARS = 4_000

    /** 子代理系统提示词：只陈述代码里真实存在的内容，禁止发明与贴大段代码。 */
    const val SUB_AGENT_SYSTEM = "你是游戏代码摘要器，为「即将开发同类新游戏的 AI」提炼一份可参照的技术笔记。"

    /** 子代理任务提示词（拼接游戏标题与全部源码）。 */
    fun subAgentUserPrompt(title: String, deliveredVersion: Int, filesText: String): String =
        "下面是模板游戏《$title》（第 $deliveredVersion 版，玩家甄选的高质量同类游戏）的完整源码。\n" +
            "请输出技术摘要（纯文本，1200 字以内），结构：\n" +
            "1. 架构地图：每个文件的职责与关键全局符号/导出；\n" +
            "2. 核心系统实现要点：世界生成/渲染/碰撞/输入/UI 各自的做法与关键函数名；\n" +
            "3. 平台契约习惯用法：__wwDebugState、GAME_CONFIG、断言套件、触控布局在本作里的写法；\n" +
            "4. 可复用模式与注意：最值得借鉴的 3~5 个做法；本作的明显短板（如有）。\n" +
            "纪律：只陈述代码里真实存在的内容，禁止发明；单段代码引用不超过 3 行；不输出游戏文案。\n\n" +
            filesText

    /** 源码组装（文件逐一带路径标题拼接；超总量上限截断并标注）。 */
    fun buildFilesText(files: Map<String, String>): String = buildString {
        var used = 0
        for ((path, content) in files.toSortedMap()) {
            val block = "\n===== $path =====\n$content\n"
            if (used + block.length > MAX_INPUT_CHARS) {
                append("\n（其余文件因总量上限省略）\n")
                break
            }
            append(block)
            used += block.length
        }
    }

    /** 摘要成品化：去围栏/首尾空白 + 截断到注入上限。空输出返回 null（视为无摘要）。 */
    fun sanitize(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```").substringAfter('\n', "")
            if (text.endsWith("```")) text = text.removeSuffix("```")
        }
        text = text.trim()
        if (text.isBlank()) return null
        if (text.length > MAX_DIGEST_CHARS) {
            text = text.take(MAX_DIGEST_CHARS) + "\n（摘要超长已截断）"
        }
        return text
    }

    /**
     * 摘要缓存（key = 游戏id + 文件清单指纹）：模板文件不变则永不重算；模板被
     * 保存/撤销改版后指纹变化自动失效。落盘为纯文本，损坏按缺失处理（重算即可）。
     */
    class Cache(private val dir: File) {
        private fun fileFor(key: String): File = File(dir.apply { mkdirs() }, "$key.md")

        fun read(key: String): String? = runCatching {
            val f = fileFor(key)
            if (f.isFile) f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } else null
        }.getOrNull()

        fun write(key: String, digest: String) {
            runCatching { fileFor(key).writeText(digest, Charsets.UTF_8) }
        }
    }
}
