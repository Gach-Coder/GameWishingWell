package com.gamewishingwell.ui.files

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gamewishingwell.data.GameFileEntry
import com.gamewishingwell.ui.rememberContainer
import com.gamewishingwell.ui.viewmodels.FileBrowserViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件可视系统（资源管理器样式，只读概要）：
 * 首页游戏卡「文件夹」选项进入，展示该游戏存储文件夹（games/<id>）内
 * 一级条目的名称/类型/大小/修改日期；按需求不实现打开文件的功能。
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

    LaunchedEffect(gameId) { vm.load(gameId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("游戏文件夹") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                "应用私有目录 · games/$gameId · 只读概览（共 ${entries?.size ?: 0} 项）",
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
                    "文件夹为空或游戏已被删除",
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
                        FileRow(entry)
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

@Composable
private fun FileRow(entry: GameFileEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
    }
}

private fun fileTypeOf(entry: GameFileEntry): String = when {
    entry.isDirectory -> if (entry.name == ".versions") "版本归档文件夹" else "文件夹"
    entry.name.endsWith(".html") -> "HTML 网页"
    entry.name.endsWith(".json") -> "JSON 数据"
    entry.name.endsWith(".sha256") -> "SHA-256 校验"
    entry.name.endsWith(".version") -> "版本标记"
    entry.name.endsWith(".txt") -> "文本"
    else -> "文件"
}

private fun describeSize(entry: GameFileEntry): String = when {
    entry.isDirectory -> "${entry.childCount} 项"
    entry.sizeBytes < 1024 -> "${entry.sizeBytes} B"
    entry.sizeBytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", entry.sizeBytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", entry.sizeBytes / (1024.0 * 1024.0))
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
