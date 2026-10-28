package ru.rim.dd.data.repository

import kotlinx.coroutines.withContext
import ru.rim.dd.core.coroutines.DispatcherProvider
import ru.rim.dd.data.journal.JournalRecorder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JournalRepositoryImpl @Inject constructor(
    private val journal: JournalRecorder,
    private val dispatchers: DispatcherProvider,
) : JournalRepository {

    override suspend fun addToJournal(): Boolean = withContext(dispatchers.io) { journal.commit() }
}
