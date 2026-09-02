package com.gamewishingwell.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
 * 冒烟测试探针：把 requestAnimationFrame 改成确定性 tick 队列，
 * DOMContentLoaded 后同步跑满最多 [MAX_FRAMES] 帧；任何帧内异常都会被捕获，
 * 同时设置超时兜底，防止定时器失控。替代 jsdom/Playwright。
 *
 * Agent Loop 在静态校验通过且内容确有变化后调用本探针验证“可正确运行”，
 * 失败结果作为观察回填给模型继续修复（同签名熔断同样生效）。
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
              function start(){
                if (document.readyState !== 'loading') { setTimeout(pump, 10); }
                else { window.addEventListener('DOMContentLoaded', function(){ setTimeout(pump, 10); }); }
              }
              start();
              setTimeout(function(){
                if (ticked < ${MAX_FRAMES}) {
                  window.__wwSmokeResult = {passed:false, framesRun:ticked, errors:window.__wwSmokeResult.errors.concat(['smoke-timeout'])};
                }
              }, 2500);
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
 * 同 WebView 内核的隔离 iframe 冒烟测试：
 * 在离屏 WebView 中加载 [SmokeTestProbe] 注入后的 HTML，等待确定性 tick 结果。
 * 带 8 秒超时。由 GameAgent 在验收阶段调用（JVM 单测经注入点替换或跳过）。
 */
class AndroidSmokeTestRunner(private val appContext: Context) : SmokeTestRunner {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun run(html: String): SmokeTestResult {
        val prepared = HtmlEnhancer.inject(SmokeTestProbe.inject(html))
        val isolated = buildIsolatedFrameHtml(prepared)
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                lateinit var webView: WebView
                var finished = false

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

                main.post {
                    val view = WebView(appContext)
                    webView = view
                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.settings.allowFileAccess = false
                    view.settings.allowContentAccess = false
                    val consoleErrors = mutableListOf<String>()
                    view.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                val msg = consoleMessage.message() ?: ""
                                if (msg.isNotBlank() && !msg.startsWith("[游戏错误]")) consoleErrors += msg.take(200)
                            }
                            return true
                        }
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            // 等探针 tick 完成后读取结构化结果。
                            // 探针运行在 srcdoc iframe 内，结果挂在 iframe 的 window 上——
                            // 必须经 contentWindow 读取；父 window 上取永远为 undefined。
                            view.postDelayed({
                                val js = "(function(){try{var w=null;try{var f=document.getElementById('__ww_game_frame');w=f&&f.contentWindow;}catch(e){}var r=(w&&w.__wwSmokeResult)||window.__wwSmokeResult;return JSON.stringify({p:r&&r.passed,f:r&&r.framesRun,e:r&&r.errors||[]});}catch(e){return JSON.stringify({p:false,f:0,e:[String(e)]});}})()"
                                view.evaluateJavascript(js) { value ->
                                    val result = parseProbeResult(value, consoleErrors.toList())
                                    finish(result)
                                }
                            }, 1200)
                        }
                    }
                    view.loadDataWithBaseURL(null, isolated, "text/html", "UTF-8", null)
                }
                main.postDelayed(timeout, TIMEOUT_MS)

                cont.invokeOnCancellation {
                    main.post { runCatching { webView.destroy() } }
                }
            }
        } ?: SmokeTestResult(false, errors = listOf("冒烟测试超时"), message = "smoke-timeout")
    }

    /** 同 WebView 内隔离 iframe：沙箱只放行脚本，探针与游戏都运行在 iframe 中。 */
    private fun buildIsolatedFrameHtml(gameHtml: String): String {
        val escaped = gameHtml
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("</", "<\\/")
        return """
            <!DOCTYPE html><html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
            <style>html,body,iframe{margin:0;padding:0;width:100%;height:100%;border:0;background:#000;}</style>
            </head><body>
            <iframe id="__ww_game_frame" sandbox="allow-scripts allow-same-origin" style="width:100vw;height:100vh;display:block"></iframe>
            <script>
            (function(){
              var frame = document.getElementById('__ww_game_frame');
              frame.srcdoc = '$escaped';
            })();
            </script>
            </body></html>
        """.trimIndent()
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
                passed = passed && errors.isEmpty() && consoleErrors.isEmpty(),
                framesRun = frames,
                errors = errors + consoleErrors,
                message = if (passed) "smoke-ok" else "smoke-failed"
            )
        } catch (_: Exception) {
            fallback
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
