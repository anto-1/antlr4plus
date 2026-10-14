package com.antlr4plus.preview

import java.io.File

/** Export options, optionally including image bytes already encoded for these settings. */
data class ExportSettings(
    val file: File,
    val format: ImageFormat,
    val scaleFactor: Double,
    val jpegQuality: Int,
    val precomputedData: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ExportSettings
        return file == other.file &&
            format == other.format &&
            scaleFactor == other.scaleFactor &&
            jpegQuality == other.jpegQuality
    }

    override fun hashCode(): Int = file.hashCode() * 31 + format.hashCode()
}

enum class ImageFormat(
    val extension: String,
    val displayName: String,
    val isVector: Boolean,
    val supportsTransparency: Boolean,
) {
    SVG("svg", "SVG (Vector)", true, true),
    PNG("png", "PNG", false, true),
    JPEG("jpg", "JPEG", false, false),
    TIFF("tiff", "TIFF", false, true);

    override fun toString(): String = displayName
}
