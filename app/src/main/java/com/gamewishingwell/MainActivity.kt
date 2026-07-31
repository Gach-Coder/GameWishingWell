package com.gamewishingwell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gamewishingwell.ui.WishwellApp
import com.gamewishingwell.ui.WishwellTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WishwellTheme {
                WishwellApp()
            }
        }
    }
}
