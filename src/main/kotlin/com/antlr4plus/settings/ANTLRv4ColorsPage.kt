package com.antlr4plus.settings

import com.antlr4plus.dotG4Support.syntaxHighlighting.SyntaxHighlighter
import com.antlr4plus.icons.Icons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage

class ANTLRv4ColorsPage : ColorSettingsPage {
    override fun getIcon() = Icons.FILE

    override fun getHighlighter(): SyntaxHighlighter = SyntaxHighlighter()

    override fun getDemoText(): String =
        """
        /**
         * Example ANTLR4 grammar
         */
        grammar Foo;

        // Parser rules (lowercase)
        compilationUnit : statement* EOF;
        statement : IDENTIFIER '=' expression ';';
        expression : IDENTIFIER | NUMBER;

        // Lexer rules (UPPERCASE)
        IDENTIFIER : [a-zA-Z_][a-zA-Z0-9_]*;
        NUMBER : [0-9]+;
        STRING : '"' .*? '"';

        STUFF : 'stuff' -> pushMode(OTHER_MODE);
        WS : [ \t\r\n]+ -> channel(HIDDEN);

        /* Block comment example */
        mode OTHER_MODE;

        KEYWORD : 'keyword' -> popMode;
        """
            .trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = ATTRIBUTES

    override fun getColorDescriptors(): Array<out ColorDescriptor?> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "ANTLR4+"
}

private val ATTRIBUTES =
    arrayOf(
        AttributesDescriptor("Keyword", SyntaxHighlighter.KEYWORD),
        AttributesDescriptor("Lexer Rule", SyntaxHighlighter.TOKENNAME),
        AttributesDescriptor("Parser Rule", SyntaxHighlighter.RULENAME),
        AttributesDescriptor("String Literal", SyntaxHighlighter.STRING),
        AttributesDescriptor("Line Comment", SyntaxHighlighter.LINE_COMMENT),
        AttributesDescriptor("Block Comment", SyntaxHighlighter.BLOCK_COMMENT),
        AttributesDescriptor("Doc Comment", SyntaxHighlighter.DOC_COMMENT),
    )
