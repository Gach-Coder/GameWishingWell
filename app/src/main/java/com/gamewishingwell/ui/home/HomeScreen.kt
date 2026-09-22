package com.gamewishingwell.ui.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gamewishingwell.data.GameMeta
import com.gamewishingwell.data.GameRepository
import com.gamewishingwell.ui.WwExportIcon
import com.gamewishingwell.ui.WwImportIcon
import com.gamewishingwell.ui.files.WwFolderIcon
import com.gamewishingwell.ui.rememberContainer
import com.gamewishingwell.ui.viewmodels.HomeViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenGame: (Long) -> Unit,
    onEditGame: (Long) -> Unit,
    onContinueDraft: () -> Unit,
    onOpenFiles: (Long) -> Unit
) {
    val container = rememberContainer()
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(container.gameRepository, container.agentHub) } }
    )
    val games by vm.games.collectAsState()
    val hasDraft by vm.hasDraft.collectAsState()
    val runState by vm.runState.collectAsState()
    val diskUnsaved by vm.diskUnsaved.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { vm.refresh() }

    // 导入游戏包：SAF 选 zip → 解包为新游戏（与游戏卡「导出zip」互导自洽）
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val meta = context.contentResolver.openInputStream(uri)?.use { vm.importGameZip(it) }
            Toast.makeText(
                context,
                if (meta != null) "已导入「${meta.title}」" else "导入失败：不是有效的游戏包（需含 index.html）",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 导出中转：菜单记下目标游戏，SAF「另存为」返回 Uri 后打包写入
    var pendingExportGameId by remember { mutableStateOf<Long?>(null) }
    val exportZipLauncher = rememberLauncherForActivityResult(
        remember { ActivityResultContracts.CreateDocument("application/zip") }
    ) { uri ->
        val gameId = pendingExportGameId
        pendingExportGameId = null
        if (uri == null || gameId == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = context.contentResolver.openOutputStream(uri)?.use { vm.exportGameZip(gameId, it) } ?: false
            Toast.makeText(context, if (ok) "已导出游戏包" else "导出失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的游戏") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                importZipLauncher.launch(arrayOf("application/zip"))
            }) {
                Icon(WwImportIcon, contentDescription = "导入游戏")
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (games.isEmpty() && !hasDraft) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(88.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("还没有游戏", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "去底部「创作」页说一句话，AI 立刻给你做一个游戏；\n也可点击右下角导入 zip 游戏包",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            if (hasDraft) "草稿 + 已保存 ${games.size} 款游戏" else "已保存 ${games.size} 款游戏，点击即可游玩",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    if (hasDraft) {
                        item {
                            ContinueDraftCard(
                                runningStage = runState.draftStage,
                                unsaved = runState.draftUnsaved || diskUnsaved.draft,
                                onContinue = onContinueDraft,
                                onFiles = { onOpenFiles(GameRepository.DRAFT_ID) },
                                onDelete = { vm.deleteDraft() }
                            )
                        }
                    }
                    items(games, key = { it.id }) { meta ->
                        GameCard(
                            meta = meta,
                            runningStage = runState.gameStages[meta.id],
                            unsaved = runState.gameUnsaved.contains(meta.id) || diskUnsaved.games.contains(meta.id),
                            onClick = { onOpenGame(meta.id) },
                            onEdit = { onEditGame(meta.id) },
                            onDelete = { vm.deleteGame(meta.id) },
                            onRename = { vm.renameGame(meta.id, it) },
                            onFiles = { onOpenFiles(meta.id) },
                            onExportZip = {
                                pendingExportGameId = meta.id
                                exportZipLauncher.launch(zipFileNameFor(meta.title))
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Agent Loop 运行动态标识：循环箭头匀速旋转 + 透明度呼吸，一眼可辨"这个会话正在跑"。 */
@Composable
private fun AgentRunningLogo(tint: Color, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "agent-running")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing)),
        label = "spin"
    )
    val alpha by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 650, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath"
    )
    Icon(
        Icons.Filled.Refresh,
        contentDescription = "AI 正在工作中",
        tint = tint.copy(alpha = alpha),
        modifier = modifier.rotate(angle)
    )
}

/** 草稿卡片：菜单与成品游戏卡片同款（文件夹 / 删除），点击卡片本体继续上次创作。 */
@Composable
private fun ContinueDraftCard(
    runningStage: String?,
    unsaved: Boolean,
    onContinue: () -> Unit,
    onFiles: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 运行中=动态 logo；结束但有未保存新版本=同色 "!"（保存后消失）；
            // 其余不展示状态文字——围观细节留给对话页。
            when {
                runningStage != null -> AgentRunningLogo(tint = MaterialTheme.colorScheme.onPrimaryContainer)
                unsaved -> Text(
                    "!",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                else -> Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("继续上次创作", style = MaterialTheme.typography.titleMedium)
                Text(
                    "你有一份未保存的游戏草稿",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("文件夹") },
                        leadingIcon = { Icon(WwFolderIcon, contentDescription = null) },
                        onClick = { menuOpen = false; onFiles() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; confirmDelete = true }
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除草稿") },
            text = { Text("确定删除这份未保存的游戏草稿吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun GameCard(
    meta: GameMeta,
    runningStage: String?,
    unsaved: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onFiles: () -> Unit,
    onExportZip: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    meta.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.description.isNotBlank()) {
                    Text(
                        meta.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    formatTime(meta.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 运行中=小动态 logo；结束但有未保存新版本=同色 "!"（保存后消失）；
            // 不展示任何状态文字，占位最小化不挤标题。
            if (runningStage != null) {
                AgentRunningLogo(
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            } else if (unsaved) {
                Text(
                    "!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(6.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("继续编辑") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; renameOpen = true }
                    )
                    DropdownMenuItem(
                        text = { Text("文件夹") },
                        leadingIcon = { Icon(WwFolderIcon, contentDescription = null) },
                        onClick = { menuOpen = false; onFiles() }
                    )
                    DropdownMenuItem(
                        text = { Text("导出zip") },
                        leadingIcon = { Icon(WwExportIcon, contentDescription = null) },
                        onClick = { menuOpen = false; onExportZip() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; confirmDelete = true }
                    )
                }
            }
        }
    }

    if (renameOpen) {
        var newTitle by remember { mutableStateOf(meta.title) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("重命名游戏") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("游戏名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank(),
                    onClick = {
                        renameOpen = false
                        onRename(newTitle.trim())
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("取消") }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除游戏") },
            text = { Text("确定删除「${meta.title}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

private fun formatTime(millis: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

/** SAF「另存为」建议文件名：标题清理文件系统非法字符，空/超长时回退通用名。 */
private fun zipFileNameFor(title: String): String {
    val clean = title.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(50)
    return if (clean.isEmpty()) "游戏.zip" else "$clean.zip"
}
