/** Compiler, runtime, and IDE bounds for each distribution. */
data class VariantConfig(
    val name: String,
    val releaseVersionCode: Int,
    val kotlinVersion: String,
    val composeVersion: String,
    val sinceBuild: String,
    val untilBuild: String,
    val jewelVersion: String,
    val needsComposeFallback: Boolean,
) {
    fun releaseVersion(baseVersion: String): String = "$baseVersion.$releaseVersionCode"
}

object BuildVariants {

    val Legacy =
        VariantConfig(
            name = "legacy",
            releaseVersionCode = 1,
            kotlinVersion = "1.9.24",
            composeVersion = "1.6.11",
            sinceBuild = "232",
            untilBuild = "242.*",
            jewelVersion = "0.15.2",
            needsComposeFallback = true,
        )

    val Modern =
        VariantConfig(
            name = "modern",
            releaseVersionCode = 2,
            kotlinVersion = "2.1.0",
            composeVersion = "1.8.0",
            sinceBuild = "243",
            untilBuild = "252.*",
            jewelVersion = "0.27.0",
            needsComposeFallback = true,
        )

    val Newer =
        VariantConfig(
            name = "newer",
            releaseVersionCode = 3,
            kotlinVersion = "2.1.20",
            composeVersion = "1.8.0", // Also used by explicit non-IDEA fallback builds.
            sinceBuild = "253",
            untilBuild = "", // No upper IDE bound.
            jewelVersion = "0.27.0",
            needsComposeFallback = false, // Require the IDEA Compose module unless a fallback build is requested.
        )

    val all = listOf(Legacy, Modern, Newer)

    fun byName(name: String): VariantConfig =
        when (name) {
            Legacy.name -> Legacy
            Modern.name -> Modern
            Newer.name -> Newer
            else ->
                throw IllegalArgumentException(
                    "Unknown variant: $name (expected 'legacy', 'modern', or 'newer')",
                )
        }
}
