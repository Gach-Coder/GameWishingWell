package com.gamewishingwell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gamewishingwell.ui.WishwellApp
import com.gamewishingwell.ui.WishwellTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局 edge-to-edge：内容绘制到透明系统栏之后（API 35+ 系统强制；低版本
        // 显式对齐同一行为）——游戏页因此能真正铺到屏幕绝对顶端，各页 TopAppBar/
        // Scaffold 自带 inset 处理不受影响。
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            WishwellTheme {
                WishwellApp()
            }
        }
    }

    /** Android 13+ 通知运行时权限：前台服务的生成进度通知需要；
     *  拒绝只影响通知显示，不影响后台生成本身（服务照常运行）。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}
