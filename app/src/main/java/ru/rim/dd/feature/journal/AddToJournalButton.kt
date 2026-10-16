package ru.rim.dd.feature.journal

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.ui.components.RimShapes
import ru.rim.dd.ui.theme.RimTheme

/** Кнопка «+ в журнал» в углу заголовка экрана прибора, того же вида, что «Обновить». */
@Composable
fun AddToJournalButton(modifier: Modifier = Modifier, viewModel: AddToJournalViewModel = hiltViewModel()) {
    val p = RimTheme.palette
    val context = LocalContext.current
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RimShapes.control)
            .background(p.cardSurface)
            .border(1.dp, p.border, RimShapes.control)
            .clickable {
                viewModel.add { ok ->
                    Toast.makeText(
                        context,
                        if (ok) "Добавлено в журнал" else "Данных для журнала ещё нет — подождите первого обновления",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Добавить в журнал",
            tint = p.textSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}
