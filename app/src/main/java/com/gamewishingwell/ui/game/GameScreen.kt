package com.gamewishingwell.ui.game

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gamewishingwell.agent.BrowserInterventions
import com.gamewishingwell.agent.GameSession
import com.gamewishingwell.agent.HtmlEnhancer
import com.gamewishingwell.ui.rememberContainer
import com.gamewishingwell.ui.viewmodels.GameViewModel

/** 从 Compose 上下文向上找宿主 Activity（LocalContext 通常是 Activity，稳妥起见遍历包装链）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GameScreen(
    source: String,
    gameId: Long,
    onBack: () -> Unit,
    onEdit: (Long?) -> Unit,
    onHome: () -> Unit
) {
    val container = rememberContainer()
    val vm: GameViewModel = viewModel(
        factory = viewModelFactory { initializer { GameViewModel(container.gameRepository, container.agentHub) } }
    )
    val html by vm.html.collectAsState()
    val jsError by vm.jsError.collectAsState()
    val loadedGameId by vm.loadedGameId.collectAsState()
    // 参数外置契约：覆盖注入（loadHtml 消费）与面板入口显隐（GAME_CONFIG 探测）
    val configOverride by vm.configOverride.collectAsState()
    val configSupported by vm.configSupported.collectAsState()
    val configParams by vm.configParams.collectAsState()
    val context = LocalContext.current
    // 保存护栏的 UI 侧：生成中 currentHtml 是未验收的中间版本，此刻入库会把半成品
    // 固化为"已保存版本"（agent 侧 saveCurrentGame 为最终闸门，此处先禁用入口）。
    val agentSession by vm.agentSession.collectAsState()
    val agentGenerating = agentSession ?: GameSession()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadedHtml by remember { mutableStateOf<String?>(null) }
    // 与 loadedHtml 同源的覆盖快照：html 不变而覆盖变化（参数保存后 reload）时
    // 仍要整页重载（参数在游戏 init 时读取一次，重载即生效）。
    var loadedOverride by remember { mutableStateOf<String?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showConfigEditor by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(0.8f) }
    var loadDone by remember { mutableStateOf(false) }

    // 游戏设置面板（顶栏右侧入口）：打开时暂停游戏（rAF 挂起），关闭恢复。
    val closeSettings: () -> Unit = {
        showSettings = false
        webView?.evaluateJavascript("window.__wwSetPaused && window.__wwSetPaused(false)", null)
    }
    val restartGame: () -> Unit = {
        // 重开成功与否都不再保留旧的报错覆盖层（restart 契约即"完整重置"）。
        vm.dismissError()
        webView?.evaluateJavascript(
            "(function(){try{if(typeof window.restart==='function'){window.restart();}else{console.error('[游戏错误] 重新游戏失败: 未找到 restart()');}}catch(err){console.error('[游戏错误] 重新游戏失败: ' + err.message);}})()",
            null
        )
    }

    LaunchedEffect(Unit) { vm.load(source, gameId); loadDone = true }

    val landscape by vm.landscape.collectAsState()

    // 画面方向（Game Schema）：横板游戏由平台请求横屏呈现——不依赖模型
    // "canvas 自适应 + 用户自己横握手机"（WebView 不会跟随物理旋转自动变横）。
    // 竖版游戏不强制旋转（沿用系统方向）；离开游戏页时恢复进入前的方向。
    DisposableEffect(landscape) {
        val activity = context.findActivity()
        if (activity != null && landscape) {
            val previous = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose { activity.requestedOrientation = previous }
        } else {
            onDispose { }
        }
    }

    // 沉浸式全屏（横竖屏一致）：游戏页隐藏状态栏——（返回/设置）顶部区域贴屏幕
    // 最顶端无任何留白，游戏画面延伸到绝对顶部（原先状态栏图标区与按钮之间
    // 有一条约 50px 的间隔带）。横划短暂唤出状态栏（transient），离开页面恢复。
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (window != null && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
            // 刘海屏：允许内容延伸进 cutout 区（SHORT_EDGES），横屏两端不留黑/白带。
            if (Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }

    val loadHtml: (WebView, String, String?) -> Unit = { view, h, overrideJson ->
        // 每游戏独立假 origin（子域隔离）：localStorage 的 origin 只由 scheme+host+port
        // 构成、路径不参与——同 host 不同 path 的全部游戏会折叠成同一 origin 共享同一份
        // localStorage（实测事故：两局游戏 ww:save schema 不同，交叉读取触发防御性删除
        // 互相清档）。host 内嵌 source+gameId 后每游戏独立键空间，存档跨重启/覆盖安装
        // 存活，预览与运行区互不污染；假域名不产生任何网络请求（游戏自包含）。
        // 参数覆盖（overrideJson）经 HtmlEnhancer 注入为 window.__wwConfigOverride，
        // 游戏侧 GAME_CONFIG 合并行消费——仅合法 JSON 会走到这里（VM 已过滤）。
        view.loadDataWithBaseURL(
            "https://$source$gameId.ww.local/",
            HtmlEnhancer.inject(h, overrideJson), "text/html", "UTF-8", null
        )
        loadedHtml = h
        loadedOverride = overrideJson
    }

    // 清除存档（平台通用，不依赖游戏配合）：删 ww:save 契约键后整页重载——比 restart()
    // 更彻底（restart 只重置本局，内存中的最高分等跨局状态保留；重载后从空存档全新开局）。
    val clearSaveAndRestart: () -> Unit = {
        val view = webView
        val h = loadedHtml
        val ov = loadedOverride
        view?.evaluateJavascript(
            "(function(){try{localStorage.removeItem('ww:save');}catch(e){}})()"
        ) { _ ->
            if (h != null) loadHtml(view, h, ov)
        }
        closeSettings()
        Toast.makeText(context, "已清除存档", Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (loadDone && html == null) {
            // 游戏已被删除或草稿不存在
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "游戏不存在或已被删除",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.size(12.dp))
                Button(onClick = onHome) { Text("返回首页") }
            }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                val msg = consoleMessage.message() ?: "未知 JS 错误"
                                // 浏览器干预告警（passive preventDefault 等）不弹报错覆盖层：
                                // 不是游戏缺陷，弹给用户只会驱动"修复良性告警"的高危回合
                                // （实测 61 轮重写触摸架构消告警，真实手感反而劣化）。
                                if (!BrowserInterventions.isIntervention(msg)) {
                                    vm.onJsError(msg)
                                }
                            }
                            return true
                        }
                    }
                    webViewClient = WebViewClient()
                    webView = this
                }
            },
            update = { view ->
                val h = html
                // WebView 在 Compose 首次测量前 viewport 高度可能为 0，此时加载 HTML
                // 会让 100vh / 100% 根高度永久计算为 0，导致 Canvas 场景不可见。
                // 因此只在 WebView 已有实际尺寸后加载。html 或参数覆盖任一变化都重载
                // （参数保存后 html 不变、覆盖已变）。
                if (h != null && (loadedHtml != h || loadedOverride != configOverride) &&
                    view.width > 0 && view.height > 0
                ) {
                    loadHtml(view, h, configOverride)
                }
            },
            onRelease = { view ->
                // 离开游戏页销毁 WebView：不销毁会持续占用共享渲染器的 tile 内存/JS 堆，
                // 与沙箱实例累积后互相拖垮（设施故障根因之一）。销毁后清空引用，
                // 避免设置面板等再对已销毁实例调用 evaluateJavascript。
                runCatching { view.destroy() }
                if (webView == view) webView = null
                loadedHtml = null
                loadedOverride = null
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) {
                        val h = html
                        val view = webView
                        if (h != null && view != null && (loadedHtml != h || loadedOverride != configOverride)) {
                            loadHtml(view, h, configOverride)
                        }
                    }
                }
        )

        // 顶部操作条：全透明背景，返回/设置悬浮于游戏画面之上；
        // 内容仍避开状态栏（windowInsetsPadding），深色图标场景由游戏画面自衬。
        Surface(
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .padding(horizontal = 4.dp)
                    // 状态栏已由沉浸式隐藏（inset=0）；保留 padding 作为控制器
                    // 失效设备的防御，正常情况下按钮贴屏幕最顶端无留白。
                    .windowInsetsPadding(WindowInsets.statusBars),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    webView?.evaluateJavascript("window.__wwSetPaused && window.__wwSetPaused(true)", null)
                    showSettings = true
                }) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
                }
            }
        }

        // JS 运行错误覆盖层
        if (jsError != null) {
            Surface(
                color = Color(0xF2262424),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "游戏运行出错",
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        (jsError ?: "").take(300),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.dismissError() }) {
                            Text("关闭", color = Color.White)
                        }
                        Button(onClick = {
                            if (!vm.fixErrorAndGo()) {
                                Toast.makeText(context, "正在生成中，请等本轮完成后再修复", Toast.LENGTH_SHORT).show()
                            } else {
                                onEdit(loadedGameId)
                            }
                        }) {
                            Text("让 AI 修复")
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = closeSettings,
            title = { Text("游戏设置") },
            text = {
                // 横屏短屏（手机 ~360dp 可用高）下内容超出对话框高度——可滚动，
                // 否则尾部的按钮会被裁出屏外（实测 density 480 横屏"参数调整"被裁掉）
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("音量")
                        Text("${(volume * 100).toInt()}%")
                    }
                    Slider(
                        value = volume,
                        onValueChange = {
                            volume = it
                            webView?.evaluateJavascript("window.__wwSetVolume && window.__wwSetVolume($it)", null)
                        },
                        valueRange = 0f..1.5f
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "重新游戏只重置本局（保留存档）；清除存档会删除最高分等跨局记录并重开。首次保存命名入库，之后保存直接覆盖（保持原名），改名可在首页进行。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(8.dp))
                    // 参数调整入口（参数外置契约）：仅运行区（game）且游戏定义了
                    // GAME_CONFIG 时显示——玩家免 LLM 直接改参数（user_config.json）。
                    if (source == "game" && configSupported) {
                        TextButton(onClick = { showConfigEditor = true }) {
                            Text("参数调整")
                        }
                    }
                    TextButton(onClick = clearSaveAndRestart) {
                        Text("清除存档并重开", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 运行区（game，首页游玩入口）不提供保存；预览/草稿页可把编辑区版本保存入库。
                    if (source != "game") {
                        OutlinedButton(
                            enabled = !agentGenerating.isGenerating,
                            onClick = {
                                if (agentGenerating.isGenerating) {
                                    Toast.makeText(context, "正在生成中，请等本轮完成后再保存", Toast.LENGTH_SHORT).show()
                                } else {
                                    closeSettings()
                                    // 仅首次保存询问命名（未命名草稿）；已命名游戏直接覆盖保持原名。
                                    vm.saveGame(
                                        onNeedName = { showSaveDialog = true },
                                        onDone = { ok ->
                                            Toast.makeText(
                                                context,
                                                if (ok) "已保存" else "保存失败",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            if (ok) onHome()
                                        }
                                    )
                                }
                            }
                        ) { Text("保存游戏") }
                    }
                    Button(onClick = {
                        restartGame()
                        closeSettings()
                    }) { Text("重新游戏") }
                }
            },
            dismissButton = {
                TextButton(onClick = closeSettings) { Text("继续游戏") }
            }
        )
    }


    // 参数调整编辑器（参数外置契约·直列编辑）：进页即列全部参数（名称/注释/当前生效值，
    // 覆盖优先）。点任意行内联改值（输入框+确定/取消），可连续修改多项——已改行高亮并可 ↺
    // 撤销单项；底部【保存并生效】把全部修改并入 user_config.json 并整页重载生效（参数在游戏
    // init 读取一次；对局进度由 ww:save 存档契约自动恢复）。免 JSON 语法：数字直接填、布尔
    // true/false、其余按文本原样（parseValueOrString 宽松解析，仓库侧仍校验整体 JSON）。
    if (showConfigEditor) {
        val pending = remember { mutableStateMapOf<String, String>() }
        var editingName by remember { mutableStateOf<String?>(null) }
        var editingValue by remember { mutableStateOf("") }
        var cfgError by remember { mutableStateOf<String?>(null) }

        fun displayValue(el: kotlinx.serialization.json.JsonElement?): String = when (el) {
            null -> ""
            is kotlinx.serialization.json.JsonPrimitive -> el.content
            else -> el.toString()
        }

        AlertDialog(
            onDismissRequest = { showConfigEditor = false },
            title = { Text("参数调整") },
            text = {
                Column {
                    Text(
                        if (pending.isEmpty()) {
                            "点参数行直接修改数值，可连续修改多项后一次保存生效。" +
                                "数值直接填数字，布尔填 true/false，其余按文本原样填写。"
                        } else {
                            "待保存修改 ${pending.size} 项（高亮行，↺ 撤销单项，重置修改全清）。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(4.dp))
                    if (configParams.isEmpty()) {
                        Text(
                            "参数列表解析失败：该游戏的配置不可解析，无法列出参数。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(configParams, key = { it.name }) { p ->
                                val edited = pending[p.name]
                                if (editingName == p.name) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Text(
                                            p.name + if (p.doc.isNotEmpty()) " · ${p.doc}" else "",
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedTextField(
                                                value = editingValue,
                                                onValueChange = {
                                                    editingValue = it
                                                    cfgError = null
                                                },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = {
                                                if (editingValue.isNotBlank()) {
                                                    pending[p.name] = editingValue.trim()
                                                }
                                                editingName = null
                                            }) { Text("确定") }
                                            TextButton(onClick = { editingName = null }) { Text("取消") }
                                        }
                                    }
                                } else {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                editingName = p.name
                                                editingValue = edited ?: displayValue(p.effective)
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                p.name,
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                color = if (edited != null) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (p.doc.isNotEmpty()) {
                                                Text(
                                                    p.doc,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                        Text(
                                            edited ?: displayValue(p.effective),
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = if (edited != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (edited != null) {
                                            TextButton(onClick = { pending.remove(p.name) }) { Text("↺") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    cfgError?.let {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                // 底部同一排：重置修改 / 保存并生效 / 关闭
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = pending.isNotEmpty(),
                        onClick = {
                            pending.clear()
                            editingName = null
                        }
                    ) { Text("重置修改") }
                    Button(
                        enabled = pending.isNotEmpty(),
                        onClick = {
                            val changes = pending.toList()
                            vm.saveUserConfigChanges(changes) { ok ->
                                if (ok) {
                                    showConfigEditor = false
                                    closeSettings()
                                    Toast.makeText(
                                        context, "已保存 ${changes.size} 项更改，重载后生效", Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    cfgError = "保存失败：更改未落盘，请重试"
                                }
                            }
                        }
                    ) { Text("保存并生效") }
                    TextButton(onClick = { showConfigEditor = false }) { Text("关闭") }
                }
            }
        )
    }

    // 首次保存（未命名草稿）命名框：默认名取会话首条用户消息前 20 字。
    if (showSaveDialog) {
        val defaultTitle = remember {
            vm.agentSession.value?.messages
                ?.firstOrNull { it.isUser }?.content?.take(20) ?: ""
        }
        var title by remember { mutableStateOf(defaultTitle) }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存游戏") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("游戏名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    showSaveDialog = false
                    vm.saveDraft(title) { ok ->
                        Toast.makeText(
                            context,
                            if (ok) "已保存到游戏库" else "保存失败",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (ok) onHome()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            }
        )
    }
}
