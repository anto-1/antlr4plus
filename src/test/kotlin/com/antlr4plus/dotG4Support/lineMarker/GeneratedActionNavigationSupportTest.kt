package com.antlr4plus.dotG4Support.lineMarker

import com.antlr4plus.parsing.actionMap.ActionId
import com.antlr4plus.parsing.actionMap.ActionLocationMap
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class GeneratedActionNavigationSupportTest {

    @Test
    fun `finds entry when offset is inside grammar body`() {
        val map = testMap(grammarBodyRange = 8 until 13)
        val entry = GeneratedActionNavigationSupport.findEntry(map, "rule : {hello};", 10)

        assertEquals(map.entries.single(), entry)
    }

    @Test
    fun `finds entry when offset is on opening brace`() {
        val map = testMap(grammarBodyRange = 8 until 13)
        val entry = GeneratedActionNavigationSupport.findEntry(map, "rule : {hello};", 7)

        assertEquals(map.entries.single(), entry)
    }

    @Test
    fun `finds entry when offset is on closing brace`() {
        val map = testMap(grammarBodyRange = 8 until 13)
        val entry = GeneratedActionNavigationSupport.findEntry(map, "rule : {hello};", 13)

        assertEquals(map.entries.single(), entry)
    }

    @Test
    fun `finds empty entry when offset is on opening brace`() {
        val map = testMap(grammarBodyRange = 8 until 8, body = "")
        val entry = GeneratedActionNavigationSupport.findEntry(map, "rule : {};", 7)

        assertEquals(map.entries.single(), entry)
    }

    @Test
    fun `finds empty entry when offset is on closing brace`() {
        val map = testMap(grammarBodyRange = 8 until 8, body = "")
        val entry = GeneratedActionNavigationSupport.findEntry(map, "rule : {};", 8)

        assertEquals(map.entries.single(), entry)
    }

    @Test
    fun `does not resolve offsets adjacent to body without brace`() {
        val map = testMap(grammarBodyRange = 8 until 13)
        val entry = GeneratedActionNavigationSupport.findEntry(map, "rule : xhello};", 7)

        assertNull(entry)
    }

    private fun testMap(grammarBodyRange: IntRange, body: String = "hello"): ActionLocationMap =
        ActionLocationMap(
            grammarFile = File("Demo.g4"),
            generatedFile = File("DemoParser.java"),
            entries =
                listOf(
                    ActionLocationMap.Entry(
                        id = ActionId("Demo.g4", 1, grammarBodyRange.first),
                        kind = "Parser Action",
                        scope = "rule",
                        grammarBodyRange = grammarBodyRange,
                        generatedBodyRange = 20 until 30,
                        body = body,
                    ),
                ),
        )
}
