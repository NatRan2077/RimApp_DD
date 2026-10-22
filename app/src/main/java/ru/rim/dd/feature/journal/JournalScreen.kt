package ru.rim.dd.feature.journal

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import ru.rim.dd.data.local.JournalEntry
import ru.rim.dd.ui.components.DataCard
import ru.rim.dd.ui.components.Divider
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.components.RimShapes
import ru.rim.dd.ui.components.SectionLabel
import ru.rim.dd.ui.components.StatusChip
import ru.rim.dd.ui.theme.RimError
import ru.rim.dd.ui.theme.RimFonts
import ru.rim.dd.ui.theme.RimSuccess
import ru.rim.dd.ui.theme.RimTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Подтверждение очистки, ожидающее ответа пользователя. */
private sealed interface ClearRequest {
    object All : ClearRequest
    data class Selected(val ids: Set<Long>) : ClearRequest
}

@Composable
fun JournalScreen(
    onBack: () -> Unit,
    viewModel: JournalViewModel = hiltViewModel(),
) {
    val p = RimTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by viewModel.entries.collectAsState()

    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var clearRequest by remember { mutableStateOf<ClearRequest?>(null) }

    // Выбранные карточки могли исчезнуть (очистка) — не держим «мёртвые» id.
    val selectedAlive = selected.filter { id -> entries.any { it.id == id } }.toSet()

    fun share(file: File?) {
        if (file == null) {
            Toast.makeText(context, "Нечего отправлять", Toast.LENGTH_SHORT).show()
        } else {
            shareTextFile(context, file)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RimShapes.control)
                    .background(p.cardSurface)
                    .border(1.dp, p.border, RimShapes.control)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = p.textSecondary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Журнал", color = p.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontFamily = RimFonts.Raleway)
                Text(
                    if (selecting) "Выбрано: ${selectedAlive.size}" else "Приборы, к которым вы подключались",
                    color = p.textSecondary, fontSize = 15.sp,
                )
            }
        }

        // Панель действий
        if (entries.isNotEmpty()) {
            if (!selecting) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Отправить .txt", p.accentText, Modifier.weight(1f)) {
                        scope.launch { share(viewModel.exportText(null)) }
                    }
                    ActionButton("Выбрать", p.textSecondary, Modifier.weight(1f)) {
                        selecting = true
                        selected = emptySet()
                    }
                    ActionButton("Очистить", RimError, Modifier.weight(1f)) { clearRequest = ClearRequest.All }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        if (selectedAlive.size == entries.size) "Снять всё" else "Все",
                        p.textSecondary, Modifier.weight(1f),
                    ) {
                        selected = if (selectedAlive.size == entries.size) emptySet() else entries.map { it.id }.toSet()
                    }
                    ActionButton("Отмена", p.textSecondary, Modifier.weight(1f)) {
                        selecting = false
                        selected = emptySet()
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Отправить .txt", p.accentText, Modifier.weight(1f), enabled = selectedAlive.isNotEmpty()) {
                        scope.launch { share(viewModel.exportText(selectedAlive)) }
                    }
                    ActionButton("Удалить", RimError, Modifier.weight(1f), enabled = selectedAlive.isNotEmpty()) {
                        clearRequest = ClearRequest.Selected(selectedAlive)
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            DataCard {
                Text("Журнал пуст", color = p.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Здесь появятся приборы, к которым вы подключитесь",
                    color = p.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    JournalCard(
                        entry = entry,
                        expanded = entry.id in expanded,
                        selecting = selecting,
                        checked = entry.id in selectedAlive,
                        onClick = {
                            if (selecting) {
                                selected = if (entry.id in selectedAlive) selectedAlive - entry.id else selectedAlive + entry.id
                            } else {
                                expanded = if (entry.id in expanded) expanded - entry.id else expanded + entry.id
                            }
                        },
                        onSendThis = { scope.launch { share(viewModel.exportText(setOf(entry.id))) } },
                    )
                }
            }
        }
    }

    clearRequest?.let { req ->
        val text = when (req) {
            is ClearRequest.All -> "Удалить все записи журнала? Подключение, которое идёт сейчас, останется."
            is ClearRequest.Selected -> "Удалить выбранные записи (${req.ids.size})?"
        }
        AlertDialog(
            onDismissRequest = { clearRequest = null },
            title = { Text("Очистка журнала") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    when (req) {
                        is ClearRequest.All -> viewModel.clearAll()
                        is ClearRequest.Selected -> viewModel.delete(req.ids)
                    }
                    clearRequest = null
                    selecting = false
                    selected = emptySet()
                }) { Text("Удалить", color = RimError) }
            },
            dismissButton = { TextButton(onClick = { clearRequest = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = if (enabled) color else color.copy(alpha = 0.35f)
    Box(
        modifier = modifier
            .clip(RimShapes.control)
            .border(1.5.dp, c, RimShapes.control)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 11.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun JournalCard(
    entry: JournalEntry,
    expanded: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onSendThis: () -> Unit,
) {
    val p = RimTheme.palette
    val active = entry.isActive
    DataCard(noPad = true) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selecting) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.textSecondary),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.meterType, color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = RimFonts.Raleway)
                }
                MonoText("№ ${entry.serialNumber}", color = p.textPrimary, fontSize = 20.sp, weight = FontWeight.SemiBold)
                Text(
                    stamp(entry.connectedAt),
                    color = p.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!selecting) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, tint = p.textSecondary,
                )
            }
        }

        if (expanded && !selecting) {
            Divider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoLine("Подключено", stamp(entry.connectedAt))
                InfoLine(
                    "Отключено",
                    entry.disconnectedAt?.let { stamp(it) } ?: if (active) "ещё подключён" else "—",
                )

                SectionLabel("Электроэнергия", Modifier.padding(top = 10.dp))
                if (entry.energy.isEmpty()) {
                    Text("Показания не получены", color = p.textFaint, fontSize = 15.sp)
                } else {
                    entry.energy.forEach { InfoLine(it.label, it.value, mono = true) }
                }

                SectionLabel("Сеть", Modifier.padding(top = 10.dp))
                InfoLine("Напряжение", entry.voltage ?: "—", mono = true)
                InfoLine("Ток", entry.current ?: "—", mono = true)

                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Отправить .txt", p.accentText, Modifier.weight(1f), onClick = onSendThis)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    val p = RimTheme.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (mono) {
            MonoText(value, color = p.textPrimary, fontSize = 16.sp, weight = FontWeight.Medium)
        } else {
            Text(value, color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun stamp(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(millis))

private fun shareTextFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Отправить"))
}
