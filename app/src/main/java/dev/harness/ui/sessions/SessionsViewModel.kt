package dev.harness.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.harness.AppContainer
import dev.harness.data.session.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionsViewModel(private val container: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")

    val sessions: StateFlow<List<SessionEntity>?> = combine(container.sessions.sessions, query) { list, q ->
        val t = q.trim()
        // A new session stays out of the history until its first message.
        val started = list.filter { it.messageCount > 0 || container.runner.isRunning(it.id) }
        if (t.isEmpty()) started else started.filter { it.title.contains(t, true) || it.tags.contains(t, true) || it.model.contains(t, true) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val e = container.sessions.create(container.settings.current().provider.model)
            onCreated(e.id)
        }
    }

    fun setPinned(ids: Collection<String>, pinned: Boolean) { viewModelScope.launch { ids.forEach { container.sessions.setPinned(it, pinned) } } }
    fun deleteMany(ids: Collection<String>) { viewModelScope.launch { ids.forEach { container.sessions.delete(it) } } }
    fun duplicateMany(ids: Collection<String>) { viewModelScope.launch { ids.forEach { container.sessions.fork(it) } } }

    fun rename(id: String, title: String) { viewModelScope.launch { container.sessions.rename(id, title) } }
    fun delete(id: String) { viewModelScope.launch { container.sessions.delete(id) } }
    fun duplicate(id: String) { viewModelScope.launch { container.sessions.fork(id) } }

    /** Sessions with a run in flight right now (background runs included). */
    fun runningIds(ids: List<String>): Set<String> = ids.filter { container.runner.isRunning(it) }.toSet()

    fun transcript(e: SessionEntity, onReady: (String) -> Unit) { onReady(container.sessions.transcript(e)) }
}
