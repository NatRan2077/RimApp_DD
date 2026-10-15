package ru.rim.dd.feature.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.rim.dd.data.local.JournalEntry
import ru.rim.dd.data.local.JournalStore
import java.io.File
import javax.inject.Inject

/** Журнал подключений: список карточек, очистка (вся / по выбору) и экспорт в .txt. */
@HiltViewModel
class JournalViewModel @Inject constructor(
    private val store: JournalStore,
) : ViewModel() {

    val entries: StateFlow<List<JournalEntry>> = store.entries

    init {
        viewModelScope.launch(Dispatchers.IO) { store.refresh() }
    }

    fun delete(ids: Set<Long>) {
        viewModelScope.launch(Dispatchers.IO) { store.delete(ids) }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) { store.clearAll() }
    }

    /** Файл журнала для отправки: выбранные карточки или (ids == null) все. */
    suspend fun exportText(ids: Set<Long>?): File? = withContext(Dispatchers.IO) { store.exportText(ids) }

    /** HEX-лог байтов одного подключения. */
    suspend fun exportRaw(id: Long): File? = withContext(Dispatchers.IO) { store.exportRaw(id) }
}
