package com.antlr4plus.settings

import com.antlr4plus.state.PreviewState
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

class GrammarProperties {
    companion object {
        private val LOG = Logger.getInstance(GrammarProperties::class.java)

        /** Resolves configured output, falling back to contentRoot/gen when the requested path is out of bounds. */
        fun resolveOutputDirName(
            state: PreviewState,
            project: Project,
            contentRoot: VirtualFile,
            packageName: String,
        ): String =
            resolveOutputDirSetting(
                project = project,
                contentRoot = contentRoot,
                configuredOutputDir = state.liveSettings?.outputDir,
                packageName = packageName,
            )

        internal fun resolveOutputDirSetting(
            project: Project,
            contentRoot: VirtualFile,
            configuredOutputDir: String?,
            packageName: String = "",
        ): String {
            val outputDir =
                PathMacroManager.getInstance(project)
                    .expandPath(configuredOutputDir?.takeIf { it.isNotBlank() } ?: "gen")
            val outputFile = File(outputDir)

            val resolvedPath =
                when {
                    outputFile.isAbsolute -> outputDir
                    else -> {
                        val packagePath = packageName.replace('.', File.separatorChar)
                        val fullPath =
                            if (packagePath.isNotEmpty()) {
                                "${contentRoot.path}${File.separator}$outputDir${File.separator}$packagePath"
                            } else {
                                "${contentRoot.path}${File.separator}$outputDir"
                            }
                        fullPath
                    }
                }

            if (!isWithinProjectBounds(project, resolvedPath)) {
                LOG.warn(
                    "SECURITY: Output directory '$resolvedPath' is outside project bounds - using default",
                )
                val safeDefault = "${contentRoot.path}${File.separator}gen"
                if (packageName.isNotEmpty()) {
                    val packagePath = packageName.replace('.', File.separatorChar)
                    return "$safeDefault${File.separator}$packagePath"
                }
                return safeDefault
            }

            return resolvedPath
        }

        internal fun resolvePreviewOutputDir(
            state: PreviewState,
            project: Project,
            contentRoot: VirtualFile,
            runtime: String,
        ): String =
            resolvePreviewOutputDir(
                project = project,
                contentRoot = contentRoot,
                configuredOutputDir = state.liveSettings?.outputDir,
                runtime = runtime,
            )

        /** One replaceable preview source directory per runtime. */
        internal fun resolvePreviewOutputDir(
            project: Project,
            contentRoot: VirtualFile,
            configuredOutputDir: String?,
            runtime: String,
        ): String {
            val runtimeDirectory =
                when (runtime.lowercase()) {
                    "kotlin" -> "kotlin"
                    else -> "java"
                }
            val baseOutput = resolveOutputDirSetting(project, contentRoot, configuredOutputDir)
            return File(baseOutput, "preview${File.separator}$runtimeDirectory").path
        }

        /** Rejects deletion of project roots or protected inputs; never substitutes a fallback directory. */
        internal fun resolveOutputDirForDeletion(
            state: PreviewState,
            project: Project,
            contentRoot: VirtualFile,
            protectedPaths: Collection<String>,
        ): File? =
            resolveOutputDirForDeletion(
                project,
                contentRoot,
                state.liveSettings?.outputDir,
                protectedPaths,
            )

        internal fun resolveOutputDirForDeletion(
            project: Project,
            contentRoot: VirtualFile,
            configuredOutputDir: String?,
            protectedPaths: Collection<String>,
        ): File? {
            val configured =
                PathMacroManager.getInstance(project)
                    .expandPath(configuredOutputDir?.takeIf { it.isNotBlank() } ?: "gen")
            val unresolved = File(configured)
            val output = if (unresolved.isAbsolute) unresolved else File(contentRoot.path, configured)
            val canonicalOutput =
                try {
                    output.canonicalFile
                } catch (failure: Exception) {
                    LOG.warn(
                        "Cannot canonicalize output directory for deletion: ${output.path}",
                        failure,
                    )
                    return null
                }

            if (!isWithinProjectBounds(project, canonicalOutput.path)) {
                LOG.warn(
                    "SECURITY: Refusing to delete output outside project bounds: ${canonicalOutput.path}",
                )
                return null
            }

            val protectedRoots = buildList {
                project.basePath?.let { add(File(it).canonicalFile) }
                ProjectRootManager.getInstance(project).contentRoots.forEach {
                    add(File(it.path).canonicalFile)
                }
            }
            if (protectedRoots.any { it == canonicalOutput }) {
                LOG.warn(
                    "SECURITY: Refusing to delete a project/content root: ${canonicalOutput.path}",
                )
                return null
            }

            val outputPrefix = canonicalOutput.path + File.separator
            val containsProtectedInput = protectedPaths.any { protectedPath ->
                val canonicalProtected =
                    try {
                        File(protectedPath).canonicalPath
                    } catch (_: Exception) {
                        return@any true
                    }
                canonicalProtected == canonicalOutput.path || canonicalProtected.startsWith(outputPrefix)
            }
            if (containsProtectedInput) {
                LOG.warn(
                    "SECURITY: Refusing to delete output directory containing a grammar/profile input: ${canonicalOutput.path}",
                )
                return null
            }

            return canonicalOutput
        }

        /** Resolves libDir relative to the grammar directory, defaulting there; returns null outside project bounds. */
        fun resolveLibDir(
            state: PreviewState,
            project: Project,
            sourcePath: String,
        ): String? {
            val libDir = PathMacroManager.getInstance(project).expandPath(state.liveSettings?.libDir ?: "")

            val resolvedPath =
                when {
                    libDir.isBlank() -> sourcePath
                    File(libDir).isAbsolute -> libDir
                    else -> "$sourcePath${File.separator}$libDir"
                }

            if (!isWithinProjectBounds(project, resolvedPath)) {
                LOG.warn(
                    "SECURITY: Lib directory '$resolvedPath' is outside project bounds - rejecting",
                )
                return null
            }

            return resolvedPath
        }

        /** Checks canonical containment in the project base directory or any content root. */
        internal fun isWithinProjectBounds(project: Project, path: String): Boolean {
            try {
                val canonicalPath = File(path).canonicalPath

                val projectBasePath = project.basePath
                if (projectBasePath != null) {
                    val canonicalProjectPath = File(projectBasePath).canonicalPath
                    if (
                        canonicalPath.startsWith(canonicalProjectPath + File.separator) ||
                            canonicalPath == canonicalProjectPath
                    ) {
                        return true
                    }
                }

                val contentRoots = ProjectRootManager.getInstance(project).contentRoots
                for (root in contentRoots) {
                    val canonicalRootPath = File(root.path).canonicalPath
                    if (
                        canonicalPath.startsWith(canonicalRootPath + File.separator) ||
                            canonicalPath == canonicalRootPath
                    ) {
                        return true
                    }
                }

                return false
            } catch (e: Exception) {
                LOG.warn("Error checking path bounds: ${e.message}")
                return false
            }
        }
    }
}
