package com.antlr4plus.previewService

import com.antlr4plus.codeGen.GenAntlrCode
import com.antlr4plus.util.GeneratedWorkspaceFileOps
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Defines exactly which preview artifacts belong to one grammar generation. */
internal object PreviewGeneratedArtifactPolicy {

    data class Plan(
        val ownedSourceNames: Set<String>,
        val requiredSourceNames: Set<String>,
        val ownedMetadataNames: Set<String>,
        val generationIdentity: String,
    )

    private const val LEGACY_METADATA_DIRECTORY = ".antlr4plus"

    /**
     * Derived preview fingerprints belong to one project session. A fresh session deliberately regenerates once because
     * generated-file trust is session-scoped as well.
     */
    class SessionCache {
        private val dependencySnapshots = ConcurrentHashMap<String, String>()

        fun hasReusableSources(
            outputDir: File,
            plan: Plan,
            grammarFiles: Collection<File>,
        ): Boolean {
            val outputPath = outputDir.toPath()
            if (!GeneratedWorkspaceFileOps.isDirectory(outputPath) || plan.requiredSourceNames.isEmpty()) return false
            if (
                !plan.requiredSourceNames.all { name ->
                    GeneratedWorkspaceFileOps.isRegularFile(outputPath.resolve(name))
                }
            )
                return false

            val expectedSnapshot = captureDependencySnapshot(plan, grammarFiles) ?: return false
            return dependencySnapshots[outputKey(outputDir)] == expectedSnapshot
        }

        /** Records a successful generation only if its inputs remained stable throughout codegen. */
        fun recordGeneration(
            outputDir: File,
            plan: Plan,
            grammarFiles: Collection<File>,
            expectedSnapshot: String? = null,
        ): Boolean {
            if (!GeneratedWorkspaceFileOps.isDirectory(outputDir.toPath())) return false
            val currentSnapshot = captureDependencySnapshot(plan, grammarFiles) ?: return false
            if (expectedSnapshot != null && currentSnapshot != expectedSnapshot) return false
            dependencySnapshots[outputKey(outputDir)] = currentSnapshot
            return true
        }

        fun invalidate(outputDir: File) {
            dependencySnapshots.remove(outputKey(outputDir))
        }

        fun clear() {
            dependencySnapshots.clear()
        }

        private fun outputKey(outputDir: File): String = outputDir.toPath().toAbsolutePath().normalize().toString()
    }

    fun buildPlan(
        parserGrammarFile: File,
        lexerGrammarFile: File?,
        charset: Charset,
        sourceExtension: String,
        generateListener: Boolean,
        generateVisitor: Boolean,
        packageName: String? = null,
    ): Plan {
        val normalizedExtension = sourceExtension.trimStart('.')
        val ownedSourceNames =
            GenAntlrCode.computeExpectedAntlrOutputFiles(
                    parserGrammarFile,
                    lexerGrammarFile,
                    charset,
                )
                .mapTo(linkedSetOf()) { javaName ->
                    javaName.removeSuffix(".java") + "." + normalizedExtension
                }

        val requiredSourceNames =
            ownedSourceNames.filterTo(linkedSetOf()) { name ->
                when {
                    name.isListenerSource() -> generateListener
                    name.isVisitorSource() -> generateVisitor
                    else -> true
                }
            }

        val grammarNames = buildSet {
            add(parserGrammarFile.nameWithoutExtension)
            lexerGrammarFile?.let { add(it.nameWithoutExtension) }
            ownedSourceNames
                .asSequence()
                .filterNot { it.isListenerSource() || it.isVisitorSource() }
                .mapTo(this) { it.substringBeforeLast('.') }
        }
        val ownedMetadataNames =
            grammarNames.flatMapTo(linkedSetOf()) { name ->
                listOf("$name.tokens", "$name.interp")
            }

        return Plan(
            ownedSourceNames = ownedSourceNames,
            requiredSourceNames = requiredSourceNames,
            ownedMetadataNames = ownedMetadataNames,
            generationIdentity =
                generationIdentity(
                    parserGrammarFile,
                    lexerGrammarFile,
                    charset,
                    normalizedExtension,
                    generateListener,
                    generateVisitor,
                    packageName,
                ),
        )
    }

    /** Removes replaceable artifacts, compiled output, and obsolete on-disk metadata. */
    fun cleanForRegeneration(outputDir: File, plan: Plan): Boolean {
        val outputPath = outputDir.toPath()
        if (!GeneratedWorkspaceFileOps.exists(outputPath)) return true
        if (!GeneratedWorkspaceFileOps.isDirectory(outputPath)) return false

        var cleaned = true

        val ownedTopLevelNames = plan.ownedSourceNames + plan.ownedMetadataNames
        ownedTopLevelNames.forEach { name ->
            cleaned = GeneratedWorkspaceFileOps.deleteFileOrLink(outputPath.resolve(name)) && cleaned
        }

        cleaned = GeneratedWorkspaceFileOps.deleteTree(outputPath.resolve(LEGACY_METADATA_DIRECTORY)) && cleaned
        cleaned = GeneratedWorkspaceFileOps.deleteTree(outputPath.resolve("compiled")) && cleaned
        return cleaned
    }

    fun ownedSourcesIn(outputDir: File, plan: Plan): List<File> = regularSourcesIn(outputDir, plan.ownedSourceNames)

    fun requiredSourcesIn(outputDir: File, plan: Plan): List<File> =
        regularSourcesIn(outputDir, plan.requiredSourceNames)

    private fun regularSourcesIn(outputDir: File, names: Set<String>): List<File> {
        val outputPath = outputDir.toPath()
        if (!GeneratedWorkspaceFileOps.isDirectory(outputPath)) return emptyList()
        return names.mapNotNull { name ->
            outputPath.resolve(name).takeIf(GeneratedWorkspaceFileOps::isRegularFile)?.toFile()
        }
    }

    fun captureDependencySnapshot(plan: Plan, grammarFiles: Collection<File>): String? =
        dependencyFingerprint(plan.generationIdentity, grammarFiles)

    private fun String.isListenerSource(): Boolean {
        val stem = substringBeforeLast('.')
        return stem.endsWith("Listener") || stem.endsWith("BaseListener")
    }

    private fun String.isVisitorSource(): Boolean {
        val stem = substringBeforeLast('.')
        return stem.endsWith("Visitor") || stem.endsWith("BaseVisitor")
    }

    private fun generationIdentity(
        parserGrammarFile: File,
        lexerGrammarFile: File?,
        charset: Charset,
        sourceExtension: String,
        generateListener: Boolean,
        generateVisitor: Boolean,
        packageName: String?,
    ): String = buildString {
        append(parserGrammarFile.safeCanonicalPath())
        append('\u0000')
        append(lexerGrammarFile?.safeCanonicalPath().orEmpty())
        append('\u0000')
        append(charset.name())
        append('\u0000')
        append(sourceExtension)
        append('\u0000')
        append(generateListener)
        append('\u0000')
        append(generateVisitor)
        append('\u0000')
        append(packageName.orEmpty())
    }

    private fun dependencyFingerprint(
        generationIdentity: String,
        grammarFiles: Collection<File>,
    ): String? {
        val files =
            try {
                grammarFiles.map(File::getCanonicalFile).distinctBy(File::getPath).sortedBy(File::getPath)
            } catch (_: Exception) {
                return null
            }
        if (files.isEmpty() || files.any { !it.isFile }) return null

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(
                "antlr4plus-preview-dependencies-v2".toByteArray(StandardCharsets.US_ASCII),
            )
            val identityBytes = generationIdentity.toByteArray(StandardCharsets.UTF_8)
            digest.update(intBytes(identityBytes.size))
            digest.update(identityBytes)
            digest.update(intBytes(files.size))
            files.forEach { file ->
                val pathBytes = file.path.toByteArray(StandardCharsets.UTF_8)
                digest.update(intBytes(pathBytes.size))
                digest.update(pathBytes)
                digest.update(
                    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(file.length()).array(),
                )
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        } catch (_: Exception) {
            null
        }
    }

    private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(Integer.BYTES).putInt(value).array()

    private fun File.safeCanonicalPath(): String = runCatching {
        canonicalPath
    }
        .getOrElse { absoluteFile.normalize().path }
}
