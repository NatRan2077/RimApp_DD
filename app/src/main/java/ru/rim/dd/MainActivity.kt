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
        // [Android-патч] Тема (RimTheme) применяется внутри AppNavHost — она зависит от
        // пользовательской настройки «Тёмная тема» (AppSettingsViewModel), которую там же и читаем.
        setContent {
            AppNavHost()
        }
    }
}
