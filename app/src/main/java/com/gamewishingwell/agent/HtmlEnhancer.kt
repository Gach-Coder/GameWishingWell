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

      var __wwVolume = 0.8;
      var __wwPaused = false;
      var __wwSettingsOpen = false;
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
        __wwVolume = Math.max(0, Math.min(1, Number(v) || 0));
        for (var i = 0; i < __wwAudioContexts.length; i++) {
          var ctx = __wwAudioContexts[i];
          if (ctx && ctx.__wwMasterGain) {
            try { ctx.__wwMasterGain.gain.value = __wwVolume; } catch (e) {}
          }
        }
      }

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

        installGameSettings();
        if (window.MutationObserver && !window.__wwSettingsObserver && root) {
          try {
            window.__wwSettingsObserver = new MutationObserver(installGameSettings);
            window.__wwSettingsObserver.observe(root, { childList: true, subtree: true });
          } catch (e) { window.__wwSettingsObserver = null; }
        }
      }

      function hideLegacyRestartButtons() {
        var nodes = document.querySelectorAll('button, [role="button"]');
        for (var i = 0; i < nodes.length; i++) {
          var el = nodes[i];
          if (el.id === '__ww_game_settings_btn') continue;
          var text = (el.textContent || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.value || '');
          if (/(?:重新开始|重开|重玩|再来一局|restart)/i.test(text)) {
            el.style.display = 'none';
            el.__wwLegacyRestartHidden = true;
          }
        }
      }

      function installGameSettings() {
        hideLegacyRestartButtons();
        var body = document.body;
        if (!body) return;
        if (document.getElementById('__ww_game_settings_btn')) return;

        var btn = document.createElement('button');
        btn.id = '__ww_game_settings_btn';
        btn.type = 'button';
        btn.setAttribute('aria-label', '设置');
        btn.textContent = '⚙';
        btn.style.cssText = 'position:fixed;top:112px;right:12px;width:40px;height:40px;padding:0;margin:0;border:none;border-radius:10px;background:rgba(20,22,34,0.82);color:#ffffff;font-size:20px;line-height:40px;text-align:center;z-index:99990;box-shadow:0 4px 12px rgba(0,0,0,0.35);';
        btn.addEventListener('click', function(e) {
          e.preventDefault();
          e.stopPropagation();
          openGameSettings();
        });
        body.appendChild(btn);

        var overlay = document.createElement('div');
        overlay.id = '__ww_game_settings_overlay';
        overlay.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;background:rgba(0,0,0,0.58);display:none;align-items:center;justify-content:center;z-index:99999;';
        overlay.addEventListener('click', function(e) {
          if (e.target === overlay) closeGameSettings();
        });

        var panel = document.createElement('div');
        panel.style.cssText = 'width:82%;max-width:340px;background:#1c2130;color:#f5f7ff;border-radius:16px;padding:18px;box-shadow:0 20px 60px rgba(0,0,0,0.45);font-family:system-ui,sans-serif;';

        var title = document.createElement('div');
        title.textContent = '游戏设置';
        title.style.cssText = 'font-size:18px;font-weight:700;text-align:center;margin-bottom:14px;';

        var volRow = document.createElement('div');
        volRow.style.cssText = 'display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;';
        var volLabel = document.createElement('span');
        volLabel.textContent = '音量';
        volLabel.style.cssText = 'font-size:14px;';
        var volValue = document.createElement('span');
        volValue.textContent = Math.round(__wwVolume * 100) + '%';
        volValue.style.cssText = 'font-size:13px;opacity:0.85;';
        volRow.appendChild(volLabel);
        volRow.appendChild(volValue);

        var slider = document.createElement('input');
        slider.type = 'range';
        slider.min = '0';
        slider.max = '100';
        slider.value = String(Math.round(__wwVolume * 100));
        slider.style.cssText = 'width:100%;height:28px;margin:2px 0 14px;';
        slider.addEventListener('input', function() {
          var v = Number(slider.value) || 0;
          volValue.textContent = v + '%';
          __wwSetVolume(v / 100);
        });

        var actions = document.createElement('div');
        actions.style.cssText = 'display:flex;gap:10px;margin-top:4px;';

        var restartBtn = document.createElement('button');
        restartBtn.type = 'button';
        restartBtn.textContent = '重新游戏';
        restartBtn.style.cssText = 'flex:1;height:40px;border:none;border-radius:10px;background:#4f7cff;color:#ffffff;font-size:14px;font-weight:700;';
        restartBtn.addEventListener('click', function(e) {
          e.stopPropagation();
          closeGameSettings();
          try {
            if (typeof window.restart === 'function') window.restart();
          } catch (err) {
            try { console.error('[游戏错误] 重新游戏失败: ' + err.message); } catch (e2) {}
          }
        });

        var resumeBtn = document.createElement('button');
        resumeBtn.type = 'button';
        resumeBtn.textContent = '继续游戏';
        resumeBtn.style.cssText = 'flex:1;height:40px;border:none;border-radius:10px;background:#343b52;color:#ffffff;font-size:14px;font-weight:700;';
        resumeBtn.addEventListener('click', function(e) {
          e.stopPropagation();
          closeGameSettings();
        });

        actions.appendChild(restartBtn);
        actions.appendChild(resumeBtn);
        panel.appendChild(title);
        panel.appendChild(volRow);
        panel.appendChild(slider);
        panel.appendChild(actions);
        overlay.appendChild(panel);
        body.appendChild(overlay);
      }

      function openGameSettings() {
        if (__wwSettingsOpen) return;
        __wwSettingsOpen = true;
        __wwPaused = true;
        var overlay = document.getElementById('__ww_game_settings_overlay');
        if (overlay) overlay.style.display = 'flex';
      }

      function closeGameSettings() {
        if (!__wwSettingsOpen) return;
        __wwSettingsOpen = false;
        __wwPaused = false;
        var overlay = document.getElementById('__ww_game_settings_overlay');
        if (overlay) overlay.style.display = 'none';
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
