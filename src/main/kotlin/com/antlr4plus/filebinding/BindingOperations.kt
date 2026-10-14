package com.antlr4plus.filebinding

import java.nio.file.Files
import java.nio.file.Path

/** Parses staged bindings, plans project-relative changes and applies filesystem operations. */
internal object BindingOperations {

    data class ParsedBinding(
        val g4pPath: String,
        val grammarPath: String,
    )

    sealed class PlannedAction {
        abstract val g4pPath: String
        abstract val grammarPath: String

        data class BindOnly(
            override val g4pPath: String,
            override val grammarPath: String,
        ) : PlannedAction()

        data class Rebind(
            override val g4pPath: String,
            val currentGrammar: String,
            override val grammarPath: String,
        ) : PlannedAction()

        data class RenameAndBind(
            val sourceFile: String, // For example, examples/test.sql.
            override val g4pPath: String, // For example, examples/test.sql.g4p.
            override val grammarPath: String,
        ) : PlannedAction()

        data class Skip(
            override val g4pPath: String,
            override val grammarPath: String,
            val reason: String,
        ) : PlannedAction()
    }

    data class ExecutionResult(
        val action: PlannedAction,
        val success: Boolean,
        val message: String,
    )

    fun parseBindings(content: String): List<ParsedBinding> =
        content
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("->") }
            .mapNotNull { line ->
                val parts = line.split("->", limit = 2)
                val g4pPath = parts[0].trim()
                val grammarPath = parts[1].trim()
                if (g4pPath.isNotBlank() && grammarPath.isNotBlank()) {
                    ParsedBinding(g4pPath, grammarPath)
                } else {
                    null
                }
            }

    /** Plans with direct filesystem checks. Call off the EDT. */
    fun buildExecutionPlan(
        rootPath: Path,
        bindings: List<ParsedBinding>,
        isCancelled: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<PlannedAction> {
        val actions = bindings.mapIndexed { index, binding ->
            val action =
                if (isCancelled()) {
                    PlannedAction.Skip(binding.g4pPath, binding.grammarPath, "Cancelled")
                } else {
                    buildActionForBinding(rootPath, binding)
                }
            onProgress(index + 1, bindings.size)
            action
        }
        val targetGroups =
            bindings.indices
                .mapNotNull { index ->
                    ProjectPathPolicy.resolve(rootPath, bindings[index].g4pPath.trim())?.let {
                        it to index
                    }
                }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        return actions.mapIndexed { index, action ->
            val target = ProjectPathPolicy.resolve(rootPath, bindings[index].g4pPath.trim()) ?: return@mapIndexed action
            val group = targetGroups[target].orEmpty()
            if (group.size <= 1) return@mapIndexed action

            val grammarTargets =
                group
                    .map { bindingIndex ->
                        ProjectPathPolicy.resolve(
                            rootPath,
                            bindings[bindingIndex].grammarPath.trim(),
                        )
                    }
                    .toSet()
            if (grammarTargets.size == 1 && index == group.first()) {
                action
            } else {
                val reason =
                    if (grammarTargets.size == 1) {
                        "Duplicate binding for the same profile"
                    } else {
                        "Conflicting bindings target the same profile"
                    }
                PlannedAction.Skip(action.g4pPath, action.grammarPath, reason)
            }
        }
    }

    private fun buildActionForBinding(
        rootPath: Path,
        binding: ParsedBinding,
    ): PlannedAction {
        val g4pPath = binding.g4pPath.trim()
        val grammarPath = binding.grammarPath.trim()

        if (!g4pPath.endsWith(".g4p", ignoreCase = true)) {
            return PlannedAction.Skip(g4pPath, grammarPath, "Path must end with .g4p")
        }
        if (!grammarPath.endsWith(".g4", ignoreCase = true)) {
            return PlannedAction.Skip(g4pPath, grammarPath, "Grammar path must end with .g4")
        }

        val grammarFsPath =
            ProjectPathPolicy.resolve(rootPath, grammarPath)
                ?: return PlannedAction.Skip(
                    g4pPath,
                    grammarPath,
                    "Grammar path must stay within the project",
                )
        val g4pFsPath =
            ProjectPathPolicy.resolve(rootPath, g4pPath)
                ?: return PlannedAction.Skip(
                    g4pPath,
                    grammarPath,
                    "G4P path must stay within the project",
                )

        if (!Files.isRegularFile(grammarFsPath)) {
            return PlannedAction.Skip(g4pPath, grammarPath, "Grammar file not found: $grammarPath")
        }

        if (Files.exists(g4pFsPath)) {
            if (!Files.isRegularFile(g4pFsPath)) {
                return PlannedAction.Skip(g4pPath, grammarPath, "G4P path is not a regular file")
            }
            val currentBinding =
                FileAttributeHelper.readAttribute(
                    g4pFsPath,
                    FileAttributeHelper.GRAMMAR_BINDING_ATTR,
                )
            if (currentBinding != null && currentBinding != grammarPath) {
                return PlannedAction.Rebind(g4pPath, currentBinding, grammarPath)
            }
            return PlannedAction.BindOnly(g4pPath, grammarPath)
        }

        val basePath = g4pPath.dropLast(4)
        val baseFsPath =
            ProjectPathPolicy.resolve(rootPath, basePath)
                ?: return PlannedAction.Skip(
                    g4pPath,
                    grammarPath,
                    "Source path must stay within the project",
                )

        if (Files.exists(baseFsPath) && !Files.isDirectory(baseFsPath)) {
            return PlannedAction.RenameAndBind(basePath, g4pPath, grammarPath)
        }

        return PlannedAction.Skip(
            g4pPath,
            grammarPath,
            "Source file not found (tried '$g4pPath' and '$basePath')",
        )
    }

    /** Applies one planned binding or rename through filesystem operations, without a VFS write action. */
    fun executePlannedAction(
        rootPath: Path,
        action: PlannedAction,
    ): ExecutionResult = executeBindingAction(rootPath, action)
}
