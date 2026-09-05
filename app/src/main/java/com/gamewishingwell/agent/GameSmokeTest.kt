package com.gamewishingwell.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
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
     * 额外真实调用 restart() 完整重开后复跑半程帧，验证重开契约。
     * [scenariosJson] 为功能断言（均衡/精品档，标准 JSON 数组文本，null=无断言）：
     * 主跑帧通过后在同一探针内逐条执行（点按/拖动→推进帧→比对 __wwDebugState() 快照）。
     * [landscape] 为画面方向（Game Schema 的 screenOrientation==横板 时 true）：
     * 沙箱以横屏视口（640×360）运行，与真实游戏页的横屏呈现一致；收尾断言主画布
     * 宽>高（竖版游戏按竖屏视口运行并断言高>宽），拦"口头横屏化、画面仍竖版"的假交付。
     */
    suspend fun run(
        html: String,
        deep: Boolean = false,
        scenariosJson: String? = null,
        landscape: Boolean = false
    ): SmokeTestResult
}

@kotlinx.serialization.Serializable
data class SmokeTestResult(
    val passed: Boolean,
    val framesRun: Int = 0,
    val errors: List<String> = emptyList(),
    val message: String? = null,
    /** 功能断言执行总数（无断言时为 0）。 */
    val scenarioTotal: Int = 0,
    /** 功能断言通过数。 */
    val scenarioPassed: Int = 0,
    /** 断言结果摘要（"系统/名称 通过|失败:原因"），供有据自检与决策日志使用。 */
    val scenarioResults: List<String> = emptyList(),
    /** 沙箱总尝试次数（设施类失败时 runner 重建 WebView 重试）。 */
    val attempts: Int = 1,
    /** 探针是否已定稿（deep 复跑中途 framesRun≥MAX 不算完成——修复宿主提前收单竞态）。 */
    val finalized: Boolean = false
)

object NoopSmokeTestRunner : SmokeTestRunner {
    override suspend fun run(html: String, deep: Boolean, scenariosJson: String?, landscape: Boolean): SmokeTestResult =
        SmokeTestResult(passed = true, message = "noop-smoke-runner")
}

/**
 * 沙箱校验探针：把 requestAnimationFrame 改成确定性 tick 队列，帧推进由宿主轮询驱动——
 * 探针暴露 window.__wwPump()（每调用一次同步推进一批帧），不自排程：离屏 WebView 的
 * setTimeout 被节流至约 1 次/秒，自排程会把 24 帧拖成 25~35 秒；宿主经 evaluateJavascript
 * 驱动不经定时器队列，24 帧约 5 秒完成。DOMContentLoaded（游戏脚本注册 rAF）前不推进。
 * 任何帧内异常、全局异常（含事件回调）与 console error 都会被捕获；
 * 多点位触控派发（touchstart/touchend/click + 一次拖动）在首帧后与跑帧中段各一轮；
 * localStorage 替换为内存 stub；跑满帧后做白屏检测、顶部平台按钮角落扫描
 * 与可观测性不变量断言（__wwDebugState()：负血量实体/NaN 数值/实体泄漏）；
 * 内部超时仅作宿主失效时的看门狗。
 *
 * 沙箱是运行正确性的唯一校验来源：passed = 跑满 tick 且无任何错误文本。
 */
object SmokeTestProbe {
    /** 确定性 tick 帧数：每帧 50ms 游戏时间，共约 1.2s——覆盖出怪、数值变动、状态切换等早期玩法。 */
    const val MAX_FRAMES = 24
    /** 平台按钮悬浮区高度（px）：左上"返回"与右上"设置"按钮悬浮在游戏画面之上。 */
    const val RESERVED_TOP_PX = 110
    /** 平台按钮悬浮区宽度（px）：只执法两个角落——顶部其余区域可自由布局，无保留区留白。 */
    const val RESERVED_CORNER_W = 140
    private const val MARKER = "__wwSmokeInstalled"

    fun inject(html: String, deep: Boolean = false, scenariosJson: String? = null, landscape: Boolean = false): String {
        if (html.contains(MARKER)) return html
        // 场景断言数据用 <script type="application/json"> 承载（放在探针脚本之前，
        // 解析时元素已可用）：避免把 JSON 内嵌进 JS 字符串字面量的转义问题；
        // "</" 转义为 "<\/" 是合法 JSON 转义，防止内容意外闭合标签。
        val dataTag = if (!scenariosJson.isNullOrBlank()) {
            val safe = scenariosJson.replace("</", "<\\/")
            """<script type="application/json" id="__wwScenarioData">$safe</script>"""
        } else {
            ""
        }
        val script = """
            <script>
            (function(){
              if (window.$MARKER) return;
              window.$MARKER = true;
              window.__wwSmokeResult = {passed:false, framesRun:0, errors:[]};
              window.__wwSmokeFinalized = false;
              window.__wwSmokePending = [];
              // 功能断言数据（宿主注入的 scenarios.json，标准 JSON 数组）：
              // 主跑帧通过后在 finalize 路径里逐条确定性执行。
              var __scenData = [];
              try {
                var __dataEl = document.getElementById('__wwScenarioData');
                if (__dataEl && __dataEl.textContent) {
                  var __parsedScen = JSON.parse(__dataEl.textContent);
                  if (Object.prototype.toString.call(__parsedScen) === '[object Array]' && __parsedScen.length) {
                    __scenData = __parsedScen.slice(0, ${GameScenarios.MAX_SCENARIOS});
                  }
                }
              } catch (e) {}
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
              var __finalized = false; // 终态防覆盖：deep 复跑会重置 ticked，超时回调不得覆盖已通过结果
              var __pokedInitial = false; // 首帧后的首轮交互只做一次
              // deep（精品档）：restart() 真实重开后复跑半程帧，验证"完整重开"契约。
              var __deep = ${if (deep) "true" else "false"};
              // 画面方向期望（宿主按 Game Schema 注入）：横板断言主画布宽>高，
              // 竖版断言高>宽。getBoundingClientRect 反映 transform 后的视觉盒，
              // 旋转画布实现的横板游戏同样按宽>高判定。
              var __landscape = ${if (landscape) "true" else "false"};
              var phase = 1;   // 1=首次运行 2=restart 后复跑
              var limit = ${MAX_FRAMES};
              window.requestAnimationFrame = function(cb){
                // 阈值用动态 limit（主跑帧/场景阶段/deep 复跑各有预算）：
                // 场景阶段会重置 ticked 并给独立帧预算，合成队列须保持接管，
                // 否则第 24 帧后新注册的 rAF 会落到离屏被冻结的真实定时器上。
                if (ticked < limit) { window.__wwSmokePending.push(cb); return window.__wwSmokePending.length; }
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
              // ---------- 功能断言阶段（主跑帧通过后、deep 复跑前，同步执行） ----------
              // 每条断言：restart() 重置 → 按 steps 派发百分比输入 → 独立帧预算推进 →
              // 对 __wwDebugState() 快照求值 expect 布尔表达式。全部同步跑在触达
              // finalize 的那一次 pump 调用内，宿主读到终态时断言已完成。
              var __scenLog = [];
              function scenStep(){
                if (ticked >= limit) return;
                ticked++;
                var now = ticked * 50;
                var batch = window.__wwSmokePending.splice(0, window.__wwSmokePending.length);
                for (var i = 0; i < batch.length; i++) {
                  try { batch[i](now); }
                  catch (e) { window.__wwSmokeResult.errors.push(String(e && e.message || e)); }
                }
              }
              function pctCoord(v, total){ var n = Number(v); if (!isFinite(n)) n = 50; return total * (n / 100); }
              function pokeAtPct(xp, yp){
                try {
                  var fallback = document.querySelector('canvas') || document.body;
                  var vw = window.innerWidth || 360, vh = window.innerHeight || 640;
                  pokePoint(fallback, pctCoord(xp, vw), pctCoord(yp, vh));
                } catch (e) {}
              }
              function dragAtPct(x0, y0, x1, y1){
                try {
                  var fallback = document.querySelector('canvas') || document.body;
                  var vw = window.innerWidth || 360, vh = window.innerHeight || 640;
                  dragTouch(fallback, pctCoord(x0, vw), pctCoord(y0, vh), pctCoord(x1, vw), pctCoord(y1, vh));
                } catch (e) {}
              }
              function runScenarioPhase(){
                if (!__scenData.length) return;
                // 已有运行错误（含不变量断言失败）时断言结果无意义：先修运行问题。
                if (window.__wwSmokeResult.errors.length) return;
                if (typeof window.__wwDebugState !== 'function') return;
                for (var i = 0; i < __scenData.length; i++) {
                  var sc = __scenData[i];
                  if (!sc || !sc.expect) continue;
                  var entry = {name: String(sc.name || sc.id || ('scenario-' + i)), system: String(sc.system || ''), ok: false, reason: ''};
                  var errBefore = window.__wwSmokeResult.errors.length;
                  try {
                    if (typeof window.restart === 'function') { window.restart(); }
                  } catch (e0) {
                    window.__wwSmokeResult.errors.push('scenario-restart-error: 执行断言「' + entry.name + '」前 restart() 抛错：' + String(e0 && e0.message || e0));
                    entry.reason = 'restart() 抛错';
                    __scenLog.push(entry);
                    continue;
                  }
                  window.__wwSmokePending.length = 0;
                  var savedTicked = ticked, savedLimit = limit;
                  ticked = 0; limit = ${GameScenarios.MAX_FRAMES_PER_SCENARIO};
                  var steps = (sc.steps && sc.steps.length) ? sc.steps : [{frames: ${GameScenarios.DEFAULT_FRAMES}}];
                  for (var sI = 0; sI < steps.length && sI < ${GameScenarios.MAX_STEPS}; sI++) {
                    var st = steps[sI];
                    if (!st) continue;
                    if (st.tap && st.tap.length >= 2) {
                      pokeAtPct(st.tap[0], st.tap[1]);
                    } else if (st.drag && st.drag.length >= 4) {
                      dragAtPct(st.drag[0], st.drag[1], st.drag[2], st.drag[3]);
                    } else if (st.frames) {
                      var n = Math.min(Math.max(1, st.frames | 0), ${GameScenarios.MAX_FRAMES_PER_SCENARIO});
                      for (var fI = 0; fI < n && ticked < limit; fI++) { scenStep(); }
                    }
                  }
                  // 收尾：把剩余帧预算跑完，让已注册的 rAF 回调全部执行完毕再取快照。
                  while (ticked < limit) { scenStep(); }
                  ticked = savedTicked; limit = savedLimit;
                  var newErrs = window.__wwSmokeResult.errors.length - errBefore;
                  var snap = null;
                  try { snap = window.__wwDebugState(); } catch (e2) { snap = null; }
                  var verdict = false, why = '';
                  if (newErrs > 0) {
                    why = '场景执行期间出现运行错误（见 errors）';
                  } else if (!snap || typeof snap !== 'object') {
                    why = '__wwDebugState() 未返回状态对象';
                  } else {
                    try {
                      // 断言表达式在此求值（探针基础设施，非游戏代码，不受安全契约约束）。
                      verdict = !!new Function('s', 'return (' + String(sc.expect).slice(0, ${GameScenarios.MAX_EXPECT_LEN}) + ');')(snap);
                    } catch (e3) {
                      why = '断言表达式执行出错：' + String(e3 && e3.message || e3);
                    }
                  }
                  entry.ok = verdict;
                  if (!verdict && !why) {
                    var snapStr = '';
                    try { snapStr = JSON.stringify(snap); } catch (e4) { snapStr = String(snap); }
                    if (snapStr.length > 300) snapStr = snapStr.slice(0, 300) + '…';
                    why = '断言不满足；实际快照 ' + snapStr;
                  }
                  entry.reason = why;
                  if (!verdict) {
                    window.__wwSmokeResult.errors.push('scenario-fail[' + (entry.system ? entry.system + '/' : '') + entry.name + ']: ' + why + '（断言：' + String(sc.expect) + '）');
                  }
                  __scenLog.push(entry);
                }
                window.__wwSmokeResult.scenarios = __scenLog;
              }
              // 帧推进为宿主驱动：探针不自排程——离屏 WebView 的页面生命周期事件与定时器
              // 可能被 Chromium 整体冻结（DOMContentLoaded 不派发、setTimeout 不运行），
              // 但 evaluateJavascript 同步执行不受影响。因此不依赖任何事件/定时器：
              // pump 每次实时读 document.readyState（属性同步反映解析状态），解析完成即推进。
              function pump(){
                if (__finalized) return;
                if (document.readyState === 'loading') return;
                if (ticked < limit) {
                  ticked++;
                  window.__wwSmokeResult.framesRun = ticked; // 实时同步：宿主与日志可观测中间进度
                  window.__wwPumpCount = (window.__wwPumpCount || 0) + 1;
                  var now = ticked * 50; // 每帧 50ms 游戏时间（dt 恰为健壮性契约的钳制上限）
                  var batch = window.__wwSmokePending.splice(0, window.__wwSmokePending.length);
                  for (var i = 0; i < batch.length; i++) {
                    try { batch[i](now); }
                    catch (e) { window.__wwSmokeResult.errors.push(String(e && e.message || e)); }
                  }
                  if (phase === 1 && !__pokedInitial) {
                    __pokedInitial = true;
                    pokeTouch(); // 首帧后立即交互：触发"开始"类按钮让游戏真正进入运行态
                  } else if (phase === 1 && ticked === ${MAX_FRAMES / 2}) {
                    pokeTouch(); // 中段再交互一轮：运行态后的处理器也得到执行
                  }
                  if (ticked < limit) return;
                  // 推满帧的本次调用直接落入收尾检查：宿主在 f==limit 的同一次轮询里
                  // 即可读到终态 passed，避免"帧已满但未 finalize"的空档被误判为设施异常。
                }
                if (phase === 1) {
                  restartPresenceCheck();
                  blankCheck();
                  reservedAreaCheck();
                  orientationCheck();
                  debugStateCheck(false);
                  // 功能断言：主跑帧与不变量检查全绿后才执行（有错时先修运行问题），
                  // 之后才进入 deep 复跑——断言自身会调用 restart() 重置。
                  runScenarioPhase();
                  if (__deep) {
                    phase = 2; ticked = 0; limit = Math.floor(${MAX_FRAMES} / 2);
                    exerciseRestart();
                    return;
                  }
                } else {
                  blankCheck();
                  debugStateCheck(true);
                }
                __finalized = true;
                window.__wwSmokeFinalized = true; // 宿主完成判定的权威信号（deep 复跑中途不算完成）
                window.__wwSmokeResult = {passed: window.__wwSmokeResult.errors.length === 0, framesRun: (phase === 2 ? ${MAX_FRAMES} + ticked : ticked), errors: window.__wwSmokeResult.errors, scenarios: __scenLog};
                return;
              }
              // 宿主驱动入口：一次调用可推进多批帧（降低 evaluateJavascript 往返次数，
              // 每次往返的回调延迟在后台 WebView 上可能达数百毫秒）。
              window.__wwPump = function(n){
                n = n || 1;
                for (var i = 0; i < n; i++) {
                  pump();
                  if (__finalized) break;
                }
              };
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
              // 平台按钮角落执法（平台契约）：左上/右上角各约 ${RESERVED_CORNER_W}x${RESERVED_TOP_PX}px 是
              // 平台"返回/设置"按钮悬浮区——角落内出现任何可交互元素即失败（会被平台按钮遮挡而无法点击）。
              // 顶部其余区域（含整条顶带）可自由布局画面/HUD/文字，无保留区留白要求。
              function reservedAreaCheck(){
                try {
                  var bad = [];
                  var vw = window.innerWidth || 360;
                  var cornerW = ${RESERVED_CORNER_W};
                  var els = document.querySelectorAll('button,a,[role="button"],input,select,textarea,[onclick]');
                  for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    if (el.id && String(el.id).indexOf('__ww') === 0) continue;
                    var r = el.getBoundingClientRect();
                    if (r.width <= 0 || r.height <= 0) continue;
                    var st = null;
                    try { st = window.getComputedStyle(el); } catch (e0) {}
                    if (st && (st.display === 'none' || st.visibility === 'hidden' || st.pointerEvents === 'none')) continue;
                    if (r.top < ${RESERVED_TOP_PX} && (r.left < cornerW || r.right > vw - cornerW)) {
                      var label = ((el.innerText || el.value || el.getAttribute('aria-label') || el.tagName || '') + '').trim().slice(0, 16);
                      bad.push(el.tagName.toLowerCase() + (label ? '(' + label + ')' : '') + '@' + Math.round(r.left) + ',' + Math.round(r.top));
                    }
                  }
                  if (bad.length) {
                    window.__wwSmokeResult.errors.push('top-corner-reserved: 左上/右上角平台按钮悬浮区（约' + cornerW + 'x' + ${RESERVED_TOP_PX} + 'px）出现可交互元素（会被平台返回/设置按钮遮挡无法点击）：' + bad.slice(0, 3).join(' | ') + '；请将这些元素移出两个角落，顶部其余区域可自由布局');
                  }
                } catch (e) {}
              }
              // 画面方向断言（Game Schema 契约）：取面积最大的可见 canvas 为主画布，
              // 其视觉宽高比必须与 design_schema 的 orientation 一致。拦"把关卡做成
              // 横向卷轴但画面仍是竖版"的假横板——这正是玩家投诉"说是横屏实际竖屏"的来源。
              // 同时检查铺满程度：主画布宽度应占视口宽 ≥85%、高度占 ≥60%——拦"固定逻辑
              // 分辨率 + 等比缩放居中"的黑边适配（宽屏左右大片空白）。
              // 无 canvas 的 DOM 游戏跳过（内容检测由白屏检查兜底）。
              function orientationCheck(){
                try {
                  var canvases = document.querySelectorAll('canvas');
                  var main = null, mainArea = 0;
                  for (var i = 0; i < canvases.length; i++) {
                    var r = canvases[i].getBoundingClientRect();
                    if (r.width <= 1 || r.height <= 1) continue;
                    var area = r.width * r.height;
                    if (area > mainArea) { mainArea = area; main = r; }
                  }
                  if (!main) return;
                  var w = Math.round(main.width), h = Math.round(main.height);
                  if (__landscape && w <= h) {
                    window.__wwSmokeResult.errors.push('orientation-mismatch: 画面方向应为横板（主画布宽>高），实际主画布为 ' + w + 'x' + h + '。横板游戏不能只把关卡做成横向卷轴而保持竖版/窄幅画面：平台会以横屏视口加载，直接以窗口实际尺寸为逻辑分辨率全屏铺满即可，不要旋转画布');
                  } else if (!__landscape && h <= w) {
                    window.__wwSmokeResult.errors.push('orientation-mismatch: 画面方向应为竖版（主画布高>宽），实际主画布为 ' + w + 'x' + h + '；请按竖屏视口布局');
                  }
                  var vw = window.innerWidth || 0, vh = window.innerHeight || 0;
                  if (vw > 0 && vh > 0 && main.width >= vw * 0.5) {
                    if (main.width < vw * 0.85 || main.height < vh * 0.6) {
                      window.__wwSmokeResult.errors.push('canvas-not-fullscreen: 主画布 ' + w + 'x' + h + ' 未铺满视口 ' + Math.round(vw) + 'x' + Math.round(vh) + '（要求宽≥85%、高≥60%）。禁止固定逻辑分辨率后等比缩放居中留黑边——应以窗口实际尺寸为逻辑分辨率全屏自适应');
                    }
                  }
                } catch (e) {}
              }
              // 看门狗已移除：离屏页面的 setTimeout 可能被冻结（永不触发）；
              // 超时的真边界是宿主侧外层超时（TIMEOUT_MS），frames=0 的超时由上层判设施异常。
            })();
            </script>
        """.trimIndent()

        val blob = dataTag + script
        return when {
            html.contains("</head>", ignoreCase = true) ->
                html.replaceFirst("</head>", "$blob</head>", ignoreCase = true)
            html.contains("<body", ignoreCase = true) ->
                html.replaceFirst("<body", "$blob<body", ignoreCase = true)
            else -> insertAfterRootTags(blob, html)
        }
    }

    /**
     * 无 head/body 的兜底插入：脚本必须落在 DOCTYPE 与 <html> 开标签之后——
     * 前插到 DOCTYPE 之前会触发 quirks 模式，布局/视口行为与真机不一致，
     * 沙箱结论失真。连 <html> 都没有时原样前插（页面本就无标准模式可言）。
     */
    private fun insertAfterRootTags(blob: String, html: String): String {
        htmlOpenTagRegex.find(html)?.let { m ->
            return html.replaceRange(m.range, m.value + "\n" + blob)
        }
        doctypeRegex.find(html)?.let { m ->
            return html.replaceRange(m.range, m.value + "\n" + blob)
        }
        return blob + html
    }

    private val htmlOpenTagRegex = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)
    private val doctypeRegex = Regex("^\\s*<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE)
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
    override suspend fun run(
        html: String,
        deep: Boolean,
        scenariosJson: String?,
        landscape: Boolean
    ): SmokeTestResult {
        val prepared = HtmlEnhancer.inject(SmokeTestProbe.inject(html, deep, scenariosJson, landscape))
        val timeoutMs = if (deep) DEEP_TIMEOUT_MS else TIMEOUT_MS
        // 设施类失败（零帧冻结/结果不可读）重建 WebView 重试：实测该故障间歇性发作
        // （曾连续五回合在 LLM 工作全部完成后死在沙箱、之后自愈），新实例大概率落到
        // 健康 renderer 上；游戏自身错误（有错误文本/帧有推进）不属于此类，不重试。
        var last = SmokeTestResult(false, errors = listOf("冒烟测试超时"), message = "smoke-timeout")
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = runOnce(prepared, timeoutMs, landscape)
            last = result.copy(attempts = attempt + 1)
            if (!isRetryableInfraFailure(result)) return last
            if (attempt < MAX_ATTEMPTS - 1) {
                android.util.Log.w("SmokeRunner", "infra failure (attempt ${attempt + 1}/$MAX_ATTEMPTS), recreating WebView to retry")
            }
        }
        return last
    }

    private suspend fun runOnce(prepared: String, timeoutMs: Long, landscape: Boolean): SmokeTestResult =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                lateinit var webView: WebView
                var finished = false
                var polls = 0
                var pollStarted = false
                var lastRaw: String? = null
                // 探针活性遥测：frames 与 pumpCount 双零 = evaluateJavascript 从未真正
                // 驱动过探针（renderer 冻结）或页面卡在 loading（readyState 永远 loading，
                // pump 前置返回）。两者都是设施问题，不必等外层超时。
                val startedAt = android.os.SystemClock.elapsedRealtime()
                var lastFrames = 0
                var lastPumpCount = 0

                var livenessRunnable: Runnable? = null
                var timeoutRunnable: Runnable? = null

                /** 取消/超时共用的收尾：停轮询链、解绑回调、销毁 WebView。 */
                fun stopAll() {
                    finished = true
                    timeoutRunnable?.let { main.removeCallbacks(it) }
                    livenessRunnable?.let { main.removeCallbacks(it) }
                    // 用完即毁（根修复）：泄漏的 WebView 实例会持续占用共享渲染器的
                    // tile 内存与 JS 堆，累积后新页面无法绘制（tile memory limits
                    // exceeded）→ 探针停滞 → "运行验证环境异常"。摧毁后资源归还，
                    // 同进程内多轮沙箱不再互相拖垮。
                    main.post { runCatching { webView.destroy() } }
                }

                val finish: (SmokeTestResult) -> Unit = { result ->
                    if (!finished) {
                        stopAll()
                        cont.resumeWith(Result.success(result))
                    }
                }

                val timeout = Runnable {
                    // 超时也必须走 finish()：它负责 destroy WebView。超时恰恰发生在
                    // renderer 最病态（tile memory 已紧张）的时刻，直接 resume 会把
                    // 病态实例泄漏在共享渲染器上，后续沙箱连环"设施异常"的根源之一。
                    if (!finished) {
                        android.util.Log.w("SmokeRunner", "outer timeout after polls=$polls last=$lastRaw")
                        finish(SmokeTestResult(false, errors = listOf("冒烟测试超时"), message = "smoke-timeout"))
                    }
                }
                timeoutRunnable = timeout

                // 活性看门狗：宽限期后仍双零 → 提前以 smoke-frozen 终止本次尝试
                // （可重试标记），把 80s 干等压缩成 ~15s。
                val liveness = Runnable {
                    if (!finished && lastFrames <= 0 && lastPumpCount <= 0) {
                        android.util.Log.w("SmokeRunner", "liveness check failed after ${LIVENESS_GRACE_MS / 1000}s (frames=0 pump=0), abort early")
                        finish(SmokeTestResult(false, errors = listOf("冒烟测试超时"), message = "smoke-frozen"))
                    }
                }
                livenessRunnable = liveness

                val consoleErrors = mutableListOf<String>()

                /**
                 * 渲染器健康握手：空白页上执行一次最小 JS 并等待回声。回声正常才载入
                 * 游戏页面；连续无响应则按可重试设施失败终止（message=smoke-warmup），
                 * 由 runner 销毁重建 WebView 后重试——把"神秘停滞"变成快速确定性重建。
                 */
                fun handshake(attempt: Int) {
                    if (finished) return
                    webView.evaluateJavascript("1+1") { echo ->
                        if (finished) return@evaluateJavascript
                        if (echo != null) {
                            android.util.Log.d("SmokeRunner", "warmup echo ok (attempt=$attempt)")
                            webView.loadDataWithBaseURL(null, prepared, "text/html", "UTF-8", null)
                        } else if (attempt >= MAX_WARMUP_CHECKS) {
                            android.util.Log.w("SmokeRunner", "warmup failed after $attempt checks")
                            finish(
                                SmokeTestResult(
                                    false,
                                    errors = listOf("沙箱预热失败：渲染器对 JS 桥无响应"),
                                    message = MESSAGE_WARMUP_FAILED
                                )
                            )
                        } else {
                            main.postDelayed({ handshake(attempt + 1) }, WARMUP_INTERVAL_MS)
                        }
                    }
                }

                fun poll() {
                    if (finished) return
                    polls++
                    // 每次轮询顺手驱动一帧批次（宿主驱动帧推进：evaluateJavascript 不经定时器队列，
                    // 不受离屏 WebView 后台节流影响），再读取探针终态。
                    val js = "(function(){try{if(window.__wwPump){window.__wwPump($FRAMES_PER_POLL);}}catch(pumpErr){}" +
                        "try{var r=window.__wwSmokeResult;return JSON.stringify({p:r&&r.passed!==undefined&&r.passed,f:r&&r.framesRun||0,e:r&&r.errors||[],fin:!!window.__wwSmokeFinalized,rs:document.readyState,m:!!window.__wwSmokeInstalled,pfn:typeof window.__wwPump,pc:window.__wwPumpCount||0,sc:(r&&r.scenarios)||[]});}catch(err){return JSON.stringify({p:false,f:0,e:[String(err)],done:true,rs:document.readyState});}})()"
                    webView.evaluateJavascript(js) { value ->
                        // 取消与回调的竞态兜底：stopAll 置位后 WebView 已销毁/结果已无意义，
                        // 不再续排下一轮（否则对已销毁实例无限 200ms 重轮）。
                        if (finished) return@evaluateJavascript
                        lastRaw = value
                        if (polls <= 8) {
                            android.util.Log.d("SmokeRunner", "poll#$polls raw=${value?.take(220)}")
                        }
                        // 活性遥测更新 + 宽限期后双零的提前终止（回调路径）。
                        decodeProbeObject(value)?.let { telemetry ->
                            (telemetry["f"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { lastFrames = it }
                            (telemetry["pc"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { lastPumpCount = it }
                        }
                        if (android.os.SystemClock.elapsedRealtime() - startedAt > LIVENESS_GRACE_MS &&
                            lastFrames <= 0 && lastPumpCount <= 0
                        ) {
                            finish(SmokeTestResult(false, errors = listOf("冒烟测试超时"), message = "smoke-frozen"))
                            return@evaluateJavascript
                        }
                        val parsed = parseProbeResult(value, emptyList())
                        // 完成判定不重复解析原文：framesRun 已实时同步（>=MAX 即跑满），
                        // errors 非空即出错，passed 为探针 finalize 的终态。
                        // 完成判定以探针定稿为准：deep 复跑期间 framesRun 已达 MAX 但未定稿，
                        // 不能提前收单（历史 bug：读到中间态被判"结果不可读"设施异常）。
                        if (parsed.errors.isNotEmpty() || parsed.passed || parsed.finalized) {
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

                /**
                 * 单例轮询链入口：真实页 onPageFinished 与"个别环境 onPageFinished
                 * 不触发"的兜底定时器都经此启动（此前两条链并行，帧推进双倍速、
                 * 取消时要停两处）。已启动/已收尾则 no-op。必须定义在 poll 之后：
                 * 局部函数引用（::poll）不允许前向引用。
                 */
                fun startPollingOnce(delayMs: Long = 0L) {
                    if (finished || pollStarted) return
                    pollStarted = true
                    main.postDelayed(::poll, delayMs)
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
                    // 视口方向与真实游戏页一致：横板游戏在 GameScreen 中以横屏呈现，
                    // 沙箱同样按横屏 640×360 运行（竖版按 360×640），方向断言才有意义。
                    if (landscape) view.layout(0, 0, 640, 360) else view.layout(0, 0, 360, 640)
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
                        var blankChecked = false
                        override fun onPageFinished(view: WebView, url: String?) {
                            android.util.Log.d("SmokeRunner", "onPageFinished url=$url")
                            if (!blankChecked) {
                                // 预热门：先在空白页确认 evaluateJavascript 链路活着再载入真实页面。
                                // 病态渲染器在这里数秒内快速失败并重建，不消耗整轮外层超时。
                                blankChecked = true
                                handshake(0)
                            } else {
                                // 真实页面加载完成：启动（唯一一条）轮询链。
                                startPollingOnce(FIRST_POLL_DELAY_MS)
                            }
                        }

                        // 渲染器死亡信号（Chromium 官方回调）：与其等 80s 外层超时，
                        // 不如立刻按可重试设施失败终止本次尝试并重建实例。
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            android.util.Log.w("SmokeRunner", "renderer gone (crashed=${detail.didCrash()})")
                            finish(SmokeTestResult(false, errors = listOf("渲染进程丢失"), message = MESSAGE_RENDERER_GONE))
                            return true
                        }
                    }
                    // 兜底：若 onPageFinished 未触发（个别环境），仍按计划开始轮询；
                    // 正常路径已启动时此处 no-op，不再出现第二条并行轮询链。
                    main.postDelayed({ startPollingOnce() }, FIRST_POLL_DELAY_MS + 2500)
                    view.loadDataWithBaseURL(null, BLANK_HTML, "text/html", "UTF-8", null)
                }
                main.postDelayed(timeout, timeoutMs)
                main.postDelayed(liveness, LIVENESS_GRACE_MS)

                cont.invokeOnCancellation {
                    // 取消（外层超时/调用方协程取消）必须终止轮询链并解绑全部回调：
                    // 此前只销毁 WebView，飞行中的 poll 会继续对已销毁实例
                    // evaluateJavascript（可能抛异常）或按 200ms 无限重排。
                    main.post { stopAll() }
                }
            }
        } ?: SmokeTestResult(false, errors = listOf("冒烟测试超时"), message = "smoke-timeout")

    /**
     * evaluateJavascript 返回值的统一解码：返回的是 JSON 编码字符串（内层引号带转义），
     * 先按 JSON 字符串字面量正确解码再解析对象——直接 removeSurrounding 会留下转义
     * 反斜杠导致解析恒失败（历史遗留 bug：完成判定从未生效，全靠外层超时收尾）。
     */
    private fun decodeProbeObject(raw: String?): JsonObject? {
        if (raw == null) return null
        val decoded = runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull()
            ?: raw.trim().removeSurrounding("\"")
        return runCatching { Json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
    }

    private fun parseProbeResult(raw: String?, consoleErrors: List<String>): SmokeTestResult {
        val fallback = SmokeTestResult(false, errors = consoleErrors, message = "probe-result-unavailable")
        val json = decodeProbeObject(raw) ?: return fallback
        return try {
            val passed = (json["p"] as? JsonPrimitive)?.contentOrNull == "true"
            val frames = (json["f"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val errors = (json["e"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            } ?: emptyList()
            // 功能断言结果：[{name, system, ok, reason}] → 摘要行 + 计数。
            val scenEntries = (json["sc"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                .orEmpty()
            val scenResults = scenEntries.map { entry ->
                val name = (entry["name"] as? JsonPrimitive)?.contentOrNull ?: "scenario"
                val system = (entry["system"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val ok = (entry["ok"] as? JsonPrimitive)?.contentOrNull == "true"
                val reason = (entry["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val label = if (system.isBlank()) name else "$system/$name"
                if (ok) "$label 通过" else "$label 失败:${reason.take(160)}"
            }
            SmokeTestResult(
                passed = passed && errors.isEmpty(),
                framesRun = frames,
                errors = errors + consoleErrors,
                finalized = (json["fin"] as? JsonPrimitive)?.contentOrNull == "true",
                message = if (passed) "smoke-ok" else "smoke-failed",
                scenarioTotal = scenEntries.size,
                scenarioPassed = scenResults.count { it.endsWith(" 通过") },
                scenarioResults = scenResults
            )
        } catch (_: Exception) {
            fallback
        }
    }

    private companion object {
        // 外层兜底超时：仅防挂起（宿主驱动下正常路径数秒完成）。
        const val TIMEOUT_MS = 80_000L
        // deep（精品档）额外跑 restart() 复跑半程（合计约 36 批次）。
        const val DEEP_TIMEOUT_MS = 110_000L
        const val FIRST_POLL_DELAY_MS = 1_200L
        // 冒烟期轮询兼帧驱动：每次轮询调用 __wwPump() 推进 4 批帧（FRAMES_PER_POLL）——
        // 后台 WebView 的 evaluateJavascript 回调延迟可达数百毫秒，多帧合并把往返次数从 24 降到 6；
        // 常规 24 帧约 2~6s、deep 约 3~8s，不受离屏定时器节流影响。
        const val POLL_INTERVAL_MS = 200L
        const val FRAMES_PER_POLL = 4
        // 设施类失败的最大尝试次数（1 次 + 2 次重建重试）；全部耗尽才向上报失败。
        const val MAX_ATTEMPTS = 3
        // 探针活性宽限：超过后仍 frames=0 且 pump=0 判设施冻结，提前终止本次尝试。
        // 健康探针在 2~6s 内即有帧推进，15s 足以避开慢模拟器的正常加载耗时。
        const val LIVENESS_GRACE_MS = 15_000L
        // 渲染器预热握手：JS 桥回声重试上限与间隔（共 5s），无响应按可重试设施失败重建。
        const val MAX_WARMUP_CHECKS = 10
        const val WARMUP_INTERVAL_MS = 500L
        // 预热用空白页（随后才载入真实游戏页面）。
        const val BLANK_HTML = "<html><body></body></html>"
    }
}

/** 沙箱设施类失败的消息标记（isRetryableInfraFailure 与 runner 共用）。 */
internal const val MESSAGE_WARMUP_FAILED = "smoke-warmup"
internal const val MESSAGE_RENDERER_GONE = "smoke-renderer-gone"

/**
 * 设施类失败判定（可重建 WebView 重试）：零帧冻结（smoke-frozen）、结果不可读、
 * 预热握手失败（smoke-warmup）、渲染器死亡（smoke-renderer-gone）属于测试环境
 * 瞬时问题；游戏自身错误（有错误文本）与有帧推进的超时不属于，不重试。
 */
internal fun isRetryableInfraFailure(r: SmokeTestResult): Boolean = when (r.message) {
    "smoke-frozen", "probe-result-unavailable", MESSAGE_WARMUP_FAILED, MESSAGE_RENDERER_GONE -> true
    "smoke-timeout" -> r.framesRun <= 0 && r.errors.all { it == "冒烟测试超时" || it == "smoke-timeout" }
    else -> false
}
