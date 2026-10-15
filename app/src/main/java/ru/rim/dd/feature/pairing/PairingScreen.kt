package ru.rim.dd.feature.pairing

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.core.model.MeterDeviceName
import ru.rim.dd.ui.components.GlassCard
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.components.RimShapes
import ru.rim.dd.ui.components.SectionLabel
import ru.rim.dd.ui.theme.RimError
import ru.rim.dd.ui.theme.RimFonts
import ru.rim.dd.ui.theme.RimTheme

private val blePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * На Android 9–11 поиск Bluetooth-устройств не работает при выключенной геолокации телефона: система
 * молча возвращает пустой список. С Android 12 доступ к поиску выдаётся отдельным разрешением.
 */
private fun isLocationReady(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
    return manager.isLocationEnabled
}

private fun isBluetoothEnabled(context: Context): Boolean =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

@Composable
fun PairingScreen(
    onConnected: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val recentSerials by viewModel.recentSerials.collectAsState()
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

    // Проверка Bluetooth и геолокации перед поиском и подключением: если Bluetooth выключен — системный
    // запрос на включение, если выключена геолокация (Android 9–11) — подсказка с переходом в настройки.
    // Действие продолжается только когда всё готово.
    var showLocationDialog by remember { mutableStateOf(false) }
    val runChecked: (() -> Unit) -> Unit = { action ->
        if (isLocationReady(context)) action() else showLocationDialog = true
    }
    var pendingAfterBluetooth by remember { mutableStateOf<(() -> Unit)?>(null) }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingAfterBluetooth
        pendingAfterBluetooth = null
        if (result.resultCode == Activity.RESULT_OK) action?.let { runChecked(it) }
        else viewModel.reportError("Bluetooth выключен — включите его, чтобы искать и подключаться к приборам")
    }
    val withBluetooth: (() -> Unit) -> Unit = { action ->
        if (isBluetoothEnabled(context)) {
            runChecked(action)
        } else {
            pendingAfterBluetooth = action
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
    val focusManager = LocalFocusManager.current
    val lossNotice by viewModel.lossNotice.collectAsState()

    val canConnect = (selected != null || serial.length >= 8) && !state.isConnecting

    if (showLocationDialog) {
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            containerColor = p.cardSurface,
            shape = RimShapes.card,
            title = { Text("Включите геолокацию", color = p.textPrimary, fontFamily = RimFonts.Raleway, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "На этой версии Android поиск Bluetooth-приборов работает только при включённой геолокации. " +
                            "Приложение не определяет и не сохраняет ваше местоположение.",
                    color = p.textSecondary, fontSize = 16.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLocationDialog = false
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) { Text("Открыть настройки", color = p.accentText, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showLocationDialog = false }) { Text("Отмена", color = p.textSecondary) }
            },
        )
    }

    lossNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissLossNotice() },
            containerColor = p.cardSurface,
            shape = RimShapes.card,
            title = { Text("Обрыв связи", color = RimError, fontFamily = RimFonts.Raleway, fontWeight = FontWeight.Bold) },
            text = { Text(message, color = p.textSecondary, fontSize = 16.sp) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissLossNotice() }) {
                    Text("Понятно", color = p.accentText, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(p.background)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
                Text(
                    "Подключение", color = p.textPrimary, fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold, fontFamily = RimFonts.Raleway,
                )
                Text("к счётчику", color = p.accentText, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, fontFamily = RimFonts.Raleway)
                Text("Выберите прибор или введите номер", color = p.textSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!permissionsGranted) {
                    GlassCard {
                        Text(
                            "Для поиска и подключения нужен доступ к Bluetooth" +
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " и геолокации" else "",
                            color = p.textSecondary, fontSize = 15.sp,
                        )
                        GradientButton("Предоставить разрешения", Modifier.fillMaxWidth().padding(top = 12.dp), enabled = true) {
                            permissionLauncher.launch(blePermissions)
                        }
                    }
                } else {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Поиск приборов", color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = RimFonts.Raleway)
                                Text(
                                    if (state.isScanning) "Идёт поиск…" else "Приборы учёта поблизости",
                                    color = p.textSecondary, fontSize = 16.sp,
                                )
                            }
                            // «Искать» — сплошная фирменная кнопка, «Стоп» — красная рамка.
                            Box(
                                modifier = Modifier
                                    .clip(RimShapes.control)
                                    .then(
                                        if (state.isScanning) Modifier.border(1.5.dp, RimError, RimShapes.control)
                                        else Modifier.background(p.accent)
                                    )
                                    .clickable(enabled = !state.isConnecting) {
                                        if (state.isScanning) viewModel.stopScan()
                                        else withBluetooth { viewModel.scan() }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    if (state.isScanning) "Стоп" else "Искать",
                                    color = if (state.isScanning) RimError else Color.White,
                                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    // Ручной ввод: только серийный номер, PIN не нужен
                    GlassCard {
                        Text(
                            "Номер прибора учёта", color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            fontFamily = RimFonts.Raleway, modifier = Modifier.padding(bottom = 10.dp),
                        )
                        FieldInput(
                            value = serial,
                            onChange = { serial = it; selected = null },
                            placeholder = "Серийный номер",
                            keyboard = KeyboardType.Number,
                            // Ввод номера и выбор найденного прибора взаимоисключают друг друга:
                            // как только пользователь берётся за поле, выбор снимается.
                            onFocus = { selected = null },
                        )
                        // Три последних введённых номера — нажатие подставляет номер в поле.
                        if (recentSerials.isNotEmpty()) {
                            Text("НЕДАВНИЕ", color = p.textSecondary, fontSize = 13.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                recentSerials.forEach { recent ->
                                    RecentSerialChip(recent, recent == serial) { serial = recent; selected = null }
                                }
                            }
                        }
                    }

                    if (state.foundDevices.isNotEmpty()) {
                        SectionLabel("Найдено · ${state.foundDevices.size}")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.foundDevices.forEach { dev ->
                                DeviceRow(dev, dev.address == selected) {
                                    selected = if (dev.address == selected) null else dev.address
                                    // Убираем фокус с поля номера: иначе повторное нажатие на него
                                    // не вызвало бы событие фокуса и выбор прибора не снялся бы.
                                    focusManager.clearFocus()
                                }
                            }
                        }
                    }

                    if (state.isConnecting) {
                        Text(
                            "Если появится запрос сопряжения — введите PIN-код прибора. " +
                                    "На некоторых телефонах запрос приходит уведомлением: откройте его.",
                            color = p.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    state.errorMessage?.let {
                        Text(it, color = RimError, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                    Box(Modifier.height(4.dp))
                }
            }

            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp)) {
                GradientButton(
                    if (state.isConnecting) "Подключение…" else "Подключиться",
                    Modifier.fillMaxWidth(),
                    enabled = canConnect,
                ) {
                    val dev = selected?.let { sel -> state.foundDevices.firstOrNull { it.address == sel } }
                    withBluetooth {
                        if (dev != null) viewModel.selectDevice(dev, pin, remember = true)
                        else viewModel.connectByNumber(serial, pin, remember = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSerialChip(serial: String, active: Boolean, onClick: () -> Unit) {
    val p = RimTheme.palette
    Box(
        modifier = Modifier
            .clip(RimShapes.chip)
            .background(if (active) p.accent.copy(alpha = 0.10f) else p.cardSurface)
            .border(if (active) 1.5.dp else 1.dp, if (active) p.accent else p.border, RimShapes.chip)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        MonoText(serial, color = if (active) p.accentText else p.textPrimary, fontSize = 17.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun DeviceRow(dev: BleDevice, selected: Boolean, onClick: () -> Unit) {
    val p = RimTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RimShapes.card)
            .background(if (selected) p.accent.copy(alpha = if (p.dark) 0.18f else 0.06f) else p.cardSurface)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.border, RimShapes.card)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            // Тип прибора (AKROS / РиМ 189.40 / РиМ 384.02), под ним — серийный номер крупно.
            val parsed = MeterDeviceName.parse(dev.name)
            Text(
                MeterDeviceName.typeLabel(dev.name) ?: dev.name ?: "Неизвестное устройство",
                color = if (selected) p.accentText else p.textSecondary, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, fontFamily = RimFonts.Raleway,
            )
            if (parsed != null) {
                MonoText("№ ${parsed.serialNumber}", color = p.textPrimary, fontSize = 27.sp, weight = FontWeight.SemiBold)
            } else {
                MonoText(dev.address, color = p.textSecondary, fontSize = 16.sp, weight = FontWeight.Normal)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val bars = if (dev.rssi > -50) 4 else if (dev.rssi > -70) 2 else 1
                (1..4).forEach { i ->
                    Box(
                        Modifier
                            .size(width = 5.dp, height = (5 + i * 3).dp)
                            .background(if (i <= bars) p.accent else p.border),
                    )
                }
            }
            MonoText("${dev.rssi} дБм", color = p.textSecondary, fontSize = 14.sp, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FieldInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType,
    onFocus: () -> Unit = {},
) {
    val p = RimTheme.palette
    val container = p.fieldSurface
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = p.textFaint, fontSize = 20.sp) },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(
            fontFamily = RimFonts.Plex, fontSize = 22.sp, fontWeight = FontWeight.Medium,
            fontFeatureSettings = RimFonts.DIGITS, letterSpacing = 1.sp,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RimShapes.control,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedTextColor = p.textPrimary,
            unfocusedTextColor = p.textPrimary,
            focusedIndicatorColor = p.accent,
            unfocusedIndicatorColor = p.border,
            cursorColor = p.accent,
        ),
    )
}

@Composable
private fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean, onClick: () -> Unit) {
    val p = RimTheme.palette
    Box(
        modifier = modifier
            .clip(RimShapes.card)
            .background(if (enabled) p.accent else if (p.dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.07f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Color.White else p.textFaint,
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}
