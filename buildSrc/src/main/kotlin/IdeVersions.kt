/** IntelliJ IDEA versions used to generate launch tasks. */
data class IdeVersion(
    val shortCode: String,
    val fullVersion: String,
    val taskSuffix: String,
    val needsComposeFallback: Boolean = true, // IDEA 253+ supplies Compose.
)

/** Launch configuration for a non-IDEA product. */
data class NonIdeaIdeConfig(
    val taskName: String,
    val platformType: String,
    val version: String,
)

/** IDEA versions available for local compatibility testing. */
val ideaVersions =
    listOf(
        IdeVersion("232", "2023.2", "2023_2"),
        IdeVersion("233", "2023.3", "2023_3"),
        IdeVersion("241", "2024.1", "2024_1"),
        IdeVersion("242", "2024.2", "2024_2"),
        IdeVersion("243", "2024.3", "2024_3"),
        IdeVersion("251", "2025.1", "2025_1"),
        IdeVersion("252", "2025.2", "2025_2"),
        IdeVersion("253", "2025.3", "2025_3", needsComposeFallback = false),
        IdeVersion("261", "2026.1", "2026_1", needsComposeFallback = false),
    )

val nonIdeaIdeConfigs =
    listOf(
        NonIdeaIdeConfig("runCLion_242", "CLion", "2024.2"),
        NonIdeaIdeConfig("runCLion_251", "CLion", "2025.1"),
        NonIdeaIdeConfig("runCLion_253", "CLion", "2025.3"),
        NonIdeaIdeConfig("runRustRover_242", "RustRover", "2024.2"),
        NonIdeaIdeConfig("runRustRover_251", "RustRover", "2025.1"),
        NonIdeaIdeConfig("runRustRover_253", "RustRover", "2025.3"),
    )

/** The IDE supplies Kotlin and coroutines for fallback distributions too. */
val composeFallbackExcludes = listOf("**/kotlinx-*.jar", "**/kotlin-stdlib*.jar")
