import java.io.File
import java.security.MessageDigest

/** Collect one isolated build without disturbing other release variants. */
object ReleaseArtifacts {
    fun collect(variantDirectory: File, destination: File, archiveName: String) {
        require(File(archiveName).name == archiveName && archiveName.endsWith(".zip")) {
            "Expected a ZIP filename: $archiveName"
        }
        val archive = variantDirectory.resolve("distributions/$archiveName")
        val bom = variantDirectory.resolve("reports/sbom.json")
        require(archive.isFile && archive.length() > 0) { "Missing plugin archive: $archive" }
        require(bom.isFile && bom.length() > 0) { "Missing SBOM: $bom" }
        destination.mkdirs()
        val copiedArchive = archive.copyTo(destination.resolve(archiveName), overwrite = true)
        val copiedBom =
            bom.copyTo(
                destination.resolve(archiveName.removeSuffix(".zip") + ".sbom.json"),
                overwrite = true,
            )
        listOf(archive to copiedArchive, bom to copiedBom).forEach { (source, artifact) ->
            val sourceHash = sha256(source)
            require(sha256(artifact) == sourceHash) {
                "Copied release artifact differs from its source: $artifact"
            }
            val checksum = destination.resolve("${artifact.name}.sha256")
            checksum.writeText("$sourceHash  ${artifact.name}\n")
            verifyChecksum(checksum)
        }
    }

    fun verifyChecksums(directory: File) {
        val checksums =
            directory
                .listFiles { file -> file.isFile && file.name.endsWith(".sha256") }
                ?.sortedBy(File::getName)
                .orEmpty()
        require(checksums.isNotEmpty()) { "No SHA-256 files found in $directory" }
        checksums.forEach(::verifyChecksum)
    }

    private fun verifyChecksum(checksum: File) {
        val match =
            Regex("^([0-9a-f]{64})  ([^/\\\\]+)\\n$").matchEntire(checksum.readText())
                ?: throw IllegalArgumentException("Invalid checksum file: $checksum")
        val artifact = checksum.parentFile.resolve(match.groupValues[2])
        require(artifact.isFile) { "Checksum target is missing: $artifact" }
        require(sha256(artifact) == match.groupValues[1]) { "Checksum mismatch: $artifact" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
