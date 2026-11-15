package com.relay.agents

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class RelayViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = Storage(app)
    private val store = ProjectStore(app)
    private val attStore = AttachmentStore(app)
    private val processor = AttachmentProcessor(app)
    private val backup = AgentBackupStore(app, storage)
    private val backupLock = Mutex()
    private var restoring = false

    /** مرفقات تُجهَّز لمهمة جديدة (قبل الضغط على ابدأ). */
    var pending by mutableStateOf<List<Attachment>>(emptyList())
        private set
    var processing by mutableStateOf(false)
        private set

    /** مرفقات المشروع الحالي. */
    var projectAttachments by mutableStateOf(AttachmentBundle())
        private set

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            processing = true
            val results = withContext(Dispatchers.IO) { uris.map { processor.process(it) } }
            pending = (pending + results).take(MAX_FILES)
            processing = false
        }
    }

    fun removePending(index: Int) {
        pending = pending.filterIndexed { i, _ -> i != index }
    }

    /** يحدّ مجموع الصور المرسلة للتحليل. */
    private fun trimImages(list: List<Attachment>): List<Attachment> {
        var left = AttachmentBundle.MAX_IMAGES
        return list.map { a ->
            val keep = a.images.take(left)
            left -= keep.size
            a.copy(images = keep)
        }
    }

    var agents by mutableStateOf(storage.loadAgents())
        private set
    var checkpoint by mutableStateOf(storage.loadCheckpoint())
        private set
    var states by mutableStateOf<List<AgentState>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var projects by mutableStateOf(store.list())
        private set

    /** اسم مجلد النسخة الاحتياطية المحلية (null = لم يُحدَّد). */
    var backupFolder by mutableStateOf<String?>(null)
        private set
    var backupStatus by mutableStateOf("")
        private set
    var backupPassSet by mutableStateOf(storage.backupPassphrase().isNotEmpty())
        private set
    var lastBackupAt by mutableStateOf(storage.lastBackupAt())
        private set

    init {
        restoreOnLaunch()
    }

    private var current: Project? = storage.currentProjectId()?.let { store.load(it) }
    private var job: Job? = null

    val currentProjectId: String? get() = current?.id

    // ---------- الوكلاء ----------

    /** طلب يدوي معلّق: المهمة التي على المستخدم تنفيذها في موقع الويب ولصق الرد. */
    class ManualRequest(val agent: AgentConfig, val prompt: String, val reply: CompletableDeferred<String?>)

    var manualRequest by mutableStateOf<ManualRequest?>(null)
        private set

    fun answerManual(text: String?) {
        manualRequest?.reply?.complete(text)
    }

    private suspend fun askManual(agent: AgentConfig, prompt: String): String? {
        val req = ManualRequest(agent, prompt, CompletableDeferred())
        manualRequest = req
        return try {
            req.reply.await()
        } finally {
            manualRequest = null
        }
    }

    fun addAgent(name: String, key: String, model: String, provider: Provider) {
        if (name.isBlank() || (key.isBlank() && !provider.manual)) return
        agents = agents + AgentConfig(
            UUID.randomUUID().toString(), name.trim(), key.trim(),
            model.trim().ifEmpty { provider.defaultModel }, true, provider
        )
        persistAgents()
    }

    fun removeAgent(id: String) {
        agents = agents.filterNot { it.id == id }
        persistAgents()
    }

    fun updateModel(id: String, model: String) {
        val m = model.trim().removePrefix("models/")
        if (m.isEmpty()) return
        agents = agents.map { if (it.id == id) it.copy(model = m) else it }
        persistAgents()
    }

    fun toggleAgent(id: String) {
        agents = agents.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        persistAgents()
    }

    // ---------- النسخة الاحتياطية المحلية ----------

    private fun folderLabel(): String? =
        backup.treeUri()?.let { Uri.decode(it.lastPathSegment ?: it.toString()) }

    private fun failureMessage(t: Throwable): String = when ((t as? BackupException)?.kind) {
        BackupException.Kind.NEED_PASSPHRASE -> "النسخة مشفّرة: أدخل كلمة المرور ثم اضغط «حفظ كلمة المرور»"
        BackupException.Kind.WRONG_PASSPHRASE -> "كلمة المرور لا تطابق النسخة المشفّرة"
        else -> "تعذّرت قراءة النسخة الاحتياطية: الملف غير صالح"
    }

    /** يحفظ الوكلاء وينسخهم تلقائياً إلى مجلد النسخة إن كان محدداً. */
    private fun persistAgents() {
        storage.saveAgents(agents)
        if (!restoring && backup.treeUri() != null) writeBackupAsync(agents)
    }

    private fun writeBackupAsync(list: List<AgentConfig>) {
        val pass = storage.backupPassphrase()
        viewModelScope.launch {
            backupLock.withLock {
                val ok = withContext(Dispatchers.IO) { backup.write(AgentBackup.encode(list, pass)) }
                if (ok) {
                    val t = System.currentTimeMillis()
                    storage.setLastBackupAt(t)
                    lastBackupAt = t
                    backupStatus = "تم حفظ نسخة احتياطية محلية"
                } else {
                    backupStatus = "تعذّر حفظ النسخة المحلية، أعد اختيار المجلد"
                }
            }
        }
    }

    /** عند فتح التطبيق: إن كانت قائمة الوكلاء فارغة والمجلد متاح، استعدهم تلقائياً. */
    private fun restoreOnLaunch() {
        backupFolder = folderLabel()
        if (backupFolder == null || agents.isNotEmpty()) return
        restoring = true
        viewModelScope.launch {
            try {
                tryRestoreFromFolder(auto = true)
            } finally {
                restoring = false
            }
        }
    }

    private suspend fun tryRestoreFromFolder(auto: Boolean) {
        val text = withContext(Dispatchers.IO) { backup.read() }
        if (text == null) {
            if (!auto) backupStatus = "لا توجد نسخة في المجلد بعد"
            return
        }
        val pass = storage.backupPassphrase()
        runCatching { withContext(Dispatchers.IO) { AgentBackup.decode(text, pass) } }
            .onSuccess { restored ->
                if (restored.isNotEmpty() && agents.isEmpty()) {
                    agents = AgentBackup.merge(agents, restored)
                    storage.saveAgents(agents)
                    backupStatus = "تمت استعادة ${restored.size} وكيل من النسخة المحلية"
                }
            }
            .onFailure { backupStatus = failureMessage(it) }
    }

    /** اختيار مجلد النسخة: يدمج ما فيه من وكلاء ثم يكتب النسخة الحالية. لا يستبدل ملفاً لا يمكن قراءته. */
    fun chooseBackupFolder(uri: Uri?) {
        if (uri == null) return
        backup.chooseTree(uri)
        backupFolder = folderLabel()
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { backup.read() }
            if (text != null) {
                val pass = storage.backupPassphrase()
                val res = runCatching { withContext(Dispatchers.IO) { AgentBackup.decode(text, pass) } }
                val err = res.exceptionOrNull()
                if (err != null) {
                    // لا نكتب فوق نسخة لا نستطيع قراءتها: نتراجع عن اختيار المجلد.
                    backup.clearTree()
                    backupFolder = null
                    backupStatus = failureMessage(err) + " (أُلغي اختيار المجلد، أعد اختياره بعد ذلك)"
                    return@launch
                }
                val merged = AgentBackup.merge(agents, res.getOrThrow())
                if (merged.size != agents.size) {
                    agents = merged
                    storage.saveAgents(agents)
                }
            }
            writeBackupAsync(agents)
        }
    }

    /** يحفظ كلمة مرور التشفير (فارغة = إلغاء التشفير) ويعيد كتابة النسخة بها. */
    fun setBackupPassphrase(p: String) {
        storage.setBackupPassphrase(p)
        backupPassSet = p.isNotEmpty()
        when {
            backup.treeUri() == null -> backupStatus = "تم حفظ كلمة المرور"
            agents.isEmpty() -> viewModelScope.launch { tryRestoreFromFolder(auto = false) }
            else -> writeBackupAsync(agents)
        }
    }

    fun saveBackupNow() {
        if (backup.treeUri() == null) {
            backupStatus = "اختر مجلد الحفظ أولاً"
            return
        }
        writeBackupAsync(agents)
    }

    /** نص النسخة الحالية للتصدير اليدوي إلى أي مكان. */
    fun agentsBackupText(): String = AgentBackup.encode(agents, storage.backupPassphrase())

    /** استعادة من ملف يختاره المستخدم؛ passphrase المكتوبة تُفضَّل على المحفوظة. */
    fun restoreFromFile(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text == null) {
                backupStatus = "تعذّرت قراءة الملف"
                return@launch
            }
            val pass = passphrase.ifEmpty { storage.backupPassphrase() }
            runCatching { withContext(Dispatchers.IO) { AgentBackup.decode(text, pass) } }
                .onSuccess { restored ->
                    val merged = AgentBackup.merge(agents, restored)
                    val added = merged.size - agents.size
                    agents = merged
                    persistAgents()
                    backupStatus = if (added > 0) "تمت استعادة $added وكيل" else "لا وكلاء جدد في الملف"
                }
                .onFailure { backupStatus = failureMessage(it) }
        }
    }

    // ---------- التشغيل والمشاريع ----------

    fun start(goal: String) {
        if (running || goal.isBlank()) return
        val g = goal.trim()
        val now = System.currentTimeMillis()
        val p = Project(UUID.randomUUID().toString(), g.take(50), now, now, Checkpoint(g))
        current = p
        storage.setCurrentProjectId(p.id)
        store.save(p)
        storage.clearCheckpoint()
        checkpoint = p.checkpoint
        val bundle = AttachmentBundle(trimImages(pending))
        attStore.save(p.id, bundle)
        projectAttachments = bundle
        pending = emptyList()
        refreshProjects()
        launchRun(g, null, bundle)
    }

    fun resume() {
        val cp = checkpoint ?: return
        if (running) return
        if (current == null) {
            val now = System.currentTimeMillis()
            current = Project(UUID.randomUUID().toString(), cp.goal.take(50), now, now, cp)
            storage.setCurrentProjectId(current!!.id)
        }
        launchRun(cp.goal, cp, projectAttachments)
    }

    fun stop() {
        job?.cancel()
    }

    /** يمسح المشروع الحالي من شاشة التشغيل فقط؛ يبقى محفوظاً في قائمة المشاريع. */
    fun reset() {
        job?.cancel()
        storage.clearCheckpoint()
        storage.setCurrentProjectId(null)
        current = null
        checkpoint = null
        projectAttachments = AttachmentBundle()
        states = emptyList()
    }

    /** يفتح مشروعاً محفوظاً ليُستأنف أو يُراجع. */
    fun openProject(id: String): Boolean {
        if (running) return false
        val p = store.load(id) ?: return false
        current = p
        storage.setCurrentProjectId(p.id)
        storage.saveCheckpoint(p.checkpoint)
        checkpoint = p.checkpoint
        projectAttachments = attStore.load(p.id)
        states = emptyList()
        return true
    }

    fun deleteProject(id: String) {
        if (running && current?.id == id) return
        store.delete(id)
        attStore.delete(id)
        if (current?.id == id) reset()
        refreshProjects()
    }

    fun markdownOf(id: String): String? = store.load(id)?.let { ProjectExport.toMarkdown(it) }

    fun allProjectsJson(): String = ProjectExport.allToJson(store.list())

    fun projectName(id: String): String = projects.firstOrNull { it.id == id }?.name ?: "project"

    companion object {
        const val MAX_FILES = 12
    }

    private fun refreshProjects() {
        projects = store.list()
    }

    private fun persist(cp: Checkpoint) {
        current = current?.copy(checkpoint = cp, updatedAt = System.currentTimeMillis())
            ?.also { store.save(it) }
    }

    private fun launchRun(goal: String, resume: Checkpoint?, bundle: AttachmentBundle) {
        running = true
        // ذاكرة الوكلاء: ملخص المشاريع الأخرى المحفوظة.
        val memory = MemoryBuilder.build(store.list().filter { it.id != current?.id }, goal)
        job = viewModelScope.launch {
            val orch = Orchestrator(storage, ::askManual) { cp, st ->
                checkpoint = cp
                states = st
                persist(cp)
            }
            try {
                orch.run(goal, agents, resume, memory, bundle) { digest ->
                    projectAttachments = projectAttachments.copy(imageDigest = digest)
                    current?.let { attStore.save(it.id, projectAttachments) }
                }
            } finally {
                running = false
                refreshProjects()
            }
        }
    }
}
