package ru.rim.dd.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.rim.dd.data.journal.JournalRecorder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JournalRepositoryImpl @Inject constructor(
    private val journal: JournalRecorder,
) : JournalRepository {

    override suspend fun addToJournal(): Boolean = withContext(Dispatchers.IO) { journal.commit() }
}
