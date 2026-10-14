plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

fun variantForIdeaVersion(version: String?): String? {
    val match = Regex("""(\d{4})\.(\d+)""").find(version.orEmpty()) ?: return null
    val (year, release) = match.destructured
    val versionCode = year.toInt() * 10 + release.toInt()
    return when {
        versionCode >= 20253 -> "newer"
        versionCode >= 20243 -> "modern"
        else -> "legacy"
    }
}

val taskNames = gradle.startParameter.taskNames.joinToString(" ").lowercase()
val taskVariant =
    when {
        listOf("newer", "253", "2025_3", "261", "2026_1").any(taskNames::contains) -> "newer"
        listOf("modern", "243", "251", "252", "2024_3", "2025_1", "2025_2").any(taskNames::contains) -> "modern"
        listOf("legacy", "232", "233", "241", "242", "2023_2", "2023_3", "2024_1", "2024_2").any(taskNames::contains) ->
            "legacy"
        else -> null
    }
val ideVariant =
    if (providers.systemProperty("idea.active").orNull == "true") {
        variantForIdeaVersion(providers.systemProperty("idea.version").orNull)
    } else {
        null
    }
val buildVariant =
    providers.gradleProperty("buildVariant").orNull?.takeUnless(String::isBlank)
        ?: taskVariant
        ?: ideVariant
        ?: "legacy"

require(buildVariant in setOf("legacy", "modern", "newer")) {
    "Invalid buildVariant: $buildVariant (expected 'legacy', 'modern', or 'newer')"
}

gradle.extensions.extraProperties["antlr4plus.buildVariant"] = buildVariant

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs-$buildVariant.versions.toml"))
        }
    }
}

rootProject.name = "antlr4plus"
