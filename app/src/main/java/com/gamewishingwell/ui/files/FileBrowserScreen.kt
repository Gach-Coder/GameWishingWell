package com.gamewishingwell.ui.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gamewishingwell.data.GameFileContent
import com.gamewishingwell.data.GameFileEntry
import com.gamewishingwell.data.GameRepository
import com.gamewishingwell.ui.rememberContainer
import com.gamewishingwell.ui.viewmodels.FileViewerState
import com.gamewishingwell.ui.viewmodels.FileBrowserViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件可视系统（资源管理器样式，全程只读）：
 * 首页游戏卡「文件夹」选项进入（草稿卡片同样进入草稿文件夹），逐级浏览游戏存储
 * 文件夹（games/<id> 或 drafts/）——点击文件夹进入子目录（.versions / .savepoint /
 * log 同样可进），系统返回键或返回箭头逐级退回；
 * 点击文件以只读查看器打开：等宽字体展示文本，长按可选中复制，顶栏「导出文件」
 * 经系统 SAF 把完整文件另存到任意位置（不截断、二进制原样）；
 * 不提供任何编辑/修改/保存路径；大文件超限截断预览、二进制内容不做文本预览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    gameId: Long,
    onBack: () -> Unit
) {
    val container = rememberContainer()
    val vm: FileBrowserViewModel = viewModel(
        factory = viewModelFactory { initializer { FileBrowserViewModel(container.gameRepository) } }
    )
    val entries by vm.entries.collectAsState()
    val currentPath by vm.currentPath.collectAsState()
    val viewer by vm.viewer.collectAsState()

    val isDraft = gameId == GameRepository.DRAFT_ID

    LaunchedEffect(gameId) { vm.load(gameId) }

    // 系统返回键：先关查看器，再退上级目录，根目录时交回导航（回首页）
    val viewerState = viewer
    BackHandler(enabled = viewerState != null || currentPath.isNotEmpty()) {
        when {
            viewerState != null -> vm.closeFile()
            else -> vm.navigateUp()
        }
    }

    // 导出中转：点击「导出文件」先记下目标，SAF「另存为」返回 Uri 后读取完整字节写入
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ExportFileContract()) { uri ->
        val relativePath = pendingExport
        pendingExport = null
        if (uri == null || relativePath == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = vm.readExportBytes(relativePath)
            val ok = bytes != null && runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            Toast.makeText(
                context,
                if (ok) "已导出文件" else "导出失败，请重试",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    if (viewerState != null) {
        FileViewerContent(
            state = viewerState,
            onClose = vm::closeFile,
            onExport = { content ->
                pendingExport = content.relativePath
                exportLauncher.launch(content.name)
            }
        )
        return
    }

    val folderName = currentPath.substringAfterLast('/')
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (currentPath.isEmpty()) {
                            if (isDraft) "草稿文件夹" else "游戏文件夹"
                        } else {
                            folderName
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = if (currentPath.isEmpty()) onBack else vm::navigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "应用私有目录 · ${if (isDraft) "drafts" else "games/$gameId"}" +
                    (if (currentPath.isEmpty()) "" else "/$currentPath") +
                    " · 只读（共 ${entries?.size ?: 0} 项）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            val list = entries
            if (list == null) {
                Text(
                    "正在读取文件夹…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else if (list.isEmpty()) {
                Text(
                    when {
                        currentPath.isNotEmpty() -> "此文件夹为空"
                        isDraft -> "文件夹为空或草稿已被删除"
                        else -> "文件夹为空或游戏已被删除"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(list, key = { it.name }) { entry ->
                        FileRow(entry) {
                            if (entry.isDirectory) vm.openFolder(entry.name) else vm.openFile(entry.name)
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            modifier = Modifier.padding(start = 60.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 只读文件查看器：顶栏「导出文件」（系统 SAF 另存，完整内容），正文 SelectionContainer 可长按选中复制，无编辑入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerContent(
    state: FileViewerState,
    onClose: () -> Unit,
    onExport: (GameFileContent) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (state as? FileViewerState.Ready)?.content?.name ?: "文件查看",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val content = (state as? FileViewerState.Ready)?.content
                    if (content != null) {
                        // 二进制/截断文件同样可导出：导出的是完整原始文件，不是预览内容
                        TextButton(onClick = { onExport(content) }) { Text("导出文件") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        when (state) {
            FileViewerState.Loading -> Text(
                "正在读取文件…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(24.dp)
            )
            is FileViewerState.Failed -> Column(Modifier.padding(padding).padding(24.dp)) {
                Text(
                    "无法打开文件（可能已被移动或删除）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.reason?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            is FileViewerState.Ready -> ReadyFileBody(state.content, padding)
        }
    }
}

@Composable
private fun ReadyFileBody(content: GameFileContent, padding: PaddingValues) {
    Column(
        Modifier
            .padding(padding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                content.relativePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${describeBytes(content.sizeBytes)} · ${formatDateTime(content.lastModified)} · 只读，可长按选中复制或导出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (content.truncated) {
                Text(
                    "文件较大，已截断仅预览前 ${content.text.length} 字符（导出为完整内容）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        when {
            content.binary -> Text(
                "二进制文件，不支持文本预览",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
            content.text.isEmpty() -> Text(
                "（空文件）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
            else -> SelectionContainer {
                Text(
                    content.text,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun FileRow(entry: GameFileEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (entry.isDirectory) WwFolderIcon else WwFileIcon,
            contentDescription = if (entry.isDirectory) "文件夹" else "文件",
            tint = if (entry.isDirectory) Color(0xFFE8B23A) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${fileTypeOf(entry)} · ${describeSize(entry)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            formatDateTime(entry.lastModified),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (entry.isDirectory) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun fileTypeOf(entry: GameFileEntry): String = when {
    entry.isDirectory -> when (entry.name) {
        ".versions" -> "版本归档文件夹"
        ".savepoint" -> "保存点（撤销锚点）"
        "log" -> "Agent 日志文件夹"
        else -> "文件夹"
    }
    entry.name.endsWith(".html") -> "HTML 网页"
    entry.name.endsWith(".json") -> "JSON 数据"
    entry.name.endsWith(".log") -> "Agent 日志"
    entry.name.endsWith(".sha256") -> "SHA-256 校验"
    entry.name.endsWith(".version") -> "版本标记"
    entry.name.endsWith(".txt") -> "文本"
    else -> "文件"
}

private fun describeSize(entry: GameFileEntry): String = describeBytes(entry.sizeBytes)

private fun describeBytes(sizeBytes: Long): String = when {
    sizeBytes < 1024 -> "$sizeBytes B"
    sizeBytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", sizeBytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", sizeBytes / (1024.0 * 1024.0))
}

private fun formatDateTime(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

// material-icons-core 不含 Folder/InsertDriveFile，按官方路径数据自建两个小图标。
internal val WwFolderIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "WwFolder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = addPathNodes(
                "M10,4H4C2.9,4,2.01,4.9,2.01,6L2,18c0,1.1,0.9,2,2,2h16c1.1,0,2,-0.9,2,-2V8c0,-1.1,-0.9,-2,-2,-2h-8L10,4z"
            ),
            fill = SolidColor(Color.Black)
        )
    }.build()
}

private val WwFileIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "WwFile",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = addPathNodes(
                "M6,2c-1.1,0,-1.99,0.9,-1.99,2L4,20c0,1.1,0.89,2,1.99,2H18c1.1,0,2,-0.9,2,-2V8l-6,-6H6z"
            ),
            fill = SolidColor(Color.Black)
        )
        addPath(
            pathData = addPathNodes("M14,2l6,6h-6V2z"),
            fill = SolidColor(Color.Black),
            fillAlpha = 0.6f
        )
    }.build()
}

/**
 * 「导出文件」的系统另存契约（ACTION_CREATE_DOCUMENT）：入参 = 目标文件名
 * （MIME 按扩展名推断），出参 = 用户选择位置的 Uri；由调用方把完整文件字节
 * 写入该 Uri。相比固定 MIME 的 CreateDocument，可在点击时按文件类型决定 MIME。
 */
private class ExportFileContract : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeOf(input))
            .putExtra(Intent.EXTRA_TITLE, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private fun mimeOf(fileName: String): String = when {
    fileName.endsWith(".html", ignoreCase = true) || fileName.endsWith(".htm", ignoreCase = true) ->
        "text/html"
    fileName.endsWith(".css", ignoreCase = true) -> "text/css"
    fileName.endsWith(".js", ignoreCase = true) || fileName.endsWith(".mjs", ignoreCase = true) ->
        "text/javascript"
    fileName.endsWith(".json", ignoreCase = true) -> "application/json"
    fileName.endsWith(".log", ignoreCase = true) || fileName.endsWith(".txt", ignoreCase = true) ||
        fileName.endsWith(".md", ignoreCase = true) || fileName.endsWith(".sha256", ignoreCase = true) ||
        fileName.endsWith(".version", ignoreCase = true) -> "text/plain"
    else -> "application/octet-stream"
}
