package com.gamewishingwell.ui.game

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gamewishingwell.agent.HtmlEnhancer
import com.gamewishingwell.ui.rememberContainer
import com.gamewishingwell.ui.viewmodels.GameViewModel

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
        factory = viewModelFactory { initializer { GameViewModel(container.gameRepository, container.gameAgent) } }
    )
    val html by vm.html.collectAsState()
    val jsError by vm.jsError.collectAsState()
    val loadedGameId by vm.loadedGameId.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadedHtml by remember { mutableStateOf<String?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var loadDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load(source, gameId); loadDone = true }

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
                    settings.setSupportZoom(false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                vm.onJsError(consoleMessage.message() ?: "未知 JS 错误")
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
                if (h != null && loadedHtml != h) {
                    // 渲染增强副本：注入错误捕获器，让 JS 报错可靠上报到覆盖层
                    view.loadDataWithBaseURL(null, HtmlEnhancer.inject(h), "text/html", "UTF-8", null)
                    loadedHtml = h
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 顶部操作条
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .windowInsetsPadding(WindowInsets.statusBars),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(
                    if (source == "game") "游玩中" else "游戏预览",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (source == "draft") {
                    TextButton(onClick = { showSaveDialog = true }) {
                        Text("保存", color = Color.White)
                    }
                }
            }
        }

        // 底部操作条
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomAction(Icons.Filled.Refresh, "重玩") { webView?.reload() }
                BottomAction(null, "复制HTML") {
                    html?.let {
                        clipboard.setText(AnnotatedString(it))
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                }
                BottomAction(Icons.Filled.Edit, "修改") { onEdit(loadedGameId) }
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
                            vm.fixErrorAndGo()
                            onEdit(loadedGameId)
                        }) {
                            Text("让 AI 修复")
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        val defaultTitle = remember {
            container.gameAgent.session.value.messages
                .firstOrNull { it.isUser }?.content?.take(20) ?: ""
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

@Composable
private fun BottomAction(icon: ImageVector?, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        } else {
            Spacer(Modifier.size(22.dp))
        }
        Spacer(Modifier.size(2.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}
