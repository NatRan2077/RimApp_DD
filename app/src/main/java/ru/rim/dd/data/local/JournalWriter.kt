package ru.rim.dd.data.local

interface JournalWriter {

    /** Начало подключения: карточка и лог байтов заводятся до первого обмена. */
    fun begin()

    /** Подключение не удалось — всё, что успело записаться, удаляется. */
    fun discard()

    /** Выход: сохраняет последний целый снимок, если «+» не нажимали. */
    fun end()

    /** Кнопка «+»: сохраняет текущий снимок. false — данных для журнала ещё нет. */
    fun commit(): Boolean

    /** Добавляет пришедшие байты в сырой лог (работает только при включённом флаге). */
    fun appendBytes(chunk: ByteArray)

    /** Обновляет снимок подключения после ПОЛНОСТЬЮ разобранного буфера. */
    fun update(
        meterType: String?,
        serialNumber: String?,
        energy: List<JournalLine>,
        voltage: String?,
        current: String?,
    )
}
