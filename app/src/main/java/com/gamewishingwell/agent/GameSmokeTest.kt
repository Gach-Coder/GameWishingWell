package com.gamewishingwell.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

interface SmokeTestRunner {
    /**
     * 只在静态校验全部通过后调用。[deep] 为精品档（expectedLoops≥11）深度模式：
     * 额外真实调用 restart() 完整重开并复跑半程帧，验证重开契约。
     */
    suspend fun run(html: String, deep: Boolean = false): SmokeTestResult
}

@kotlinx.serialization.Serializable
data class SmokeTestResult(
    val passed: Boolean,
    val framesRun: Int = 0,
    val errors: List<String> = emptyList(),
    val message: String? = null
)

object NoopSmokeTestRunner : SmokeTestRunner {
    override suspend fun run(html: String, deep: Boolean): SmokeTestResult =
        SmokeTestResult(passed = true, message = "noop-smoke-runner")
}

/**
 * 沙箱校验探针：把 requestAnimationFrame 改成确定性 tick 队列，DOMContentLoaded 后
 * 跑满最多 [MAX_FRAMES] 帧（每帧 50ms 游戏时间，覆盖开始交互后的早期玩法而不只是首帧渲染）；
 * 任何帧内异常、全局异常（含事件回调）与 console error 都会被捕获；
 * 合成交互在多点位（中心/底部/左右下侧）派发 touchstart/touchend/click 并做一次拖动，
 * 开始后与跑帧中段各一轮；localStorage 替换为内存 stub，避免沙箱跨源异常；
 * 跑满帧后做白屏检测、顶部保留区扫描（平台操作条区域内出现可交互元素即失败）
 * 与可观测性不变量断言（调用 __wwDebugState() 检查负血量实体/NaN 数值/实体泄漏）；
 * 同时设置超时兜底，防止定时器失控。
 *
 * 沙箱是运行正确性的唯一校验来源：passed = 跑满 tick 且无任何错误文本。
 */
object SmokeTestProbe {
    /** 确定性 tick 帧数：每帧 50ms 游戏时间，共约 1.2s——覆盖出怪、数值变动、状态切换等早期玩法。 */
    const val MAX_FRAMES = 24
    /** 平台顶部保留区高度（px）：区域内出现可交互元素即判失败（会被平台操作条遮挡）。 */
    const val RESERVED_TOP_PX = 110
    private const val MARKER = "__wwSmokeInstalled"

    fun inject(html: String, deep: Boolean = false): String {
        if (html.contains(MARKER)) return html
        val script = """
            <script>
            (function(){
              if (window.$MARKER) return;
              window.$MARKER = true;
              window.__wwSmokeResult = {passed:false, framesRun:0, errors:[]};
              window.__wwSmokePending = [];
              // localStorage 内存 stub：沙箱 iframe（opaque origin）访问真实
              // localStorage 会抛 SecurityError，而真机游戏页正常——纯环境差异。
              try {
                var mem = {};
                var stub = {
                  getItem: function(k){ return Object.prototype.hasOwnProperty.call(mem,k) ? mem[k] : null; },
                  setItem: function(k,v){ mem[k] = String(v); },
                  removeItem: function(k){ delete mem[k]; },
                  clear: function(){ mem = {}; },
                  key: function(i){ return Object.keys(mem)[i] || null; }
                };
                Object.defineProperty(stub, 'length', { get: function(){ return Object.keys(mem).length; } });
                try { window.localStorage.getItem('x'); } catch (e) {
                  try { Object.defineProperty(window, 'localStorage', { value: stub, configurable: true }); } catch (e2) {}
                }
              } catch (e) {}
              // 全局异常捕获：tick 之外（事件回调/异步）的异常也计入沙箱结果。
              try{Object.defineProperty(document,'visibilityState',{get:function(){return 'visible';},configurable:true});}catch(e){}
              try{Object.defineProperty(document,'hidden',{get:function(){return false;},configurable:true});}catch(e){}
              window.addEventListener('error', function(e){
                window.__wwSmokeResult.errors.push(String((e && e.message) || e || 'unknown-error'));
              });
              var realRaf = window.requestAnimationFrame && window.requestAnimationFrame.bind(window);
              var realCaf = window.cancelAnimationFrame && window.cancelAnimationFrame.bind(window);
              var ticked = 0;
              var timer = null;
              var __finalized = false; // 终态防覆盖：deep 复跑会重置 ticked，超时回调不得覆盖已通过结果
              // deep（精品档）：restart() 真实重开后复跑半程帧，验证"完整重开"契约。
              var __deep = ${if (deep) "true" else "false"};
              var phase = 1;   // 1=首次运行 2=restart 后复跑
              var limit = ${MAX_FRAMES};
              window.requestAnimationFrame = function(cb){
                if (ticked < ${MAX_FRAMES}) { window.__wwSmokePending.push(cb); return window.__wwSmokePending.length; }
                return realRaf ? realRaf(cb) : 0;
              };
              window.cancelAnimationFrame = function(id){
                if (realCaf) { try { realCaf(id); } catch(e) {} }
              };
              // restart() 契约：全档检查存在性（生成契约要求全局 restart()）；
              // deep 档真实调用一次，捕获"重开才暴露"的运行错误。
              function restartPresenceCheck(){
                try {
                  if (typeof window.restart !== 'function') {
                    window.__wwSmokeResult.errors.push('restart-missing: 未找到全局 restart() 函数（契约要求提供完整重开）');
                  }
                } catch (e) {}
              }
              function exerciseRestart(){
                try {
                  if (typeof window.restart === 'function') { window.restart(); }
                } catch (e) {
                  window.__wwSmokeResult.errors.push('restart-error: 调用 restart() 抛错：' + String(e && e.message || e));
                }
              }
              // 可观测性契约断言：调用游戏提供的 __wwDebugState() 快照做通用不变量检查，
              // 杀"不抛错但明显不对"的低级逻辑 bug：负血量实体未移除、NaN/Infinity 数值、
              // 实体数组无限增长（泄漏）、restart 后状态残留（afterRestart 时检查）。
              function debugStateCheck(afterRestart){
                try {
                  var fn = window.__wwDebugState;
                  if (typeof fn !== 'function') {
                    window.__wwSmokeResult.errors.push('debug-state-missing: 未找到 window.__wwDebugState()（可观测性契约：必须提供调试状态快照函数）');
                    return;
                  }
                  var s = null;
                  try { s = fn(); }
                  catch (e) {
                    window.__wwSmokeResult.errors.push('debug-state-error: __wwDebugState() 抛错：' + String(e && e.message || e));
                    return;
                  }
                  if (!s || typeof s !== 'object') {
                    window.__wwSmokeResult.errors.push('debug-state-invalid: __wwDebugState() 必须返回状态对象（当前为 ' + (s === null ? 'null' : typeof s) + '）');
                    return;
                  }
                  var problems = [];
                  var ents = s.entities;
                  if (ents !== undefined && ents !== null) {
                    if (Object.prototype.toString.call(ents) !== '[object Array]') {
                      problems.push('debug-state-invalid: entities 必须是数组');
                    } else {
                      if (ents.length > 500) {
                        problems.push('invariant-entity-leak: 活动实体 ' + ents.length + ' 个，疑似死亡/离场实体未从数组移除（entities 只保留存活实体）');
                      }
                      var negHp = '';
                      for (var i = 0; i < ents.length; i++) {
                        var e = ents[i];
                        if (!e || typeof e !== 'object') continue;
                        if (typeof e.hp === 'number' && !isFinite(e.hp)) {
                          problems.push('invariant-non-finite: 实体(type=' + (e.type || 'unknown') + ') 的 hp 为 NaN/Infinity，数值计算异常');
                        } else if (typeof e.hp === 'number' && e.hp < 0) {
                          negHp = negHp || ('type=' + (e.type || 'unknown') + ' hp=' + e.hp);
                        }
                      }
                      if (negHp) {
                        problems.push('invariant-negative-hp: 存在 hp<0 的实体（' + negHp + '）：死亡判定应使用 hp<=0 且当帧从 entities 移除');
                      }
                    }
                  }
                  var groups = [s];
                  if (s.player && typeof s.player === 'object') groups.push(s.player);
                  for (var g = 0; g < groups.length; g++) {
                    var o = groups[g];
                    for (var k in o) {
                      if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
                      if (typeof o[k] === 'number' && !isFinite(o[k])) {
                        problems.push('invariant-non-finite: ' + k + ' 为 NaN/Infinity，数值计算异常');
                      }
                    }
                  }
                  if (afterRestart && typeof s.state === 'string' && s.state === 'over') {
                    problems.push('restart-state-residue: restart() 后 state 仍为 over，游戏状态未完整重置');
                  }
                  for (var p = 0; p < problems.length && p < 5; p++) {
                    window.__wwSmokeResult.errors.push(problems[p]);
                  }
                } catch (e) { /* 检查器自身异常不作游戏失败 */ }
              }
              function pump(){
                if (ticked >= limit) {
                  if (phase === 1) {
                    restartPresenceCheck();
                    blankCheck();
                    reservedAreaCheck();
                    debugStateCheck(false);
                    if (__deep) {
                      phase = 2; ticked = 0; limit = Math.floor(${MAX_FRAMES} / 2);
                      exerciseRestart();
                      timer = setTimeout(pump, 40);
                      return;
                    }
                  } else {
                    blankCheck();
                    debugStateCheck(true);
                  }
                  __finalized = true;
                  window.__wwSmokeResult = {passed: window.__wwSmokeResult.errors.length === 0, framesRun: (phase === 2 ? ${MAX_FRAMES} + ticked : ticked), errors: window.__wwSmokeResult.errors};
                  if (timer) { clearTimeout(timer); timer = null; }
                  return;
                }
                ticked++;
                var now = ticked * 50; // 每帧 50ms 游戏时间（dt 恰为健壮性契约的钳制上限）
                var batch = window.__wwSmokePending.splice(0, window.__wwSmokePending.length);
                for (var i = 0; i < batch.length; i++) {
                  try { batch[i](now); }
                  catch (e) { window.__wwSmokeResult.errors.push(String(e && e.message || e)); }
                }
                if (phase === 1 && ticked === ${MAX_FRAMES / 2}) { pokeTouch(); } // 中段再交互一轮：让游戏进入运行态后的处理器也得到执行
                if (window.__wwSmokePending.length > 0) {
                  timer = setTimeout(pump, 0);
                } else {
                  timer = setTimeout(pump, 40);
                }
              }
              // 合成交互：多点位派发 touchstart/touchend 与 click（中心/底部中央/左右下侧，
              // 命中 elementFromPoint 处的真实元素），再做一次滑动（touchstart→touchmove→touchend）
              // 覆盖虚拟摇杆/拖动路径；开始后与跑帧中段各派发一轮，让游戏真正进入运行态。
              function pokeTouch(){
                try {
                  var fallback = document.querySelector('canvas') || document.body;
                  var vw = window.innerWidth || 360, vh = window.innerHeight || 640;
                  pokePoint(fallback, vw / 2, vh / 2);
                  pokePoint(fallback, vw / 2, vh - 90);
                  pokePoint(fallback, vw * 0.25, vh * 0.7);
                  pokePoint(fallback, vw * 0.75, vh * 0.7);
                  dragTouch(fallback, vw / 2, vh * 0.6, vw / 2 + 60, vh * 0.6 - 60);
                } catch (e) {
                  window.__wwSmokeResult.errors.push('touch-dispatch:' + String(e && e.message || e));
                }
              }
              function hitTarget(x, y, fallback){
                try { return document.elementFromPoint(x, y) || fallback; } catch (e) { return fallback; }
              }
              function mkTouch(target, x, y){
                try { return new Touch({identifier: 1, target: target, clientX: x, clientY: y}); }
                catch (e) { return null; }
              }
              function mkTouchEvent(name, t){
                try {
                  if (t) return new TouchEvent(name, {bubbles:true, cancelable:true, touches:[t], targetTouches:[t], changedTouches:[t]});
                  return new TouchEvent(name, {bubbles:true, cancelable:true});
                } catch (e1) {
                  try { var ev = document.createEvent('Event'); ev.initEvent(name, true, true); return ev; }
                  catch (e2) { return null; }
                }
              }
              function pokePoint(fallback, x, y){
                try {
                  var target = hitTarget(x, y, fallback);
                  var t = mkTouch(target, x, y);
                  var ts = mkTouchEvent('touchstart', t);
                  if (ts) target.dispatchEvent(ts);
                  var click = document.createEvent('MouseEvents');
                  click.initMouseEvent('click', true, true, window, 1, x, y, x, y, false, false, false, false, 0, null);
                  target.dispatchEvent(click);
                  var te = mkTouchEvent('touchend', t);
                  if (te) target.dispatchEvent(te);
                } catch (e) {
                  window.__wwSmokeResult.errors.push('touch-dispatch:' + String(e && e.message || e));
                }
              }
              function dragTouch(fallback, x0, y0, x1, y1){
                try {
                  var target = hitTarget(x0, y0, fallback);
                  var t0 = mkTouch(target, x0, y0), t1 = mkTouch(target, x1, y1);
                  var a = mkTouchEvent('touchstart', t0); if (a) target.dispatchEvent(a);
                  var m = mkTouchEvent('touchmove', t1); if (m) target.dispatchEvent(m);
                  var b = mkTouchEvent('touchend', t1); if (b) target.dispatchEvent(b);
                } catch (e) {
                  window.__wwSmokeResult.errors.push('touch-drag:' + String(e && e.message || e));
                }
              }
              // 画面空白检测：跑满帧后所有 canvas 均无内容且页面无可读文本 = 白屏假通过。
              // 空白 canvas 的 PNG 极小（<3KB），任何真实绘制都会显著增大 dataURL。
              function blankCheck(){
                try {
                  var canvases = document.querySelectorAll('canvas');
                  var blank = canvases.length > 0;
                  for (var i = 0; i < canvases.length; i++) {
                    var c = canvases[i];
                    if (c.width === 0 || c.height === 0) continue;
                    if (c.toDataURL().length > 3000) { blank = false; break; }
                  }
                  var text = ((document.body && document.body.innerText) || '').trim();
                  if (text.length >= 30) blank = false;
                  if (blank) {
                    window.__wwSmokeResult.errors.push('playability-blank-screen: 画面检测未发现任何渲染内容（白屏），游戏未实际运行或未绘制');
                  }
                } catch (e) {}
              }
              // 顶部保留区执法（平台契约）：约 ${RESERVED_TOP_PX}px 平台操作条区域（左返回/右设置）内
              // 出现任何可交互元素即失败——会被平台顶栏遮挡而无法点击，报错文本直接可执行
              // （提示移入安全区）。文字标签仅在中央允许、无法静态判定，由生成提示词约束。
              function reservedAreaCheck(){
                try {
                  var bad = [];
                  var els = document.querySelectorAll('button,a,[role="button"],input,select,textarea,[onclick]');
                  for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    if (el.id && String(el.id).indexOf('__ww') === 0) continue;
                    var r = el.getBoundingClientRect();
                    if (r.width <= 0 || r.height <= 0) continue;
                    var st = null;
                    try { st = window.getComputedStyle(el); } catch (e0) {}
                    if (st && (st.display === 'none' || st.visibility === 'hidden' || st.pointerEvents === 'none')) continue;
                    if (r.top < ${RESERVED_TOP_PX}) {
                      var label = ((el.innerText || el.value || el.getAttribute('aria-label') || el.tagName || '') + '').trim().slice(0, 16);
                      bad.push(el.tagName.toLowerCase() + (label ? '(' + label + ')' : '') + '@top' + Math.round(r.top));
                    }
                  }
                  if (bad.length) {
                    window.__wwSmokeResult.errors.push('top-reserved-area: 顶部约${RESERVED_TOP_PX}px 平台保留区内出现可交互元素（会被平台操作条遮挡无法点击）：' + bad.slice(0, 3).join(' | ') + '；请将这些元素整体移到安全区（top 约 120px 以下）');
                  }
                } catch (e) {}
              }
              function start(){
                if (document.readyState !== 'loading') { setTimeout(pump, 10); setTimeout(pokeTouch, 60); }
                else { window.addEventListener('DOMContentLoaded', function(){ setTimeout(pump, 10); setTimeout(pokeTouch, 60); }); }
              }
              start();
              setTimeout(function(){
                if (!__finalized) {
                  window.__wwSmokeResult = {passed:false, framesRun:ticked, errors:window.__wwSmokeResult.errors.concat(['smoke-timeout'])};
                }
              }, ${if (deep) 60000 else 45000});
            })();
            </script>
        """.trimIndent()

        return when {
            html.contains("</head>", ignoreCase = true) ->
                html.replaceFirst("</head>", "$script</head>", ignoreCase = true)
            html.contains("<body", ignoreCase = true) ->
                html.replaceFirst("<body", "$script<body", ignoreCase = true)
            else -> script + html
        }
    }
}

/**
 * 沙箱运行器：同内核 WebView 直载探针注入后的游戏 HTML，轮询读取探针结果。
 *
 * 不再用 iframe/srcdoc 包装——实测在模拟器等环境下 iframe 加载/onPageFinished
 * 链路不可靠（表现为 frames=0 + 外层超时，历史累计 200+ 次），且 srcdoc 的
 * opaque origin 还会引起 localStorage 跨源异常。直载 + 定时轮询
 * window.__wwSmokeResult，探针完成（跑满帧或出现错误）即返回；外层超时仅兜底。
 */
class AndroidSmokeTestRunner(private val appContext: Context) : SmokeTestRunner {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun run(html: String, deep: Boolean): SmokeTestResult {
        val prepared = HtmlEnhancer.inject(SmokeTestProbe.inject(html, deep))
        val timeoutMs = if (deep) DEEP_TIMEOUT_MS else TIMEOUT_MS
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                lateinit var webView: WebView
                var finished = false
                var polls = 0

                val timeout = Runnable {
                    if (!finished) {
                        finished = true
                        runCatching { webView.stopLoading() }
                        cont.resumeWith(Result.success(SmokeTestResult(false, errors = listOf("冒烟测试超时"), message = "smoke-timeout")))
                    }
                }

                val finish: (SmokeTestResult) -> Unit = { result ->
                    if (!finished) {
                        finished = true
                        main.removeCallbacks(timeout)
                        cont.resumeWith(Result.success(result))
                    }
                }

                val consoleErrors = mutableListOf<String>()

                fun poll() {
                    if (finished) return
                    polls++
                    val js = "(function(){try{var r=window.__wwSmokeResult;return JSON.stringify({p:r&&r.passed!==undefined&&r.passed,f:r&&r.framesRun||0,e:r&&r.errors||[],done:(r&&r.framesRun>=${SmokeTestProbe.MAX_FRAMES})||(r&&r.errors&&r.errors.length>0)});}catch(err){return JSON.stringify({p:false,f:0,e:[String(err)],done:true});}})()"
                    webView.evaluateJavascript(js) { value ->
                        val parsed = parseProbeResult(value, emptyList())
                        // done=探针已有终态（跑满帧或出现错误）；未终态则继续轮询
                        val raw = value?.trim()?.removeSurrounding("\"")
                        val done = raw?.let { runCatching {
                            Json.parseToJsonElement(it).jsonObject["done"]?.jsonPrimitive?.contentOrNull == "true"
                        }.getOrNull() } ?: false
                        if (done || parsed.errors.isNotEmpty() || parsed.passed) {
                            val console = synchronized(consoleErrors) { consoleErrors.toList() }
                            finish(
                                SmokeTestResult(
                                    passed = parsed.passed && console.isEmpty(),
                                    framesRun = parsed.framesRun,
                                    errors = parsed.errors + console,
                                    message = if (parsed.passed) "smoke-ok" else "smoke-failed"
                                )
                            )
                        } else {
                            main.postDelayed(::poll, POLL_INTERVAL_MS)
                        }
                    }
                }

                main.post {
                    val view = WebView(appContext)
                    webView = view
                    // 与真实游戏页（GameScreen）保持一致的 WebView 配置与手机级视口。
                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.settings.allowFileAccess = false
                    view.settings.allowContentAccess = false
                    view.settings.useWideViewPort = true
                    view.settings.loadWithOverviewMode = true
                    view.layout(0, 0, 360, 640)
                    view.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                val msg = consoleMessage.message() ?: ""
                                if (msg.isNotBlank() && !msg.startsWith("[游戏错误]")) {
                                    synchronized(consoleErrors) { consoleErrors += msg.take(200) }
                                }
                            }
                            return true
                        }

                        // JS 弹窗自动确认：离屏 WebView 没有界面，未消费的弹窗可能挂起页面。
                        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                            result.confirm()
                            return true
                        }

                        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                            result.confirm()
                            return true
                        }

                        override fun onJsPrompt(
                            view: WebView,
                            url: String,
                            message: String,
                            defaultValue: String?,
                            result: JsPromptResult
                        ): Boolean {
                            result.confirm()
                            return true
                        }
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            // 不依赖单次 onPageFinished：从加载完成起开始轮询探针结果。
                            view.postDelayed(::poll, FIRST_POLL_DELAY_MS)
                        }
                    }
                    // 兜底：若 onPageFinished 未触发（个别环境），仍按计划开始轮询。
                    main.postDelayed(::poll, FIRST_POLL_DELAY_MS + 2500)
                    view.loadDataWithBaseURL(null, prepared, "text/html", "UTF-8", null)
                }
                main.postDelayed(timeout, timeoutMs)

                cont.invokeOnCancellation {
                    main.post { runCatching { webView.destroy() } }
                }
            }
        } ?: SmokeTestResult(false, errors = listOf("冒烟测试超时"), message = "smoke-timeout")
    }

    private fun parseProbeResult(raw: String?, consoleErrors: List<String>): SmokeTestResult {
        val fallback = SmokeTestResult(false, errors = consoleErrors, message = "probe-result-unavailable")
        val value = raw?.trim()?.removeSurrounding("\"") ?: return fallback
        return try {
            val json = Json.parseToJsonElement(value) as? JsonObject ?: return fallback
            val passed = (json["p"] as? JsonPrimitive)?.contentOrNull == "true"
            val frames = (json["f"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val errors = (json["e"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            } ?: emptyList()
            SmokeTestResult(
                passed = passed && errors.isEmpty(),
                framesRun = frames,
                errors = errors + consoleErrors,
                message = if (passed) "smoke-ok" else "smoke-failed"
            )
        } catch (_: Exception) {
            fallback
        }
    }

    private companion object {
        // 外层兜底超时：模拟器/低端机 WebView 慢（离屏节流定时器至约1次/秒，24 帧需 25~35s），给足余量；仅防挂起。
        const val TIMEOUT_MS = 80_000L
        // deep（精品档）额外跑 restart() 复跑半程（合计约 36 tick），预算相应加宽。
        const val DEEP_TIMEOUT_MS = 110_000L
        const val FIRST_POLL_DELAY_MS = 1_200L
        const val POLL_INTERVAL_MS = 700L
    }
}
