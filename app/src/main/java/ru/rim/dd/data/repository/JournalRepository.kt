package ru.rim.dd.data.repository

/** Журнал подключений: ручное добавление записи (кнопка «+»). */
interface JournalRepository {
    suspend fun addToJournal(): Boolean
}
