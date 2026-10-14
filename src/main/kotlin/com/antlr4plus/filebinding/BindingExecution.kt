package com.antlr4plus.filebinding

import com.antlr4plus.filebinding.BindingOperations.ExecutionResult
import com.antlr4plus.filebinding.BindingOperations.PlannedAction
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path

internal fun executeBindingAction(
    rootPath: Path,
    action: PlannedAction,
): ExecutionResult =
    when (action) {
        is PlannedAction.Skip -> ExecutionResult(action, false, "Skipped: ${action.reason}")
        is PlannedAction.BindOnly -> executeBindDirect(rootPath, action.g4pPath, action.grammarPath, action)
        is PlannedAction.Rebind -> executeBindDirect(rootPath, action.g4pPath, action.grammarPath, action)
        is PlannedAction.RenameAndBind -> executeRenameAndBindDirect(rootPath, action)
    }

private fun executeBindDirect(
    rootPath: Path,
    g4pRelPath: String,
    grammarRelPath: String,
    action: PlannedAction,
): ExecutionResult {
    return try {
        val g4pPath =
            ProjectPathPolicy.resolve(rootPath, g4pRelPath)
                ?: return ExecutionResult(action, false, "G4P path escapes the project")
        if (!Files.isRegularFile(g4pPath)) {
            return ExecutionResult(action, false, "G4P file not found")
        }
        val grammarPath =
            ProjectPathPolicy.resolve(rootPath, grammarRelPath)?.takeIf { Files.isRegularFile(it) }
                ?: return ExecutionResult(action, false, "Grammar file not found")
        if (!grammarPath.fileName.toString().endsWith(".g4", ignoreCase = true)) {
            return ExecutionResult(action, false, "Grammar path must end with .g4")
        }
        val success =
            FileAttributeHelper.writeAttribute(
                g4pPath,
                FileAttributeHelper.GRAMMAR_BINDING_ATTR,
                grammarRelPath,
            )
        if (success) {
            ExecutionResult(action, true, "Bound successfully")
        } else {
            ExecutionResult(action, false, "Binding failed (xattr write error)")
        }
    } catch (e: Exception) {
        ExecutionResult(action, false, "Error: ${e.message}")
    }
}

/** Renames before writing the binding; a failed attribute write leaves the file renamed. */
private fun executeRenameAndBindDirect(
    rootPath: Path,
    action: PlannedAction.RenameAndBind,
): ExecutionResult {
    return try {
        val sourcePath =
            ProjectPathPolicy.resolve(rootPath, action.sourceFile)
                ?: return ExecutionResult(action, false, "Source path escapes the project")
        if (!Files.isRegularFile(sourcePath)) {
            return ExecutionResult(action, false, "Source file not found: ${action.sourceFile}")
        }
        val grammarPath =
            ProjectPathPolicy.resolve(rootPath, action.grammarPath)?.takeIf {
                Files.isRegularFile(it)
            } ?: return ExecutionResult(action, false, "Grammar file not found")
        if (!grammarPath.fileName.toString().endsWith(".g4", ignoreCase = true)) {
            return ExecutionResult(action, false, "Grammar path must end with .g4")
        }
        val targetPath =
            ProjectPathPolicy.resolve(rootPath, action.g4pPath)
                ?: return ExecutionResult(action, false, "G4P path escapes the project")
        moveWithRetry(sourcePath, targetPath)

        val success =
            FileAttributeHelper.writeAttribute(
                targetPath,
                FileAttributeHelper.GRAMMAR_BINDING_ATTR,
                action.grammarPath,
            )
        if (success) {
            ExecutionResult(action, true, "Renamed and bound successfully")
        } else {
            ExecutionResult(action, false, "Renamed but binding failed (xattr write error)")
        }
    } catch (e: Exception) {
        ExecutionResult(action, false, "Error: ${e.message}")
    }
}

/** Retries Windows moves to tolerate transient file locks. */
private fun moveWithRetry(source: Path, target: Path) {
    var lastException: Exception? = null
    val maxAttempts = if (System.getProperty("os.name").lowercase().contains("win")) 3 else 1
    for (attempt in 1..maxAttempts) {
        try {
            Files.move(source, target)
            return
        } catch (e: AccessDeniedException) {
            lastException = e
            if (attempt < maxAttempts) Thread.sleep(50L * attempt)
        }
    }
    throw lastException!!
}
