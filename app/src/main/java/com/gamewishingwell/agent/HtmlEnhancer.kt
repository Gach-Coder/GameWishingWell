package com.gamewishingwell.agent

/**
 * 渲染前的 HTML 增强：
 * 1. 注入 Android WebView 视口修复：部分 WebView 在 loadDataWithBaseURL 页面中
 *    会把 CSS vh / 根元素百分比高度解析为 0，导致 Canvas 场景不可见；
 *    这里把根元素高度固定为 innerHeight，并把样式表中的 vh 单位转换为 px。
 * 2. 注入 JS 错误捕获器，把 window.onerror / unhandledrejection 转发到 console.error，
 *    这样 WebView 的 onConsoleMessage 能收到运行时报错（含行号），
 *    用户才能看到"游戏运行出错"覆盖层并一键让 AI 修复。
 *
 * 只影响 WebView 里渲染的副本，磁盘上保存的原始 HTML 保持不动。
 */
object HtmlEnhancer {

    private val ERROR_PRELUDE = """
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

    private val VIEWPORT_PRELUDE = """
    <script>
    (function(){
      if (window.__wwViewportFixApplied) return;
      window.__wwViewportFixApplied = true;

      function applyViewportFix() {
        var h = window.innerHeight || document.documentElement.clientHeight || 0;
        if (!h) return;

        // WebView 中 html/body 的 100% 高度可能解析为 0，先给根元素一个确定的像素高度。
        var root = document.documentElement;
        var body = document.body;
        if (root) {
          root.style.height = h + 'px';
          root.style.minHeight = h + 'px';
        }
        if (body) {
          body.style.height = h + 'px';
          body.style.minHeight = h + 'px';
        }

        // 把样式表和内联样式中所有 vh 单位转换为 px（100vh -> innerHeight px）。
        // 转换后的规则追加到末尾并加 !important，避免游戏自己的 vh 高度仍被算成 0。
        var style = document.getElementById('__ww_vh_fix__');
        if (!style) {
          style = document.createElement('style');
          style.id = '__ww_vh_fix__';
          (document.head || root).appendChild(style);
        }
        var css = '';
        for (var i = 0; i < document.styleSheets.length; i++) {
          var rules;
          try { rules = document.styleSheets[i].cssRules; } catch (e) { continue; }
          if (!rules) continue;
          for (var j = 0; j < rules.length; j++) {
            var rule = rules[j];
            if (!rule || rule.type !== 1) continue;
            var decl = rule.style;
            var changed = false;
            var text = '';
            for (var k = 0; k < decl.length; k++) {
              var prop = decl.item(k);
              var value = decl.getPropertyValue(prop);
              if (value.indexOf('vh') !== -1) {
                changed = true;
                text += prop + ':' + value.replace(/(\d*\.?\d+)vh/gi, function(m, n) {
                  return (parseFloat(n) * h / 100) + 'px';
                }) + ' !important;';
              }
            }
            if (changed) css += rule.selectorText + '{' + text + '}';
          }
        }
        style.textContent = css;

        // 兼容直接写在元素 style="height:100vh" 上的游戏。
        var all = document.querySelectorAll('*');
        for (var e = 0; e < all.length; e++) {
          var el = all[e];
          for (var p = 0; p < el.style.length; p++) {
            var prop = el.style.item(p);
            var value = el.style.getPropertyValue(prop);
            if (value.indexOf('vh') !== -1) {
              el.style.setProperty(prop, value.replace(/(\d*\.?\d+)vh/gi, function(m, n) {
                return (parseFloat(n) * h / 100) + 'px';
              }), 'important');
            }
          }
        }
      }

      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', applyViewportFix);
      } else {
        applyViewportFix();
      }
      setTimeout(applyViewportFix, 0);
      window.addEventListener('resize', applyViewportFix);
      window.addEventListener('orientationchange', applyViewportFix);
    })();
    </script>
    """.trimIndent()

    private val headTagRegex = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)

    fun inject(html: String): String {
        if (html.isBlank()) return html

        val needsErrorCatcher = !html.contains("unhandledrejection", ignoreCase = true)
        val needsViewportFix = !html.contains("__wwViewportFixApplied")
        if (!needsErrorCatcher && !needsViewportFix) return html

        val preludes = buildString {
            if (needsViewportFix) append(VIEWPORT_PRELUDE).append("\n")
            if (needsErrorCatcher) append(ERROR_PRELUDE).append("\n")
        }

        headTagRegex.find(html)?.let { m ->
            val at = m.range.last + 1
            return html.substring(0, at) + "\n" + preludes + html.substring(at)
        }
        htmlTagRegex.find(html)?.let { m ->
            val at = m.range.last + 1
            return html.substring(0, at) + "\n" + preludes + html.substring(at)
        }
        return preludes + html
    }
}
