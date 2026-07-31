package com.gamewishingwell.agent

/**
 * 渲染前的 HTML 增强：向 AI 生成的游戏注入 JS 错误捕获器，
 * 把 window.onerror / unhandledrejection 转发到 console.error，
 * 这样 WebView 的 onConsoleMessage 一定能收到运行时报错（含行号），
 * 用户才能看到"游戏运行出错"覆盖层并一键让 AI 修复。
 *
 * 只影响 WebView 里渲染的副本，磁盘上保存的原始 HTML 保持不动。
 */
object HtmlEnhancer {

    private val PRELUDE = """
    <script>
    (function(){
      function report(msg){ try { console.error('[游戏错误] ' + msg); } catch(e){} }
      window.addEventListener('error', function(e){
        if (e && e.message) report(e.message + ' @ ' + (e.filename || '') + ':' + (e.lineno || 0));
      });
      window.addEventListener('unhandledrejection', function(e){
        var r = e && e.reason;
        report('Promise 未处理: ' + (r && r.message ? r.message : String(r)));
      });
    })();
    </script>
    """.trimIndent()

    private val headTagRegex = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)

    fun inject(html: String): String {
        if (html.isBlank()) return html
        if (html.contains("unhandledrejection", ignoreCase = true)) return html

        headTagRegex.find(html)?.let { m ->
            val at = m.range.last + 1
            return html.substring(0, at) + "\n" + PRELUDE + "\n" + html.substring(at)
        }
        htmlTagRegex.find(html)?.let { m ->
            val at = m.range.last + 1
            return html.substring(0, at) + "\n" + PRELUDE + "\n" + html.substring(at)
        }
        return PRELUDE + "\n" + html
    }
}
