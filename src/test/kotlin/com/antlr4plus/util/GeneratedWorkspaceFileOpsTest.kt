package com.antlr4plus.util

import java.nio.file.Files
import java.nio.file.LinkOption
import junit.framework.TestCase

class GeneratedWorkspaceFileOpsTest : TestCase() {

    fun `test tree deletion refuses a path beneath a symbolic link ancestor`() {
        val root = Files.createTempDirectory("antlr4plus-safe-tree-delete-test")
        try {
            val outside = Files.createDirectories(root.resolve("outside"))
            val targetTree = Files.createDirectories(outside.resolve("generated"))
            val marker = Files.writeString(targetTree.resolve("keep.txt"), "keep")
            val linkedParent = root.resolve("linked-parent")
            Files.createSymbolicLink(linkedParent, outside)

            assertFalse(GeneratedWorkspaceFileOps.deleteTree(linkedParent.resolve("generated")))
            assertTrue("Cleanup traversed a symbolic-link ancestor", Files.exists(marker))
        } finally {
            GeneratedWorkspaceFileOps.deleteTree(root)
        }
    }

    fun `test file deletion refuses a path beneath a symbolic link ancestor`() {
        val root = Files.createTempDirectory("antlr4plus-safe-file-delete-test")
        try {
            val outside = Files.createDirectories(root.resolve("outside"))
            val marker = Files.writeString(outside.resolve("keep.txt"), "keep")
            val linkedParent = root.resolve("linked-parent")
            Files.createSymbolicLink(linkedParent, outside)

            assertFalse(
                GeneratedWorkspaceFileOps.deleteFileOrLink(linkedParent.resolve("keep.txt")),
            )
            assertTrue(
                "Cleanup deleted a file through a symbolic-link ancestor",
                Files.exists(marker),
            )
        } finally {
            GeneratedWorkspaceFileOps.deleteTree(root)
        }
    }

    fun `test deleting a symbolic link removes only the link`() {
        val root = Files.createTempDirectory("antlr4plus-safe-link-delete-test")
        try {
            val outside = Files.createDirectories(root.resolve("outside"))
            val marker = Files.writeString(outside.resolve("keep.txt"), "keep")
            val link = root.resolve("linked-tree")
            Files.createSymbolicLink(link, outside)

            assertTrue(GeneratedWorkspaceFileOps.deleteTree(link))
            assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
            assertTrue("Deleting the link deleted its target", Files.exists(marker))
        } finally {
            GeneratedWorkspaceFileOps.deleteTree(root)
        }
    }
}
