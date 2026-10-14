package com.antlr4plus.parsing.actionMap

import com.antlr4plus.settings.SettingsPersistenceService
import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/** Caches action maps by generated source and tracks pending generated-to-grammar syncs. */
@Service(Service.Level.PROJECT)
class ActionLocationMapService(private val project: Project) {

    /** Receives map replacements and removals through the project message bus. */
    interface ActionLocationMapListener {
        fun mapRefreshed(grammarPath: String, map: ActionLocationMap?)
    }

    companion object {
        @JvmStatic
        val TOPIC: Topic<ActionLocationMapListener> =
            Topic.create(
                "ANTLR4Plus action location map",
                ActionLocationMapListener::class.java,
            )
    }

    data class PendingGeneratedSync(
        val preferredG4pPath: String?,
        val updatedAtMillis: Long,
    )

    /** A combined grammar can have separate parser and lexer maps. */
    private val maps = ConcurrentHashMap<String, ActionLocationMap>()
    private val pendingGeneratedSyncs = ConcurrentHashMap<String, PendingGeneratedSync>()

    fun get(grammarFile: File): ActionLocationMap? = getAll(grammarFile).firstOrNull()

    fun get(grammarPath: String): ActionLocationMap? = getAll(File(grammarPath)).firstOrNull()

    /** Returns every generated-source map for a grammar, with parser output before lexer output. */
    fun getAll(grammarFile: File): List<ActionLocationMap> {
        val grammarKey = canonicalKey(grammarFile)
        return maps.values
            .filter { canonicalKey(it.grammarFile) == grammarKey }
            .sortedWith(
                compareBy<ActionLocationMap> { generatedFilePriority(it.generatedFile) }
                    .thenBy { canonicalKey(it.generatedFile) },
            )
    }

    /** Finds a cached map or reconstructs it from the bound grammar and generated source. */
    fun getByGeneratedFile(file: File): ActionLocationMap? {
        val target = canonicalKey(file)
        maps[target]?.let {
            return it
        }

        val owner = GeneratedSyncPreviewRefreshSupport.resolvePreviewOwner(project, file) ?: return null
        val configuredEncoding = project.service<SettingsPersistenceService>().getSettings(owner.g4pFile.path).encoding
        val grammarCharset =
            configuredEncoding?.takeIf(String::isNotBlank)?.let {
                runCatching { Charset.forName(it) }.getOrNull()
            } ?: Charsets.UTF_8
        val rebuilt =
            ActionLocationMapBuilder.build(
                File(owner.grammarFile.path),
                file,
                grammarCharset,
            ) ?: return null

        val generatedKey = canonicalKey(rebuilt.generatedFile)
        val existing = maps.putIfAbsent(generatedKey, rebuilt)
        if (existing != null) return existing

        notifyRefreshed(canonicalKey(rebuilt.grammarFile), rebuilt)
        return rebuilt
    }

    fun getCachedByGeneratedFile(file: File): ActionLocationMap? = maps[canonicalKey(file)]

    /** Rebuilds the session-only generated-source map. */
    fun refreshFor(
        grammarFile: File,
        generatedFile: File,
        grammarCharset: Charset = Charsets.UTF_8,
    ) {
        val grammarKey = canonicalKey(grammarFile)
        val generatedKey = canonicalKey(generatedFile)
        val map = ActionLocationMapBuilder.build(grammarFile, generatedFile, grammarCharset)
        val outputIdentity = generatedOutputIdentity(generatedFile)
        maps.entries.removeIf { (existingGeneratedKey, existingMap) ->
            existingGeneratedKey != generatedKey &&
                canonicalKey(existingMap.grammarFile) == grammarKey &&
                generatedOutputIdentity(existingMap.generatedFile) == outputIdentity
        }
        if (map != null) {
            maps[generatedKey] = map
        } else {
            maps.remove(generatedKey)
        }
        pendingGeneratedSyncs.remove(grammarKey)
        notifyRefreshed(grammarKey, map)
    }

    fun invalidate(grammarFile: File) {
        val key = canonicalKey(grammarFile)
        maps.entries.removeIf { (_, map) -> canonicalKey(map.grammarFile) == key }
        pendingGeneratedSyncs.remove(key)
        notifyRefreshed(key, null)
    }

    fun clearAll() {
        val keys = maps.values.map { canonicalKey(it.grammarFile) }.distinct()
        maps.clear()
        pendingGeneratedSyncs.clear()
        keys.forEach { notifyRefreshed(it, null) }
    }

    /** Defers preview refresh after generated-source sync until the grammar editor gains focus. */
    fun markGeneratedSyncPending(grammarFile: File, preferredG4pPath: String? = null) {
        pendingGeneratedSyncs[canonicalKey(grammarFile)] =
            PendingGeneratedSync(
                preferredG4pPath = preferredG4pPath,
                updatedAtMillis = System.currentTimeMillis(),
            )
    }

    fun peekPendingGeneratedSync(grammarFile: File): PendingGeneratedSync? =
        pendingGeneratedSyncs[canonicalKey(grammarFile)]

    fun clearPendingGeneratedSync(grammarFile: File) {
        pendingGeneratedSyncs.remove(canonicalKey(grammarFile))
    }

    private fun notifyRefreshed(grammarPath: String, map: ActionLocationMap?) {
        if (project.isDisposed) return
        try {
            project.messageBus.syncPublisher(TOPIC).mapRefreshed(grammarPath, map)
        } catch (failure: IllegalStateException) {
            rethrowIfControlFlow(failure)
            if (!project.isDisposed) throw failure
        }
    }

    private fun canonicalKey(grammarFile: File): String =
        try {
            grammarFile.canonicalPath
        } catch (_: Exception) {
            grammarFile.absolutePath
        }

    private fun generatedFilePriority(file: File): Int =
        when {
            file.nameWithoutExtension.endsWith("Parser") -> 0
            file.nameWithoutExtension.endsWith("Lexer") -> 1
            else -> 2
        }

    private fun generatedOutputIdentity(file: File): String = file.nameWithoutExtension
}
