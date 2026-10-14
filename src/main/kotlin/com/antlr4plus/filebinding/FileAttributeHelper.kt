package com.antlr4plus.filebinding

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.UserDefinedFileAttributeView

/** Stores UTF-8 binding metadata through filesystem user attributes. */
object FileAttributeHelper {

    private val logger = Logger.getInstance(FileAttributeHelper::class.java)

    /** Attribute-view name, without the platform namespace prefix. */
    const val GRAMMAR_BINDING_ATTR = "antlr4plus.grammar"

    /** Writes a UTF-8 attribute; returns false when unsupported or on failure. */
    fun writeAttribute(file: VirtualFile, attributeName: String, value: String): Boolean {
        return try {
            val path = file.toNioPathOrNull() ?: return false
            writeAttribute(path, attributeName, value)
        } catch (e: Exception) {
            logger.warn("Failed to write attribute $attributeName to ${file.path}", e)
            false
        }
    }

    fun writeAttribute(path: Path, attributeName: String, value: String): Boolean {
        return try {
            val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java)
            if (view == null) {
                logger.debug("Extended attributes not supported for $path")
                return false
            }
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            view.write(attributeName, ByteBuffer.wrap(bytes))
            true
        } catch (e: Exception) {
            logger.warn("Failed to write attribute $attributeName to $path", e)
            false
        }
    }

    /** Returns the attribute value, or null when absent, unsupported, or unreadable. */
    fun readAttribute(file: VirtualFile, attributeName: String): String? {
        return try {
            val path = file.toNioPathOrNull() ?: return null
            readAttribute(path, attributeName)
        } catch (e: Exception) {
            logger.debug("Failed to read attribute $attributeName from ${file.path}", e)
            null
        }
    }

    /** Returns the attribute value, or null when absent, unsupported, or unreadable. */
    fun readAttribute(path: Path, attributeName: String): String? {
        return try {
            val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java) ?: return null
            val size = view.size(attributeName)
            val buffer = ByteBuffer.allocate(size)
            view.read(attributeName, buffer)
            buffer.flip()
            StandardCharsets.UTF_8.decode(buffer).toString()
        } catch (e: Exception) {
            null
        }
    }

    fun hasAttribute(file: VirtualFile, attributeName: String): Boolean {
        return try {
            val path = file.toNioPathOrNull() ?: return false
            hasAttribute(path, attributeName)
        } catch (e: Exception) {
            false
        }
    }

    fun hasAttribute(path: Path, attributeName: String): Boolean {
        return try {
            val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java) ?: return false
            view.list().contains(attributeName)
        } catch (e: Exception) {
            false
        }
    }

    /** Returns true if removed or absent; false when path resolution or removal fails. */
    fun removeAttribute(file: VirtualFile, attributeName: String): Boolean {
        return try {
            val path = file.toNioPathOrNull() ?: return false
            removeAttribute(path, attributeName)
        } catch (e: Exception) {
            logger.warn("Failed to remove attribute $attributeName from ${file.path}", e)
            false
        }
    }

    /** Absence of an attribute view counts as successful removal. */
    fun removeAttribute(path: Path, attributeName: String): Boolean {
        return try {
            val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java) ?: return true
            if (view.list().contains(attributeName)) {
                view.delete(attributeName)
            }
            true
        } catch (e: Exception) {
            logger.warn("Failed to remove attribute $attributeName from $path", e)
            false
        }
    }

    /** Checks for a user-attribute view without testing whether writes succeed. */
    fun supportsExtendedAttributes(file: VirtualFile): Boolean {
        return try {
            val path = file.toNioPathOrNull() ?: return false
            supportsExtendedAttributes(path)
        } catch (e: Exception) {
            false
        }
    }

    /** Checks for a user-attribute view without testing whether writes succeed. */
    fun supportsExtendedAttributes(path: Path): Boolean =
        try {
            val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java)
            view != null
        } catch (e: Exception) {
            false
        }

    fun writeGrammarBinding(g4pFile: VirtualFile, grammarPath: String): Boolean =
        writeAttribute(g4pFile, GRAMMAR_BINDING_ATTR, grammarPath)

    fun readGrammarBinding(g4pFile: VirtualFile): String? = readAttribute(g4pFile, GRAMMAR_BINDING_ATTR)

    fun hasGrammarBinding(g4pFile: VirtualFile): Boolean = hasAttribute(g4pFile, GRAMMAR_BINDING_ATTR)

    fun removeGrammarBinding(g4pFile: VirtualFile): Boolean = removeAttribute(g4pFile, GRAMMAR_BINDING_ATTR)

    private fun VirtualFile.toNioPathOrNull(): Path? {
        return try {
            if (!this.isInLocalFileSystem) {
                logger.debug("File not in local file system: $path")
                return null
            }
            this.toNioPath()
        } catch (e: Exception) {
            logger.debug("Failed to convert to NIO path: $path", e)
            null
        }
    }
}
