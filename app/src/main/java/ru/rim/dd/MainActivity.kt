package ru.rim.dd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import ru.rim.dd.navigation.AppNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Тему (RimTheme) применяет AppNavHost: она зависит от настройки «Тёмная тема».
        setContent {
            AppNavHost()
        }
    }
}
