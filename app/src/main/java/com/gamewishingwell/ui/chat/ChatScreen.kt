package com.gamewishingwell.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gamewishingwell.agent.GameSession
import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.ui.rememberContainer
import com.gamewishingwell.ui.viewmodels.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    gameId: Long?,
    resumeDraft: Boolean = false,
    onPlay: () -> Unit,
    onOpenSettings: () -> Unit,
    onSaved: () -> Unit
) {
    val container = rememberContainer()
    val mode = gameId?.let { "game-$it" } ?: if (resumeDraft) "draft" else "new"
    val vm: ChatViewModel = viewModel(
        key = "chat-$mode",
        factory = viewModelFactory { initializer { ChatViewModel(container.gameAgent, gameId, resumeDraft) } }
    )
    val session by vm.session.collectAsState()
    val context = LocalContext.current

    var input by remember { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 列表总条目数 = 消息 + 生成中占位 + 错误卡片占位
    val listItemCount = session.messages.size +
        (if (session.isGenerating) 1 else 0) +
        (if (session.error != null) 1 else 0)

    // 新消息 / 生成开始结束 / 出错时自动滚到底部（下标最大为 count-1，不能滚到 count）
    LaunchedEffect(listItemCount) {
        if (listItemCount > 0) listState.animateScrollToItem(listItemCount - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (gameId != null) "编辑游戏" else if (resumeDraft) "继续创作" else "创作") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            InputRow(
                value = input,
                enabled = !session.isGenerating,
                onValueChange = { input = it },
                onSend = {
                    vm.send(input.trim())
                    input = ""
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(count = session.messages.size, key = { it }) { index ->
                    MessageBubble(session.messages[index])
                }
                if (session.isGenerating) {
                    item(key = "typing") { TypingBubble(streamingText = session.streamingText) }
                }
                val error = session.error
                if (error != null) {
                    item(key = "error") {
                        ErrorCard(
                            message = error,
                            onRetry = { vm.regenerate() },
                            onSettings = onOpenSettings
                        )
                    }
                }
            }
            if (session.currentHtml != null && !session.isGenerating && session.error == null) {
                ActionBar(
                    lastWarning = session.lastWarning,
                    onPlay = onPlay,
                    onSave = { showSaveDialog = true },
                    onRegenerate = { vm.regenerate() }
                )
            }
        }
    }

    if (showSaveDialog) {
        SaveGameDialog(
            defaultTitle = defaultTitle(session),
            onDismiss = { showSaveDialog = false },
            onConfirm = { title ->
                showSaveDialog = false
                vm.saveAs(title) { meta ->
                    if (meta != null) {
                        Toast.makeText(context, "已保存到游戏库", Toast.LENGTH_SHORT).show()
                        onSaved()
                    } else {
                        Toast.makeText(context, "保存失败，请先生成一个游戏", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(msg.content, Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun TypingBubble(streamingText: String?) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "正在生成游戏，请稍候…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!streamingText.isNullOrBlank()) {
                Spacer(Modifier.size(2.dp))
                // 打字机效果：展示模型最近输出的内容（原始代码流）
                Text(
                    if (streamingText.length > 160) "…" + streamingText.takeLast(160) else streamingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ActionBar(
    lastWarning: String?,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onRegenerate: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (lastWarning != null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                Text(
                    "提示：$lastWarning",
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay, modifier = Modifier.weight(1.6f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("立即游玩")
            }
            FilledTonalButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text("保存")
            }
            FilledTonalButton(onClick = onRegenerate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("重做")
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("再试一次") }
                TextButton(onClick = onSettings) { Text("去设置") }
            }
        }
    }
}

@Composable
private fun InputRow(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("描述你想玩的游戏，例如：做一个接水果的小游戏") },
                maxLines = 3,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = enabled && value.isNotBlank()
            ) {
                Text("发送")
            }
        }
    }
}

@Composable
private fun SaveGameDialog(
    defaultTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf(defaultTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun defaultTitle(session: GameSession): String {
    val first = session.messages.firstOrNull { it.isUser }?.content ?: return ""
    return first.trim().replace(Regex("\\s+"), " ").take(20)
}
