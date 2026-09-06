package com.gamewishingwell.ui.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gamewishingwell.data.GameFileContent
import com.gamewishingwell.data.GameFileEntry
import com.gamewishingwell.ui.rememberContainer
import com.gamewishingwell.ui.viewmodels.FileViewerState
import com.gamewishingwell.ui.viewmodels.FileBrowserViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件可视系统（资源管理器样式，全程只读）：
 * 首页游戏卡「文件夹」选项进入，逐级浏览该游戏存储文件夹（games/<id>）——
 * 点击文件夹进入子目录（.versions / .savepoint 同样可进），系统返回键或返回箭头逐级退回；
 * 点击文件以只读查看器打开：等宽字体展示文本，长按可选中复制（复制全部入口在顶栏），
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

    LaunchedEffect(gameId) { vm.load(gameId) }

    // 系统返回键：先关查看器，再退上级目录，根目录时交回导航（回首页）
    BackHandler(enabled = viewer != null || currentPath.isNotEmpty()) {
        when {
            viewer != null -> vm.closeFile()
            else -> vm.navigateUp()
        }
    }

    if (viewer != null) {
        FileViewerContent(state = viewer!!, onClose = vm::closeFile)
        return
    }

    val folderName = currentPath.substringAfterLast('/')
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (currentPath.isEmpty()) "游戏文件夹" else folderName,
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
                "应用私有目录 · games/$gameId" +
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
                    if (currentPath.isEmpty()) "文件夹为空或游戏已被删除" else "此文件夹为空",
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

/** 只读文件查看器：顶栏带「复制全部」，正文 SelectionContainer 可长按选中复制，无编辑入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerContent(state: FileViewerState, onClose: () -> Unit) {
    val context = LocalContext.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
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
                    if (content != null && !content.binary && content.text.isNotEmpty()) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(content.text))
                            Toast.makeText(context, "已复制全部内容", Toast.LENGTH_SHORT).show()
                        }) { Text("复制全部") }
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
            FileViewerState.Failed -> Text(
                "无法打开文件（可能已被移动或删除）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(24.dp)
            )
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
                "${describeBytes(content.sizeBytes)} · ${formatDateTime(content.lastModified)} · 只读，可长按选中复制",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (content.truncated) {
                Text(
                    "文件较大，已截断仅预览前 ${content.text.length} 字符",
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
        else -> "文件夹"
    }
    entry.name.endsWith(".html") -> "HTML 网页"
    entry.name.endsWith(".json") -> "JSON 数据"
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
