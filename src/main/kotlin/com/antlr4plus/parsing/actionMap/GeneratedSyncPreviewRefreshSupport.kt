package com.antlr4plus.parsing.actionMap

import com.antlr4plus.filebinding.FileBindingService
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.settings.GrammarProperties
import com.antlr4plus.settings.SettingsPersistenceService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import java.io.File

internal object GeneratedSyncPreviewRefreshSupport {

    data class PreviewOwner(
        val g4pFile: VirtualFile,
        val grammarFile: VirtualFile,
    )

    fun resolveActivePreviewOwner(
        project: Project,
        generatedFile: File,
        grammarFileHint: File? = null,
    ): PreviewOwner? {
        val previewService = project.getService(PreviewService::class.java) ?: return null
        val state = previewService.state
        val g4pFile = state.g4pInputFile ?: return null
        val previewDir = state.activePreviewSourceDir ?: return null

        val generatedPath = canonicalSourcePath(generatedFile)
        if (!isWithinDirectory(generatedPath, canonicalSourcePath(File(previewDir)))) return null

        val grammarCandidates = listOfNotNull(state.grammarFile, state.lexerGrammarFile)
        val grammar =
            if (grammarFileHint != null) {
                val targetGrammarPath = canonicalSourcePath(grammarFileHint)
                grammarCandidates.firstOrNull {
                    canonicalSourcePath(File(it.path)) == targetGrammarPath
                }
            } else {
                grammarCandidates.firstOrNull()
            } ?: return null

        return PreviewOwner(g4pFile = g4pFile, grammarFile = grammar)
    }

    fun resolvePreviewOwner(
        project: Project,
        generatedFile: File,
        grammarFileHint: File? = null,
    ): PreviewOwner? {
        resolveActivePreviewOwner(project, generatedFile, grammarFileHint)?.let {
            return it
        }

        val targetPath = canonicalSourcePath(generatedFile)
        val grammarPathFilter = grammarFileHint?.let(::canonicalSourcePath)

        val matchingProfiles =
            collectBoundPreviewProfiles(project, grammarPathFilter).filter { profile ->
                candidateGeneratedFiles(profile).any { candidate ->
                    canonicalSourcePath(candidate) == targetPath
                }
            }

        return selectProfile(
                project = project,
                profiles = matchingProfiles,
            )
            ?.let { profile ->
                PreviewOwner(
                    g4pFile = profile.g4pFile,
                    grammarFile = profile.grammarFile,
                )
            }
    }

    fun resolvePendingRefreshTarget(project: Project, grammarFile: VirtualFile): VirtualFile? {
        resolvePendingRefreshTargetFast(project, grammarFile)?.let {
            return it
        }

        val mapService = project.getService(ActionLocationMapService::class.java) ?: return null
        val pending = mapService.peekPendingGeneratedSync(File(grammarFile.path)) ?: return null

        return selectPreferredProfile(
                project = project,
                grammarFile = grammarFile,
                preferredG4pPath = pending.preferredG4pPath,
            )
            ?.g4pFile
    }

    fun resolvePendingRefreshTargetFast(project: Project, grammarFile: VirtualFile): VirtualFile? {
        val mapService = project.getService(ActionLocationMapService::class.java) ?: return null
        val pending = mapService.peekPendingGeneratedSync(File(grammarFile.path)) ?: return null
        val bindingService = project.getService(FileBindingService::class.java)

        pending.preferredG4pPath?.let { preferredPath ->
            val preferredFile =
                LocalFileSystem.getInstance().findFileByPath(preferredPath)
                    ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(preferredPath)
            if (preferredFile != null && bindingService?.getBoundGrammar(preferredFile)?.path == grammarFile.path) {
                return preferredFile
            }
        }

        val previewService = project.getService(PreviewService::class.java) ?: return null
        val state = previewService.state
        val activeG4pFile = state.g4pInputFile ?: return null
        val matchesActiveGrammar =
            sequenceOf(state.grammarFile, state.lexerGrammarFile).filterNotNull().any {
                it.path == grammarFile.path
            }

        return if (matchesActiveGrammar) activeG4pFile else null
    }

    private data class BoundPreviewProfile(
        val g4pFile: VirtualFile,
        val grammarFile: VirtualFile,
        val previewDir: File,
        val extension: String,
    )

    private fun collectBoundPreviewProfiles(
        project: Project,
        grammarPathFilter: String? = null,
    ): List<BoundPreviewProfile> {
        val bindingService = project.getService(FileBindingService::class.java) ?: return emptyList()
        val settingsService = project.getService(SettingsPersistenceService::class.java) ?: return emptyList()
        val projectRoot = bindingService.getProjectRoot() ?: return emptyList()

        val profiles = mutableListOf<BoundPreviewProfile>()

        VfsUtilCore.visitChildrenRecursively(
            projectRoot,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return true
                    if (!file.name.endsWith(".g4p", ignoreCase = true)) return false

                    val grammarFile = bindingService.getBoundGrammar(file) ?: return false
                    if (grammarPathFilter != null && canonicalSourcePath(grammarFile.path) != grammarPathFilter)
                        return false

                    val persisted = settingsService.getSettings(file.path)
                    val runtime = persisted.previewRuntime ?: "Java"
                    val extension = generatedSourceExtension(runtime) ?: return false
                    val outputDir = persisted.outputDir ?: "gen"

                    profiles.add(
                        BoundPreviewProfile(
                            g4pFile = file,
                            grammarFile = grammarFile,
                            previewDir = resolvePreviewSourceDir(project, file, outputDir, runtime),
                            extension = extension,
                        ),
                    )
                    return false
                }
            },
        )

        return profiles
    }

    private fun selectPreferredProfile(
        project: Project,
        grammarFile: VirtualFile,
        preferredG4pPath: String?,
    ): BoundPreviewProfile? {
        val profiles = collectBoundPreviewProfiles(project, grammarFile.path)
        return selectProfile(project, profiles, preferredG4pPath)
    }

    private fun selectProfile(
        project: Project,
        profiles: List<BoundPreviewProfile>,
        preferredG4pPath: String? = null,
    ): BoundPreviewProfile? {
        if (profiles.isEmpty()) return null

        val preferredPath = preferredG4pPath?.let(::canonicalSourcePath)
        profiles
            .firstOrNull { canonicalSourcePath(it.g4pFile.path) == preferredPath }
            ?.let {
                return it
            }

        val activeG4pPath =
            project.getService(PreviewService::class.java)?.state?.g4pInputFile?.path?.let(::canonicalSourcePath)
        profiles
            .firstOrNull { canonicalSourcePath(it.g4pFile.path) == activeG4pPath }
            ?.let {
                return it
            }

        return profiles.first()
    }

    private fun candidateGeneratedFiles(profile: BoundPreviewProfile): List<File> {
        val grammarName = profile.grammarFile.nameWithoutExtension
        val ext = profile.extension
        val dir = profile.previewDir

        return listOf(
            File(dir, "$grammarName.$ext"),
            File(dir, "${grammarName}Parser.$ext"),
            File(dir, "${grammarName}Lexer.$ext"),
        )
    }

    private fun resolvePreviewSourceDir(
        project: Project,
        g4pFile: VirtualFile,
        outputDirSetting: String,
        runtime: String,
    ): File =
        File(
            GrammarProperties.resolvePreviewOutputDir(
                project = project,
                contentRoot = g4pFile.parent,
                configuredOutputDir = outputDirSetting,
                runtime = runtime,
            ),
        )

    private fun isWithinDirectory(path: String, directory: String): Boolean {
        val normalizedDirectory = directory.trimEnd(File.separatorChar)
        return path == normalizedDirectory || path.startsWith("$normalizedDirectory${File.separatorChar}")
    }
}
