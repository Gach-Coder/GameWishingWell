package com.gamewishingwell.agent

/**
 * 渲染前的 HTML 增强：
 * 1. 注入 Android WebView 视口修复：部分 WebView 在 loadDataWithBaseURL 页面中
 *    会把 CSS vh / 根元素百分比高度解析为 0，导致 Canvas 场景不可见；
 *    这里把根元素高度固定为 innerHeight，并把样式表中的 vh 单位转换为 px。
 * 2. 注入 JS 错误捕获器，把 window.onerror / unhandledrejection 转发到 console.error，
 *    这样 WebView 的 onConsoleMessage 能收到运行时报错（含行号），
 *    用户才能看到"游戏运行出错"覆盖层并一键让 AI 修复。
 * 3. 平台设置桥接：音量（window.__wwSetVolume，默认 0.8，钳制 0–1.5）与暂停
 *    （window.__wwSetPaused）暴露给原生顶栏"设置"面板调用；游戏页内不注入任何
 *    设置按钮/面板 UI，仅隐藏游戏自带的重开类按钮避免与平台"重新游戏"重复。
 * 4. 内置引擎注入：HTML 声明 <meta name="ww-engine" content="three"> 时，
 *    把 APK assets 里的引擎源码注入 <head>（在游戏脚本之前执行）。
 *    引擎源码经 [engineSourceProvider] 获取——真机由 Application 接入 assets
 *    读取实现，JVM 单测可替换为桩；真实游戏页与沙箱共用本注入管道。
 *
 * 只影响 WebView 里渲染的副本，磁盘上保存的原始 HTML 保持不动。
 */
object HtmlEnhancer {

    /** 引擎源码提供器：name → 源码（未知引擎返回 null）。真机接 assets，测试可替换。 */
    @Volatile
    var engineSourceProvider: ((String) -> String?)? = null

    private val ERROR_PRELUDE = """
    <script>
    (function(){
      // 运行时错误格式化回传：file:line + stack + console 片段。
      var __wwConsoleTail = [];
      if (window.console && window.console.error) {
        var __wwRealConsoleError = window.console.error.bind(window.console);
        window.console.error = function(){
          try { __wwConsoleTail.push(Array.prototype.map.call(arguments, String).join(' ').slice(0, 160)); } catch(e) {}
          if (__wwConsoleTail.length > 5) __wwConsoleTail.shift();
          try { __wwRealConsoleError.apply(null, arguments); } catch(e) {}
        };
      }
      function report(msg){
        var stack = '';
        try { if (arguments[1] && arguments[1].stack) stack = arguments[1].stack; } catch(e) {}
        var tail = __wwConsoleTail.join(' | ').slice(0, 240);
        var full = '[游戏错误] ' + msg + (stack ? ' || stack: ' + stack.slice(0, 500) : '') + (tail ? ' || console: ' + tail : '');
        try { console.error(full); } catch(e) {}
      }
      window.addEventListener('error', function(e){
        if (e && e.message) report(e.message + ' @ ' + (e.filename || 'index.html') + ':' + (e.lineno || 0), e.error);
      });
      window.addEventListener('unhandledrejection', function(e){
        var r = e && e.reason;
        report('Promise 未处理: ' + (r && r.message ? r.message : String(r)), r);
      });
    })();
    </script>
    """.trimIndent()

    private val VIEWPORT_PRELUDE = """
    <script>
    (function(){
      if (window.__wwViewportFixApplied) return;
      window.__wwViewportFixApplied = true;

      var __wwVolume = 0.8;
      var __wwPaused = false;
      var __wwRafSeq = 1;
      var __wwRafMap = {};
      var __wwRealRaf = window.requestAnimationFrame && window.requestAnimationFrame.bind(window);
      var __wwRealCaf = window.cancelAnimationFrame && window.cancelAnimationFrame.bind(window);
      var __wwAudioContexts = [];
      var __wwAudioOriginalConnect = null;
      var __wwAudioCtor = window.AudioContext || window.webkitAudioContext;

      // 暂停控制：包装 requestAnimationFrame，设置面板打开时挂起主循环。
      if (__wwRealRaf) {
        window.requestAnimationFrame = function(cb) {
          var token = __wwRafSeq++;
          function schedule() {
            __wwRafMap[token] = __wwRealRaf(function(ts) {
              if (__wwPaused) {
                schedule();
              } else {
                delete __wwRafMap[token];
                cb(ts);
              }
            });
          }
          schedule();
          return token;
        };
        window.cancelAnimationFrame = function(id) {
          if (__wwRafMap[id] !== undefined) {
            __wwRealCaf(__wwRafMap[id]);
            delete __wwRafMap[id];
          } else {
            try { __wwRealCaf(id); } catch (e) {}
          }
        };
      }

      // 音量控制：把游戏 WebAudio 节点统一路由到一个主 GainNode。
      function patchAudioRouting() {
        if (__wwAudioOriginalConnect) return;
        var proto = window.AudioNode && window.AudioNode.prototype;
        if (!proto || !proto.connect) return;
        __wwAudioOriginalConnect = proto.connect;
        proto.connect = function(dest) {
          var ctx = this.context;
          if (ctx && ctx.__wwMasterGain && dest === ctx.destination && !this.__wwIsMaster) {
            return __wwAudioOriginalConnect.call(this, ctx.__wwMasterGain);
          }
          return __wwAudioOriginalConnect.apply(this, arguments);
        };
      }
      function setupAudioContext(ctx) {
        try {
          var master = ctx.createGain();
          master.__wwIsMaster = true;
          if (__wwAudioOriginalConnect) {
            __wwAudioOriginalConnect.call(master, ctx.destination);
          } else {
            master.connect(ctx.destination);
          }
          master.gain.value = __wwVolume;
          ctx.__wwMasterGain = master;
          __wwAudioContexts.push(ctx);
          patchAudioRouting();
        } catch (e) {}
      }
      patchAudioRouting();
      if (__wwAudioCtor) {
        var OriginalAudioContext = __wwAudioCtor;
        window.AudioContext = function() {
          var ctx = new OriginalAudioContext();
          setupAudioContext(ctx);
          return ctx;
        };
        window.webkitAudioContext = window.AudioContext;
      }

      function __wwSetVolume(v) {
        // 上限 1.5：允许对游戏自身偏小的音效做适度放大（原生滑条 0–150%）。
        __wwVolume = Math.max(0, Math.min(1.5, Number(v) || 0));
        for (var i = 0; i < __wwAudioContexts.length; i++) {
          var ctx = __wwAudioContexts[i];
          if (ctx && ctx.__wwMasterGain) {
            try { ctx.__wwMasterGain.gain.value = __wwVolume; } catch (e) {}
          }
        }
      }

      // 平台顶栏"设置"面板的页面侧接口（原生 GameScreen 经 evaluateJavascript 调用）：
      // 音量与暂停都由原生面板驱动，页面内不再注入任何设置按钮或面板。
      window.__wwSetVolume = __wwSetVolume;
      window.__wwSetPaused = function(p) { __wwPaused = !!p; };

      function applyViewportFix() {
        var h = window.innerHeight || document.documentElement.clientHeight || 0;
        if (!h) return;

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

        hideLegacyRestartButtons();
        if (window.MutationObserver && !window.__wwSettingsObserver && root) {
          try {
            window.__wwSettingsObserver = new MutationObserver(hideLegacyRestartButtons);
            window.__wwSettingsObserver.observe(root, { childList: true, subtree: true });
          } catch (e) { window.__wwSettingsObserver = null; }
        }
      }

      function hideLegacyRestartButtons() {
        var nodes = document.querySelectorAll('button, [role="button"]');
        for (var i = 0; i < nodes.length; i++) {
          var el = nodes[i];
          var text = (el.textContent || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.value || '');
          if (/(?:重新开始|重开|重玩|再来一局|restart)/i.test(text)) {
            el.style.display = 'none';
            el.__wwLegacyRestartHidden = true;
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
    private val doctypeRegex = Regex("^\\s*<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE)
    private const val ENGINE_INJECTED_MARK = "__wwEngineInjected"

    fun inject(html: String): String {
        if (html.isBlank()) return html

        val needsErrorCatcher = !html.contains("unhandledrejection", ignoreCase = true)
        val needsViewportFix = !html.contains("__wwViewportFixApplied")
        val engineScripts = buildEngineScripts(html)
        if (!needsErrorCatcher && !needsViewportFix && engineScripts.isEmpty()) return html

        val preludes = buildString {
            // 引擎最先注入：必须在游戏脚本（以及视口修复/错误捕获之后的游戏逻辑）之前完成定义。
            engineScripts.forEach { append(it).append("\n") }
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
        // 无 head/html 的兜底：保持 DOCTYPE（若有）在最前——脚本前插到 DOCTYPE
        // 之前会触发 quirks 模式，视口修复与布局行为失真。
        doctypeRegex.find(html)?.let { m ->
            return html.replaceRange(m.range, m.value + "\n" + preludes)
        }
        return preludes + html
    }

    /**
     * 按声明顺序产出引擎注入块（幂等：已注入过标记则跳过）。
     * 只注入已内置的引擎——未内置/本轮条件不允许的声明由 GameValidator 打回，
     * 这里静默跳过可保证渲染副本不因非法声明而注入未知代码。
     */
    private fun buildEngineScripts(html: String): List<String> {
        if (html.contains(ENGINE_INJECTED_MARK)) return emptyList()
        val provider = engineSourceProvider ?: return emptyList()
        return GameEngines.declaredEngines(html)
            .filter { it in GameEngines.BUNDLED }
            .mapNotNull { engine ->
                provider(engine)?.let { source ->
                    "<script>/* $ENGINE_INJECTED_MARK:$engine */\n$source\n</script>"
                }
            }
    }
}
