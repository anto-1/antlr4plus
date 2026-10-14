package com.antlr4plus.settings

import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.components.State
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.RefactoringEventListener
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
@State(name = "PS2", storages = [Storage(".plgn/fs.xml")])
class SettingsPersistenceService(val project: Project) :
    SimplePersistentStateComponent<SettingsPersistenceService.MyState>(MyState()), Disposable {

    class MyState : BaseState() {
        var fileData by map<String, com.antlr4plus.settings.State>()

        fun markModified() {
            incrementModificationCount()
        }
    }

    private val settingsLock = Any()
    private val recentlyLostLock = Any()
    private val recentlyLost = linkedSetOf<String>()

    init {
        project.messageBus
            .connect(this)
            .subscribe(
                RefactoringEventListener.REFACTORING_EVENT_TOPIC,
                object : RefactoringEventListener {
                    override fun refactoringDone(
                        refactoringId: String,
                        afterData: RefactoringEventData?,
                    ) {
                        if (hasRecentlyLost()) {
                            ApplicationManager.getApplication().invokeLater {
                                if (project.isDisposed) return@invokeLater
                                recoverRecentlyLost()
                            }
                        }
                    }
                },
            )

        val asyncListener =
            object : AsyncFileListener {
                override fun prepareChange(
                    events: List<VFileEvent>,
                ): AsyncFileListener.ChangeApplier? {
                    val moveEvents = events.filterIsInstance<VFileMoveEvent>()
                    val nameChangeEvents =
                        events.filterIsInstance<VFilePropertyChangeEvent>().filter {
                            it.propertyName == VirtualFile.PROP_NAME
                        }
                    if (moveEvents.isNotEmpty() || nameChangeEvents.isNotEmpty()) {
                        return object : AsyncFileListener.ChangeApplier {
                            override fun beforeVfsChange() {
                                // Keep this override: newer IDE defaults assert write access.
                            }

                            override fun afterVfsChange() {
                                // UI and formatting consume this state; apply VFS-driven mutations
                                // on the EDT.
                                ApplicationManager.getApplication().invokeLater {
                                    if (project.isDisposed) return@invokeLater
                                    processChanges(moveEvents, nameChangeEvents)
                                    if (hasRecentlyLost()) recoverRecentlyLost()
                                }
                            }
                        }
                    }
                    return null
                }
            }
        VirtualFileManager.getInstance().addAsyncFileListener(asyncListener, this)
    }

    override fun dispose() {
        synchronized(recentlyLostLock) {
            recentlyLost.clear()
        }
    }

    fun getSettings(key: String): com.antlr4plus.settings.State =
        synchronized(settingsLock) {
            val existed = state.fileData.containsKey(key)
            val result = state.fileData.getOrPut(key) { State() }
            if (!existed) state.markModified()
            result
        }

    fun markModified() {
        state.markModified()
        SaveAndSyncHandler.getInstance().scheduleProjectSave(project)
    }

    private fun processChanges(
        moveEvents: List<VFileMoveEvent>,
        nameChangeEvents: List<VFilePropertyChangeEvent>,
    ) {
        val changed =
            synchronized(settingsLock) {
                var changedInsideLock = false

                for (event in nameChangeEvents) {
                    val file = event.file
                    if (!file.isDirectory) continue
                    val oldName = event.oldValue as? String ?: continue
                    val newName = event.newValue as? String ?: continue
                    val parent = file.parent ?: continue
                    val oldDirPath = "${parent.path}/$oldName"
                    val newDirPath = "${parent.path}/$newName"
                    updatePathPrefixes(oldDirPath, newDirPath)
                    changedInsideLock = true
                }

                for (event in nameChangeEvents) {
                    val file = event.file
                    if (file.isDirectory || file.extension?.equals("g4p", ignoreCase = true) != true) continue
                    val oldName = event.oldValue as? String ?: continue
                    val newName = event.newValue as? String ?: continue
                    val parent = file.parent ?: continue
                    val oldPath = "${parent.path}/$oldName"
                    val newPath = "${parent.path}/$newName"
                    state.fileData.remove(oldPath)?.let { settingsState ->
                        state.fileData[newPath] = settingsState
                        changedInsideLock = true
                    }
                }

                for (event in moveEvents) {
                    val file = event.file
                    if (!file.isDirectory) continue
                    updatePathPrefixes(event.oldPath, event.newPath)
                    changedInsideLock = true
                }

                for (event in moveEvents) {
                    val file = event.file
                    if (file.isDirectory || file.extension?.equals("g4p", ignoreCase = true) != true) continue
                    val oldPath = event.oldPath
                    val newPath = event.newPath
                    state.fileData.remove(oldPath)?.let { settingsState ->
                        state.fileData[newPath] = settingsState
                        changedInsideLock = true
                    }
                        ?: run {
                            rememberRecentlyLost(oldPath)
                            changedInsideLock = true
                        }
                }
                changedInsideLock
            }

        if (changed) markModified()
    }

    private fun updatePathPrefixes(oldPrefix: String, newPrefix: String) {
        val keysToUpdate = state.fileData.keys.filter { it.startsWith("$oldPrefix/") }
        for (oldKey in keysToUpdate) {
            val newKey = newPrefix + oldKey.substring(oldPrefix.length)
            state.fileData[oldKey]?.let { settingsState ->
                state.fileData[newKey] = settingsState
                state.fileData.remove(oldKey)
            }
        }
    }

    private fun recoverRecentlyLost() {
        ProgressManager.getInstance()
            .run(
                object : Task.Backgroundable(project, "Recovering Recent Settings", true) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.text = "Recovering file settings..."
                        indicator.isIndeterminate = true

                        val lostToRecover = drainRecentlyLost()

                        if (lostToRecover.isEmpty()) return

                        val g4pNames = lostToRecover.map { Paths.get(it).fileName.toString() }.toSet()

                        var g4pMap: Map<String, Collection<VirtualFile>> = emptyMap()

                        ApplicationManager.getApplication().runReadAction {
                            g4pMap = g4pNames.associateWith {
                                FilenameIndex.getVirtualFilesByName(
                                        it,
                                        GlobalSearchScope.projectScope(project),
                                    )
                                    .filter { file -> file.isValid }
                            }
                        }

                        val rebound =
                            mutableListOf<
                                Pair<String, String>,
                            >() // Old path paired with its recovered path.

                        for (oldPath in lostToRecover) {
                            indicator.text = "Recovering settings for ${Paths.get(oldPath).fileName}"
                            indicator.checkCanceled()

                            val g4pName = Paths.get(oldPath).fileName.toString()
                            val g4pFiles = g4pMap[g4pName] ?: emptyList()

                            if (g4pFiles.size == 1) {
                                val newFile = g4pFiles.first().takeIf { it.isValid }
                                if (newFile != null) {
                                    rebound.add(Pair(oldPath, newFile.path))
                                }
                            }
                        }

                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed) return@invokeLater
                            val changed =
                                synchronized(settingsLock) {
                                    var changedInsideLock = false
                                    rebound.forEach { (oldPath, newPath) ->
                                        state.fileData.remove(oldPath)?.let { settingsState ->
                                            state.fileData[newPath] = settingsState
                                            changedInsideLock = true
                                        }
                                    }
                                    changedInsideLock
                                }
                            if (changed) markModified()
                        }
                    }
                },
            )
    }

    private fun hasRecentlyLost(): Boolean = synchronized(recentlyLostLock) { recentlyLost.isNotEmpty() }

    private fun rememberRecentlyLost(path: String) {
        synchronized(recentlyLostLock) { recentlyLost.add(path) }
    }

    private fun drainRecentlyLost(): List<String> =
        synchronized(recentlyLostLock) {
            recentlyLost.toList().also { recentlyLost.clear() }
        }
}
