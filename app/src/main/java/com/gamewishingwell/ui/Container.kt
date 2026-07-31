package com.gamewishingwell.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.gamewishingwell.AppContainer
import com.gamewishingwell.WishwellApplication

/** 在可组合函数中获取全局容器（手动依赖注入，MVP 够用）。 */
@Composable
fun rememberContainer(): AppContainer =
    (LocalContext.current.applicationContext as WishwellApplication).container
