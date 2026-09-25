package ru.rim.dd.feature.pairing

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.ui.components.GlassCard
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.theme.RimButtonGradient
import ru.rim.dd.ui.theme.RimError
import ru.rim.dd.ui.theme.RimLilac
import ru.rim.dd.ui.theme.RimPrimary
import ru.rim.dd.ui.theme.RimPrimaryDeep
import ru.rim.dd.ui.theme.RimPrimaryLight
import ru.rim.dd.ui.theme.RimSuccess
import ru.rim.dd.ui.theme.RimTheme
import ru.rim.dd.ui.theme.RimWarn

private val blePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun rssiColor(r: Int) = when {
    r > -50 -> RimSuccess
    r > -70 -> RimWarn
    else -> RimError
}

@Composable
fun PairingScreen(
    onConnected: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val p = RimTheme.palette
    var serial by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    val pin = ""

    val context = LocalContext.current
    var permissionsGranted by remember {
        mutableStateOf(
            blePermissions.all {
                ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> permissionsGranted = results.values.all { it } }

    LaunchedEffect(state.isConnected) { if (state.isConnected) onConnected() }

    val canConnect = (selected != null || serial.length >= 8) && !state.isConnecting

    Box(Modifier.fillMaxSize().background(p.background)) {
        Column(Modifier.fillMaxSize()) {
            // ---- Заголовок ----
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(RimPrimaryDeep.copy(alpha = 0.15f))
                        .border(1.dp, RimPrimaryDeep.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(RimLilac))
                    Text("BLE READY", color = RimLilac, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                }
                Text(
                    "Подключение", color = p.textPrimary, fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 18.dp),
                )
                Text("к счётчику", color = RimPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text("Выберите способ подключения", color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }

            // ---- Прокручиваемое содержимое ----
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!permissionsGranted) {
                    GlassCard {
                        Text(
                            "Для поиска и подключения нужен доступ к Bluetooth" +
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " и геолокации" else "",
                            color = p.textSecondary, fontSize = 13.sp,
                        )
                        GradientButton("Предоставить разрешения", Modifier.fillMaxWidth().padding(top = 12.dp), enabled = true) {
                            permissionLauncher.launch(blePermissions)
                        }
                    }
                } else {
                    // Карточка сканирования
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            SonarIcon(scanning = state.isScanning)
                            Column(Modifier.weight(1f)) {
                                Text("Сканировать эфир", color = p.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                Text("Поиск BLE устройств поблизости", color = p.textSecondary, fontSize = 15.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(100.dp))
                                    .then(
                                        if (state.isScanning) Modifier.background(RimPrimaryDeep.copy(alpha = 0.2f))
                                        else Modifier.background(Brush.linearGradient(RimButtonGradient))
                                    )
                                    .clickable(enabled = !state.isScanning) { viewModel.scan() }
                                    .padding(horizontal = 18.dp, vertical = 9.dp),
                            ) {
                                Text(
                                    if (state.isScanning) "..." else "Искать",
                                    color = if (state.isScanning) RimPrimaryLight else Color.White,
                                    fontSize = 19.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    // Ручной ввод (только серийный номер — PIN не нужен)
                    GlassCard {
                        Text("Ввести номер ПУ", color = p.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("Серийный номер прибора", color = p.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp))
                        FieldInput(serial, { serial = it }, "Серийный номер", KeyboardType.Number)
                    }

                    // Найденные приборы
                    if (state.foundDevices.isNotEmpty()) {
                        Text(
                            "НАЙДЕНО · ${state.foundDevices.size}",
                            color = RimPrimaryLight, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.foundDevices.forEach { dev ->
                                DeviceRow(dev, dev.address == selected) {
                                    selected = if (dev.address == selected) null else dev.address
                                }
                            }
                        }
                    }

                    state.errorMessage?.let {
                        Text(it, color = RimError, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                    Box(Modifier.height(4.dp))
                }
            }

            // ---- CTA ----
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp)) {
                GradientButton(
                    if (state.isConnecting) "Подключение…" else "Подключиться",
                    Modifier.fillMaxWidth(),
                    enabled = canConnect,
                ) {
                    val dev = selected?.let { sel -> state.foundDevices.firstOrNull { it.address == sel } }
                    if (dev != null) viewModel.selectDevice(dev, pin, remember = true)
                    else viewModel.connectByNumber(serial, pin, remember = true)
                }
            }
        }
    }
}

@Composable
private fun SonarIcon(scanning: Boolean) {
    val scale = if (scanning) {
        val t = rememberInfiniteTransition(label = "sonar")
        t.animateFloat(1f, 2f, infiniteRepeatable(tween(1500), RepeatMode.Restart), label = "s").value
    } else 1f
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(RimPrimaryDeep.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        if (scanning) {
            Box(Modifier.size(52.dp).scale(scale).clip(CircleShape).border(1.5.dp, RimPrimaryDeep.copy(alpha = (2f - scale).coerceIn(0f, 0.4f)), CircleShape))
        }
        Text("⚡", fontSize = 20.sp)
    }
}

@Composable
private fun DeviceRow(dev: BleDevice, selected: Boolean, onClick: () -> Unit) {
    val p = RimTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) RimPrimaryDeep.copy(alpha = if (p.dark) 0.25f else 0.10f) else p.cardSurface)
            .border(1.5.dp, if (selected) RimPrimary else p.border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(RimPrimaryDeep.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { Text("▯", color = if (selected) RimLilac else p.textSecondary, fontSize = 16.sp) }
        Column(Modifier.weight(1f)) {
            Text(dev.name ?: dev.address, color = if (selected) RimLilac else p.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            MonoText("№${dev.name?.substringAfterLast('-')?.substringAfterLast(' ') ?: dev.address}", color = p.textSecondary, fontSize = 11.sp, weight = FontWeight.Normal)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val bars = if (dev.rssi > -50) 4 else if (dev.rssi > -70) 2 else 1
                (1..4).forEach { i ->
                    Box(
                        Modifier
                            .size(width = 4.dp, height = (4 + i * 3).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i <= bars) rssiColor(dev.rssi) else p.border),
                    )
                }
            }
            MonoText("${dev.rssi} дБм", color = rssiColor(dev.rssi), fontSize = 10.sp)
        }
        if (selected) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(RimPrimary), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FieldInput(value: String, onChange: (String) -> Unit, placeholder: String, keyboard: KeyboardType, isPassword: Boolean = false) {
    val p = RimTheme.palette
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = p.textFaint, fontFamily = FontFamily.Monospace) },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = if (p.dark) Color.White.copy(alpha = 0.05f) else RimPrimaryDeep.copy(alpha = 0.04f),
            unfocusedContainerColor = if (p.dark) Color.White.copy(alpha = 0.05f) else RimPrimaryDeep.copy(alpha = 0.04f),
            focusedTextColor = p.textPrimary,
            unfocusedTextColor = p.textPrimary,
            focusedIndicatorColor = RimPrimary,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = RimPrimary,
        ),
    )
}

@Composable
private fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean, onClick: () -> Unit) {
    val p = RimTheme.palette
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (enabled) Modifier.background(Brush.linearGradient(RimButtonGradient))
                else Modifier.background(if (p.dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.07f))
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Color.White else p.textFaint,
            fontSize = 16.sp, fontWeight = FontWeight.Bold,
        )
    }
}
