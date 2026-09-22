package com.gamewishingwell.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// material-icons-core 不含上传/下载图标，按官方路径数据自建两个小图标
// （与 ui/files/FileBrowserScreen.kt 的 WwFolderIcon/WwFileIcon 同法）。

/** 导入：下载入托盘——「我的游戏」页右下角导入按钮（选择 zip 游戏包）。 */
val WwImportIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "WwImport",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = addPathNodes("M19,9h-4V3H9v6H5l7,7,7,-7z"),
            fill = SolidColor(Color.Black)
        )
        addPath(
            pathData = addPathNodes("M5,18v2h14v-2H5z"),
            fill = SolidColor(Color.Black)
        )
    }.build()
}

/** 导出：上传出托盘——游戏卡菜单「导出zip」（游戏文件夹打包另存）。 */
val WwExportIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "WwExport",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = addPathNodes("M9,16h6v-6h4l-7,-7,-7,7h4z"),
            fill = SolidColor(Color.Black)
        )
        addPath(
            pathData = addPathNodes("M5,18v2h14v-2H5z"),
            fill = SolidColor(Color.Black)
        )
    }.build()
}
