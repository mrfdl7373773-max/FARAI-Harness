package dev.harness.ui.settings

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import dev.harness.core.linux.LinuxState
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.harness.AppContainer
import dev.harness.core.DiffRow
import dev.harness.data.prompt.HistoryEntry
import dev.harness.data.prompt.PromptFile
import dev.harness.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState
    data class Ok(val models: Int, val latencyMs: Long, val modelFound: Boolean) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class EditorState(
    val path: String = "",
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val original: String = "",
    val text: String = "",
    val hasDefault: Boolean = false,
    val diff: List<DiffRow> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
) {
    val dirty: Boolean get() = loaded && text != original
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings?> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val prompts: StateFlow<List<PromptFile>> = container.prompts.version
        .map { withContext(Dispatchers.IO) { container.prompts.list() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val linuxState: StateFlow<LinuxState> = container.linux.state

    fun linuxLabel(s: LinuxState): String = when (s) {
        is LinuxState.Ready -> s.release
        is LinuxState.Working -> tr("Installing…")
        is LinuxState.Failed -> tr("Failed")
        LinuxState.NotInstalled -> tr("Not installed")
        is LinuxState.Unsupported -> tr("Unavailable")
    }

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    fun update(transform: (AppSettings) -> AppSettings) { viewModelScope.launch { container.settings.update(transform) } }

    fun completeOnboarding() = update { it.copy(onboardingComplete = true) }

    fun onboardingText(): String = runCatching { container.persona.onboarding() }.getOrElse { it.message ?: "" }

    fun testConnection() {
        _connection.value = ConnectionState.Testing
        viewModelScope.launch {
            val started = System.nanoTime()
            _connection.value = try {
                val list = container.client.listModels()
                _models.value = list
                val model = container.settings.current().provider.model
                ConnectionState.Ok(list.size, (System.nanoTime() - started) / 1_000_000, list.isEmpty() || model in list)
            } catch (e: Exception) {
                ConnectionState.Failed(e.message ?: e::class.java.simpleName)
            }
        }
    }

    // ---- Prompts -----------------------------------------------------------------------

    private val _editor = MutableStateFlow(EditorState())
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    fun openEditor(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val repo = container.prompts
            val text = repo.readOrNull(path)
            _editor.value = EditorState(
                path = path,
                loaded = text != null,
                missing = text == null,
                original = text.orEmpty(),
                text = text.orEmpty(),
                hasDefault = repo.defaultContent(path) != null,
                history = repo.historyOf(path),
            )
        }
    }

    fun editText(t: String) = _editor.update { it.copy(text = t) }

    fun save() {
        val e = _editor.value
        viewModelScope.launch(Dispatchers.IO) {
            container.prompts.write(e.path, e.text)
            _editor.update { it.copy(original = e.text, history = container.prompts.historyOf(e.path), loaded = true, missing = false) }
            _message.value = tr("Saved. The next message uses this text.")
        }
    }

    fun resetCurrent() {
        val p = _editor.value.path
        viewModelScope.launch(Dispatchers.IO) {
            if (container.prompts.reset(p)) { openEditor(p); _message.value = tr("Restored from defaults.") }
        }
    }

    fun computeDiff() {
        val e = _editor.value
        viewModelScope.launch(Dispatchers.Default) {
            val rows = dev.harness.core.LineDiff.sideBySide(container.prompts.defaultContent(e.path).orEmpty(), e.text)
            _editor.update { it.copy(diff = rows) }
        }
    }

    fun rollback(id: String) {
        val p = _editor.value.path
        viewModelScope.launch(Dispatchers.IO) {
            if (container.prompts.rollback(p, id)) { openEditor(p); _message.value = tr("Rolled back.") }
        }
    }

    fun previewVersion(id: String): String? = container.prompts.history.read(_editor.value.path, id)

    fun resetPrompt(path: String) {
        viewModelScope.launch(Dispatchers.IO) { if (container.prompts.reset(path)) _message.value = "Restored ${path.substringAfterLast('/')}." }
    }

    fun resetAllPrompts() {
        viewModelScope.launch(Dispatchers.IO) { val n = container.prompts.resetAll(); _message.value = trf("Restored {0} prompt files.", n) }
    }

    fun createPrompt(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = path.trim().let { if (it.endsWith(".md")) it else "$it.md" }
            runCatching { if (!container.prompts.exists(p)) container.prompts.write(p, "") }
                .onFailure { _message.value = it.message }
                .onSuccess { _message.value = trf("Created {0}.", p) }
        }
    }

    fun deletePrompt(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _message.value = if (container.prompts.delete(path)) trf("Deleted {0}.", path) else tr("Shipped prompts can be reset, not deleted.")
        }
    }

    fun importMarkdown(context: Context, uri: Uri, targetPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                container.prompts.write(targetPath, text)
            }.onSuccess { _message.value = trf("Imported into {0}.", targetPath) }.onFailure { _message.value = trf("Import failed: {0}", it.message) }
        }
    }

    fun importZip(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)!!.use { container.prompts.importZip(it) } }
                .onSuccess { _message.value = trf("Loaded {0} prompt files from zip.", it) }
                .onFailure { _message.value = trf("Import failed: {0}", it.message) }
        }
    }

    fun exportZip(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)!!.use { container.prompts.exportZip(it) } }
                .onSuccess { _message.value = trf("Exported {0} prompt files.", it) }
                .onFailure { _message.value = trf("Export failed: {0}", it.message) }
        }
    }

    // ---- Config + sessions -------------------------------------------------------------

    fun exportConfig(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(container.settings.exportToml().toByteArray()) } }
                .onSuccess { _message.value = tr("Exported config.toml (API key not included).") }
                .onFailure { _message.value = trf("Export failed: {0}", it.message) }
        }
    }

    fun importConfig(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                container.settings.importToml(text)
            }.onSuccess { _message.value = tr("Imported configuration.") }.onFailure { _message.value = trf("Import failed: {0}", it.message) }
        }
    }

    fun exportAllSessions(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val all = container.sessions.all()
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    ZipOutputStream(out).use { zip ->
                        all.forEachIndexed { i, s ->
                            val safe = s.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "session" }.take(40)
                            zip.putNextEntry(ZipEntry("%03d-%s.md".format(i + 1, safe)))
                            zip.write(container.sessions.transcript(s).toByteArray())
                            zip.closeEntry()
                        }
                    }
                }
                all.size
            }.onSuccess { _message.value = trf("Exported {0} sessions.", it) }.onFailure { _message.value = trf("Export failed: {0}", it.message) }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { container.sessions.deleteAll(); _message.value = tr("All sessions deleted.") }
    }

    val workspacePath: String get() = container.workspace.root.absolutePath
}
