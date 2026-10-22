package ru.rim.dd.data.repository

/** Журнал подключений: ручное добавление записи (кнопка «+»). */
interface JournalRepository {
    /**
     * Записывает в журнал последние целиком принятые данные. false — данных ещё нет.
     * Если не вызывать, они сохранятся сами при выходе.
     */
    suspend fun addToJournal(): Boolean
}
