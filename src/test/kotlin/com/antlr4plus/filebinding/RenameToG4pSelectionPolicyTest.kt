package com.antlr4plus.filebinding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenameToG4pSelectionPolicyTest {
    @Test
    fun `grammar remains available for auto-binding instead of being renamed`() {
        assertFalse(RenameToG4pSelectionPolicy.isRenamable(isDirectory = false, extension = "g4"))
        assertFalse(RenameToG4pSelectionPolicy.isRenamable(isDirectory = false, extension = "G4"))
        assertTrue(RenameToG4pSelectionPolicy.isGrammar(isDirectory = false, extension = "g4"))
    }

    @Test
    fun `input files are renamable but profiles and directories are not`() {
        assertTrue(RenameToG4pSelectionPolicy.isRenamable(isDirectory = false, extension = "txt"))
        assertTrue(RenameToG4pSelectionPolicy.isRenamable(isDirectory = false, extension = null))
        assertFalse(RenameToG4pSelectionPolicy.isRenamable(isDirectory = false, extension = "g4p"))
        assertFalse(RenameToG4pSelectionPolicy.isRenamable(isDirectory = true, extension = "txt"))
    }
}
