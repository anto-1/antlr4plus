package com.antlr4plus.preview

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

internal fun encodeImageToBytes(
    image: BufferedImage,
    format: ImageFormat,
    jpegQuality: Int,
): ByteArray =
    ByteArrayOutputStream().use { baos ->
        when (format) {
            ImageFormat.JPEG -> {
                writeJpegIfAvailable(image, baos, jpegQuality)
            }
            else -> {
                ImageIO.write(image, format.extension, baos)
            }
        }
        baos.toByteArray()
    }

/** Writes to an ImageIO destination, returning false if no JPEG writer is available. */
internal fun writeJpegIfAvailable(
    image: BufferedImage,
    destination: Any,
    jpegQuality: Int,
): Boolean {
    val writers = ImageIO.getImageWritersByFormatName("jpg")
    if (!writers.hasNext()) return false

    val writer = writers.next()
    val param = writer.defaultWriteParam
    if (param.canWriteCompressed()) {
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = jpegQuality / 100f
    }
    ImageIO.createImageOutputStream(destination).use { output ->
        writer.output = output
        writer.write(null, IIOImage(image, null, null), param)
    }
    writer.dispose()
    return true
}
