package ru.rim.dd.feature.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.rim.dd.core.dlms.ObisCatalog

/**
 * Мелкая приглушённая подпись под значением: «Название · OBIS-код» из [ObisCatalog]
 * (напр. «1 тариф (акт. потр.) · 1.0.1.8.1.255»); если кода в паспорте нет — один OBIS-код.
 */
@Composable
fun ObisCaption(obisCode: String, modifier: Modifier = Modifier) {
    Text(
        text = ObisCatalog.caption(obisCode),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Подпись для значений без OBIS-кода (напр. серийный номер из BLE-имени устройства). Намеренно
 * без «похожего» кода из паспорта: код рядом со значением означает, что оно прочитано из этого объекта.
 */
@Composable
fun SourceCaption(source: String, modifier: Modifier = Modifier) {
    Text(
        text = source,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
