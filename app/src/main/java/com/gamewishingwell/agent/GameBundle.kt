package com.gamewishingwell.agent

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element

/**
 * 多文件游戏的运行前合并管道：入口 index.html 以相对路径引用工作区内的 JS/CSS
 * 文本文件（如 js/main.js、css/style.css），加载前在此内联为单个自包含页面——
 * 真实游戏页（GameScreen）与沙箱（冒烟测试）共用本管道，运行行为与单文件游戏
 * 完全一致，"离线自包含、无外部资源"的产品承诺不变。
 *
 * 职责边界：
 * - 只处理文本资产（script[src] / link[rel=stylesheet][href]）；二进制（图片/音频）
 *   仍禁止本地引用（无法文本内联，由 GameValidator 判错）；
 * - [read] 回调负责路径守卫（必须限定在游戏目录内，如 GameFileWorkspace.resolve）
 *   与内容读取；返回 null 的引用保持原样（留给校验器报"文件不存在"或由沙箱暴露）；
 * - 不解析 ES module 依赖图：import/export 由校验器禁止（多文件请用普通
 * script 按顺序加载，全局共享——与 AI 生成游戏的经典脚本形态一致）。
 */
object GameBundle {

    fun inline(entryHtml: String, read: (String) -> String?): String {
        if (entryHtml.isBlank()) return entryHtml
        val doc = runCatching { Jsoup.parse(entryHtml) }.getOrNull() ?: return entryHtml

        var changed = false
        // <script src="相对路径"> → 内联脚本内容（保持元素顺序，加载语义不变）
        doc.select("script[src]").forEach { el ->
            val src = el.attr("src").trim()
            if (!isLocalRef(src)) return@forEach
            val content = read(src) ?: return@forEach
            el.removeAttr("src")
            el.removeAttr("defer")
            el.removeAttr("async")
            el.empty()
            el.appendChild(DataNode(escapeScriptClose(content)))
            changed = true
        }
        // <link rel="stylesheet" href="相对路径"> → 内联样式
        doc.select("link[rel=stylesheet][href]").forEach { el ->
            val href = el.attr("href").trim()
            if (!isLocalRef(href)) return@forEach
            val content = read(href) ?: return@forEach
            val style = Element("style")
            style.appendChild(DataNode(content))
            el.replaceWith(style)
            changed = true
        }
        if (!changed) return entryHtml
        doc.outputSettings().prettyPrint(false)
        return doc.outerHtml()
    }

    /**
     * 相对本地引用判定：排除协议 URL（http/https/file/data 等）、协议相对（//）、
     * 片段（#）与空值——这些不走文件读取（外部资源由校验器另行判罚）。
     */
    fun isLocalRef(url: String): Boolean =
        url.isNotEmpty() && !url.startsWith("//") && !url.startsWith("#") && !url.startsWith("data:") &&
            !Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)

    /**
     * 内联 JS 的标签闭合转义：内容中出现字面 `</script` 会提前终止脚本标签，
     * 替换为 `<\/script`——在字符串/模板字面量/正则/注释里均为等价合法写法
     * （与平台内置引擎"已验证可内联无 </script 字面量"同一条约定）。
     */
    private fun escapeScriptClose(js: String): String = js.replace("</script", "<\\/script")
}
