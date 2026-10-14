package com.antlr4plus.icons

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import javax.swing.Icon

object Icons {
    private val classLoader: ClassLoader = Icons::class.java.classLoader

    @JvmField val FILE: Icon = IconLoader.getIcon("icons/com/antlr4plus/antlr4plus_pink_16_16_dark.png", classLoader)

    @JvmField
    val CYAN_FILE: Icon = IconLoader.getIcon("icons/com/antlr4plus/antlr4plus_cyan_16_16_dark.png", classLoader)

    @JvmField val LEXER_RULE: Icon = IconLoader.getIcon("icons/com/antlr4plus/lexer-rule_16_16_dark.png", classLoader)

    @JvmField val PARSER_RULE: Icon = IconLoader.getIcon("icons/com/antlr4plus/parser-rule.png", classLoader)

    @JvmField val MODE: Icon = IconLoader.getIcon("icons/com/antlr4plus/mode.png", classLoader)

    @JvmField val DATAFLOW: Icon = IconLoader.getIcon("icons/com/antlr4plus/dataflow@20x20.svg", classLoader)

    @JvmField val LOCATE: Icon = IconLoader.getIcon("icons/locate.svg", classLoader)

    @JvmField val COPY: Icon = IconLoader.getIcon("icons/copy.svg", classLoader)

    @JvmField val JAVA_RUNTIME: Icon = IconUtil.scale(IconLoader.getIcon("icons/java.svg", classLoader), null, 1.35f)

    @JvmField val KOTLIN_RUNTIME: Icon = IconLoader.getIcon("icons/kotlin.svg", classLoader)

    @JvmField val JAVA_RUNTIME_16: Icon = IconUtil.toSize(IconLoader.getIcon("icons/java.svg", classLoader), 16, 16)

    @JvmField val KOTLIN_RUNTIME_16: Icon = IconUtil.toSize(IconLoader.getIcon("icons/kotlin.svg", classLoader), 16, 16)

    @JvmField val JAVA_MINI: Icon = IconUtil.toSize(JAVA_RUNTIME, 16, 16)

    @JvmField val KOTLIN_MINI: Icon = IconUtil.toSize(KOTLIN_RUNTIME, 16, 16)

    @JvmStatic
    val toolWindow: Icon
        get() {
            // IconLoader selects the theme-specific SVG variant.
            if (ApplicationInfo.getInstance().build.baselineVersion >= 182) {
                return IconLoader.getIcon(
                    "icons/com/antlr4plus/toolWindow_monochrome_13_13.svg",
                    classLoader,
                )
            }
            return IconLoader.getIcon(
                "icons/com/antlr4plus/antlr4plus_pink_16_16_dark.png",
                classLoader,
            )
        }
}
