package ru.rim.dd.feature.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.rim.dd.core.dlms.ObisCatalog

/**
 * [Android-патч] Подпись под значением на экране: «Название объекта · OBIS-код» мелким
 * приглушённым шрифтом — например, «1 тариф (акт. потр.) · 1.0.1.8.1.255» под строкой
 * «Т1: 2.640 кВт·ч».
 *
 * Зачем: до этого на экранах были только сами числа, и понять, ОТКУДА именно взято значение
 * (какой объект прибора за него отвечает), можно было только по исходникам. Теперь у каждой
 * величины видно и её название из паспорта прибора, и точный OBIS-код — это одновременно и
 * пояснение для пользователя, и то, по чему значение сверяется с документацией на счётчик
 * или ищется в логе обмена.
 *
 * Название берётся из [ObisCatalog] (выгрузка объектов прибора). Если кода в паспорте нет,
 * показывается голый OBIS-код — подпись остаётся полезной и не превращается в «неизвестно».
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
 * [Android-патч] Подпись для значений, у которых OBIS-кода НЕТ вообще — они получены не из
 * объектов прибора, а другим путём (например, серийный номер разбирается из имени, которое
 * устройство рекламирует в BLE-эфире, см. applyDeviceNameInfo в MeterRepositoryImpl).
 *
 * Такие величины намеренно не подписываются «похожим по смыслу» OBIS-кодом из паспорта: код
 * рядом со значением означает «значение прочитано ИМЕННО из этого объекта», и подставить сюда
 * 0.0.96.1.0.255 только потому, что он тоже называется «Серийный номер», означало бы соврать
 * в отладочной подписи — ровно там, где на неё будут опираться при сверке с документацией.
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
