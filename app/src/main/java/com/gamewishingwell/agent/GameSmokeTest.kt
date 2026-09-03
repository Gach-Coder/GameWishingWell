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
    /** 只在静态校验全部通过后调用。 */
    suspend fun run(html: String): SmokeTestResult
}

@kotlinx.serialization.Serializable
data class SmokeTestResult(
    val passed: Boolean,
    val framesRun: Int = 0,
    val errors: List<String> = emptyList(),
    val message: String? = null
)

object NoopSmokeTestRunner : SmokeTestRunner {
    override suspend fun run(html: String): SmokeTestResult =
        SmokeTestResult(passed = true, message = "noop-smoke-runner")
}

/**
 * 沙箱校验探针：把 requestAnimationFrame 改成确定性 tick 队列，DOMContentLoaded 后
 * 同步跑满最多 [MAX_FRAMES] 帧；任何帧内异常、全局异常（含事件回调）与 console
 * error 都会被捕获；派发一次合成 touchstart 扩大交互路径覆盖；localStorage 替换为
 * 内存 stub，避免沙箱 iframe 的 opaque origin 抛 SecurityError 造成环境性误报；
 * 同时设置超时兜底，防止定时器失控。
 *
 * 沙箱是运行正确性的唯一校验来源：passed = 跑满 tick 且无任何错误文本。
 */
object SmokeTestProbe {
    const val MAX_FRAMES = 8
    private const val MARKER = "__wwSmokeInstalled"

    fun inject(html: String): String {
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
              window.requestAnimationFrame = function(cb){
                if (ticked < ${MAX_FRAMES}) { window.__wwSmokePending.push(cb); return window.__wwSmokePending.length; }
                return realRaf ? realRaf(cb) : 0;
              };
              window.cancelAnimationFrame = function(id){
                if (realCaf) { try { realCaf(id); } catch(e) {} }
              };
              function pump(){
                if (ticked >= ${MAX_FRAMES}) {
                  blankCheck();
                  window.__wwSmokeResult = {passed: window.__wwSmokeResult.errors.length === 0, framesRun: ticked, errors: window.__wwSmokeResult.errors};
                  if (timer) { clearTimeout(timer); timer = null; }
                  return;
                }
                ticked++;
                var now = ticked * 16.7;
                var batch = window.__wwSmokePending.splice(0, window.__wwSmokePending.length);
                for (var i = 0; i < batch.length; i++) {
                  try { batch[i](now); }
                  catch (e) { window.__wwSmokeResult.errors.push(String(e && e.message || e)); }
                }
                if (window.__wwSmokePending.length > 0) {
                  timer = setTimeout(pump, 0);
                } else {
                  timer = setTimeout(pump, 40);
                }
              }
              // 合成交互：在画布中心派发带坐标的 touchstart 与 click——多数游戏的
              // "开始"按钮在中心，带坐标的点击才能让游戏真正进入运行态（供画面检测采样）。
              function pokeTouch(){
                try {
                  var target = document.querySelector('canvas') || document.body;
                  var rect = (target.getBoundingClientRect && target.getBoundingClientRect()) || {width: window.innerWidth, height: window.innerHeight};
                  var x = (rect.width || window.innerWidth) / 2;
                  var y = (rect.height || window.innerHeight) / 2;
                  var ev = null;
                  try {
                    var t = new Touch({identifier: 1, target: target, clientX: x, clientY: y});
                    ev = new TouchEvent('touchstart', {bubbles:true, cancelable:true, touches:[t], targetTouches:[t], changedTouches:[t]});
                  } catch (e1) {
                    try { ev = new TouchEvent('touchstart', {bubbles:true, cancelable:true}); }
                    catch (e2) { ev = document.createEvent('Event'); ev.initEvent('touchstart', true, true); }
                  }
                  target.dispatchEvent(ev);
                  var click = document.createEvent('MouseEvents');
                  click.initMouseEvent('click', true, true, window, 1, x, y, x, y, false, false, false, false, 0, null);
                  target.dispatchEvent(click);
                } catch (e) {
                  window.__wwSmokeResult.errors.push('touch-dispatch:' + String(e && e.message || e));
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
              function start(){
                if (document.readyState !== 'loading') { setTimeout(pump, 10); setTimeout(pokeTouch, 60); }
                else { window.addEventListener('DOMContentLoaded', function(){ setTimeout(pump, 10); setTimeout(pokeTouch, 60); }); }
              }
              start();
              setTimeout(function(){
                if (ticked < ${MAX_FRAMES}) {
                  window.__wwSmokeResult = {passed:false, framesRun:ticked, errors:window.__wwSmokeResult.errors.concat(['smoke-timeout'])};
                }
              }, 15000);
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
    override suspend fun run(html: String): SmokeTestResult {
        val prepared = HtmlEnhancer.inject(SmokeTestProbe.inject(html))
        return withTimeoutOrNull(TIMEOUT_MS) {
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
                main.postDelayed(timeout, TIMEOUT_MS)

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
        // 外层兜底超时：模拟器/低端机 WebView 慢，给足余量；仅防挂起。
        const val TIMEOUT_MS = 30_000L
        const val FIRST_POLL_DELAY_MS = 1_200L
        const val POLL_INTERVAL_MS = 700L
    }
}
