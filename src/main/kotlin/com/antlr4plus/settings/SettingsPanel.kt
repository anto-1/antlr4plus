package com.antlr4plus.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.antlr4plus.codeGen.GenAntlrCode
import com.antlr4plus.color.darken
import com.antlr4plus.color.rememberIsLightTheme
import com.antlr4plus.color.rememberThemeBackgroundColor
import com.antlr4plus.color.rememberThemeTextColor
import com.antlr4plus.compat.compatClickable
import com.antlr4plus.compose.SimpleCheckbox
import com.antlr4plus.parsing.CaseChangingStrategy
import com.antlr4plus.parsing.KotlinRuntimeCompiler
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.state.PreviewState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import java.nio.charset.Charset
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Text

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsPanel(project: Project) {
    val backgroundColor: Color = rememberThemeBackgroundColor()
    val surfaceColor = remember(backgroundColor) { backgroundColor.darken(.08f) }

    val borderColor = remember(backgroundColor) { backgroundColor.darken(.20f) }
    val isLightTheme = rememberIsLightTheme()
    val themeTextColor = rememberThemeTextColor()
    val textPrimary = if (!isLightTheme) themeTextColor.copy(alpha = 0.8f) else themeTextColor
    val textSecondary = remember(textPrimary) { textPrimary.copy(alpha = 0.6f) }
    val textUserValue = remember { Color(0xFF548AF7) }
    val roundCornerShape = RoundedCornerShape(SettingsTheme.CORNER_RADIUS)

    val previewService = project.getService(PreviewService::class.java)
    var currentG4pFile by remember { mutableStateOf(previewService.state.g4pInputFile) }
    var highlightRuntimeSelector by remember { mutableStateOf(false) }
    var revertedRuntime by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val connection = project.messageBus.connect()

        connection.subscribe(
            PreviewService.PARSING_COMPLETION_TOPIC,
            object : PreviewService.ParsingCompletionListener {
                override fun onParsingCompleted(previewState: PreviewState) {
                    currentG4pFile = previewState.g4pInputFile
                }
            },
        )

        connection.subscribe(
            PreviewService.FILE_SWITCHED_TOPIC,
            object : PreviewService.FileSwitchedListener {
                override fun onFileSwitched(previewState: PreviewState) {
                    currentG4pFile = previewState.g4pInputFile
                }
            },
        )

        connection.subscribe(
            PreviewService.PLUGIN_TOGGLE_TOPIC,
            object : PreviewService.PluginToggleListener {
                override fun onPluginToggled(enabled: Boolean) {
                    if (!enabled) {
                        currentG4pFile = null
                    }
                }
            },
        )

        connection.subscribe(
            PreviewService.RUNTIME_HIGHLIGHT_TOPIC,
            object : PreviewService.RuntimeHighlightListener {
                override fun onHighlightRuntime() {
                    highlightRuntimeSelector = true
                }
            },
        )

        connection.subscribe(
            PreviewService.RUNTIME_REVERT_TOPIC,
            object : PreviewService.RuntimeRevertListener {
                override fun onRuntimeReverted(runtime: String) {
                    revertedRuntime = runtime
                }
            },
        )

        onDispose { connection.disconnect() }
    }

    val g4pFile = currentG4pFile
    val liveSettings = previewService.state.liveSettings
    val rootFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    var selectedRuntime by
        remember(liveSettings) {
            mutableStateOf(liveSettings?.previewRuntime ?: "Java")
        }

    LaunchedEffect(revertedRuntime) {
        revertedRuntime?.let { runtime ->
            selectedRuntime = runtime
            revertedRuntime = null
        }
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .background(backgroundColor)
                .padding(start = SettingsTheme.HORIZONTAL_PADDING)
                .verticalScroll(rememberScrollState())
                .focusable()
                .focusRequester(rootFocusRequester)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    focusManager.clearFocus()
                    rootFocusRequester.requestFocus()
                }
                .onKeyEvent { event ->
                    event.key == Key.Escape
                },
    ) {
        if (g4pFile != null && liveSettings != null) {
            Spacer(modifier = Modifier.height(SettingsTheme.ITEM_SPACING))

            HeaderSection(g4pFile.name, g4pFile.path, backgroundColor, textSecondary, textPrimary)

            Spacer(modifier = Modifier.height(SettingsTheme.SECTION_SPACING))

            val languageExpanded = remember { mutableStateOf(false) }
            SettingItem(
                label = "Gen Target Language",
                description =
                    if (liveSettings.language == "Rust") {
                        "Experimental source generation for the external antlr4rust 0.6 runtime; the runtime is not bundled. Check /{lang} to output to {outputDir}/{language}/."
                    } else {
                        "Select target language. Check /{lang} to output to {outputDir}/{language}/, or uncheck to output directly to {outputDir}/"
                    },
                roundCornerShape = roundCornerShape,
                activate = {
                    coroutineScope.launch {
                        focusManager.clearFocus()
                        rootFocusRequester.requestFocus()
                        languageExpanded.value = true
                    }
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        DropdownField(
                            value = liveSettings.language ?: "Java (default)",
                            items = GenAntlrCode.SUPPORTED_TARGET_LANGUAGES,
                            onItemSelected = {
                                liveSettings.language = it
                                liveSettings.saveToPersistence()
                            },
                            expandedState = languageExpanded,
                            surfaceColor = surfaceColor,
                            borderColor = borderColor,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            roundCornerShape = roundCornerShape,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.clip(roundCornerShape)
                                .compatClickable {
                                    liveSettings.createLanguageSubdir = !liveSettings.createLanguageSubdir
                                    liveSettings.saveToPersistence()
                                }
                                .padding(horizontal = 4.dp),
                    ) {
                        SimpleCheckbox(
                            checked = liveSettings.createLanguageSubdir,
                            onCheckedChange = {
                                liveSettings.createLanguageSubdir = it
                                liveSettings.saveToPersistence()
                            },
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "/{lang}",
                            color = textSecondary,
                            fontSize = SettingsTheme.FONT_SIZE_TOOLTIP,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier =
                            Modifier.height(28.dp)
                                .clip(roundCornerShape)
                                .background(surfaceColor)
                                .border(SettingsTheme.BORDER_WIDTH, borderColor, roundCornerShape)
                                .compatClickable {
                                    previewService.generateCodeForSelectedLanguage()
                                }
                                .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Generate",
                            color = textPrimary,
                            fontSize = SettingsTheme.FONT_SIZE_VALUE,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier =
                            Modifier.height(28.dp)
                                .clip(roundCornerShape)
                                .background(surfaceColor)
                                .border(SettingsTheme.BORDER_WIDTH, borderColor, roundCornerShape)
                                .compatClickable { previewService.deleteOutputDirectory() }
                                .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Del",
                            color = textPrimary,
                            fontSize = SettingsTheme.FONT_SIZE_VALUE,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(SettingsTheme.ITEM_SPACING))

            val outputFocus = remember { FocusRequester() }
            // Match focus-loss normalization so the default path is not treated as an edit.
            var lastSavedOutputDir by remember {
                val initial = liveSettings.outputDir?.trim() ?: ""
                mutableStateOf(if (initial.isEmpty() || initial == "gen") null else initial)
            }
            SettingItem(
                label = "Output Directory",
                description =
                    "gen (default) relative to .g4p directory or provide an absolute path. Preview regeneration replaces files in {outputDir}/preview/{runtime}/. User-generated code goes to {outputDir}/{language}/ if /{lang} is checked, or directly to {outputDir}/ if unchecked.",
                roundCornerShape = roundCornerShape,
                activate = { outputFocus.requestFocus() },
            ) {
                PathField(
                    value = liveSettings.outputDir ?: "",
                    onValueChange = { liveSettings.outputDir = it },
                    onBrowse = {
                        val descriptor =
                            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select output dir")
                        FileChooser.chooseFile(descriptor, project, null) { file ->
                            if (file != null) {
                                liveSettings.outputDir = file.path
                                liveSettings.saveToPersistence()
                                lastSavedOutputDir = file.path
                                previewService.launchProcessing(g4pFile)
                                previewService.showNotification(
                                    "Output Directory Changed",
                                    "Preview will regenerate to: ${file.path}",
                                )
                            }
                        }
                    },
                    placeholder = "gen (default)",
                    focusRequester = outputFocus,
                    onFocusLost = {
                        val trimmed = liveSettings.outputDir?.trim() ?: ""
                        val newValue = if (trimmed.isEmpty() || trimmed == "gen") null else trimmed
                        if (newValue != lastSavedOutputDir) {
                            liveSettings.outputDir = newValue
                            liveSettings.saveToPersistence()
                            lastSavedOutputDir = newValue
                            previewService.launchProcessing(g4pFile)
                            previewService.showNotification(
                                "Output Directory Changed",
                                "Preview will regenerate to: ${newValue ?: "gen (default)"}",
                            )
                        }
                    },
                    surfaceColor = surfaceColor,
                    borderColor = borderColor,
                    textSecondary = textSecondary,
                    textUserValue = textUserValue,
                    roundCornerShape = roundCornerShape,
                )
            }

            Spacer(modifier = Modifier.height(SettingsTheme.ITEM_SPACING))

            val packageFocus = remember { FocusRequester() }
            SettingItem(
                label = "Package/Namespace",
                description =
                    "This option enables the specification of a package or namespace for the generated files. When declared, it establishes the corresponding directory structure within the designated output directory and prepends the package declaration to the parser and lexer files, such as \"package com.example.parser\".",
                roundCornerShape = roundCornerShape,
                activate = { packageFocus.requestFocus() },
            ) {
                TextField(
                    value = liveSettings.packageName ?: "",
                    onValueChange = { liveSettings.packageName = it },
                    placeholder = "e.g., com.example.parser",
                    focusRequester = packageFocus,
                    onFocusLost = {
                        liveSettings.saveToPersistence()
                        previewService.launchProcessing(g4pFile)
                    },
                    surfaceColor = surfaceColor,
                    borderColor = borderColor,
                    textSecondary = textSecondary,
                    textUserValue = textUserValue,
                    roundCornerShape = roundCornerShape,
                )
            }

            Spacer(modifier = Modifier.height(SettingsTheme.ITEM_SPACING))

            val libFocus = remember { FocusRequester() }
            SettingItem(
                label = "Library Directory",
                description =
                    "An alternative directory for locating grammars and tokens files. By default, ANTLR4 searches the current directory of the .g4p file for these resources, such as tokens files generated by separate lexer grammars or imported grammars referenced via the import statement or tokenVocab option. Specifying a path here overrides this behavior, directing ANTLR4 to lookup the resources in the specified location instead.",
                roundCornerShape = roundCornerShape,
                activate = { libFocus.requestFocus() },
            ) {
                PathField(
                    value = liveSettings.libDir ?: "",
                    onValueChange = { liveSettings.libDir = it },
                    onBrowse = {
                        val descriptor =
                            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select lib dir")
                        FileChooser.chooseFile(descriptor, project, null) { file ->
                            if (file != null) {
                                liveSettings.libDir = file.path
                                liveSettings.saveToPersistence()
                                previewService.launchProcessing(g4pFile)
                            }
                        }
                    },
                    placeholder = "current active .g4p directory (default)",
                    focusRequester = libFocus,
                    onFocusLost = {
                        liveSettings.saveToPersistence()
                        previewService.launchProcessing(g4pFile)
                    },
                    surfaceColor = surfaceColor,
                    borderColor = borderColor,
                    textSecondary = textSecondary,
                    textUserValue = textUserValue,
                    roundCornerShape = roundCornerShape,
                )
            }

            Spacer(modifier = Modifier.height(SettingsTheme.ITEM_SPACING))

            val encodingExpanded = remember { mutableStateOf(false) }
            val encodingSearchFocus = remember { FocusRequester() }
            SettingItem(
                label = "File Encoding",
                description =
                    "Specifies the encoding for loading grammar files if it differs from the default UTF-8 (which supports ASCII for single-byte characters). This ensures accurate interpretation of non-standard encodings in various locales. It affects only grammar files, not input to generated parsers.",
                roundCornerShape = roundCornerShape,
                activate = {
                    coroutineScope.launch {
                        focusManager.clearFocus()
                        rootFocusRequester.requestFocus()
                        encodingExpanded.value = true
                    }
                },
            ) {
                SearchableDropdownField(
                    value = liveSettings.encoding?.ifEmpty { "UTF-8 (default)" } ?: "UTF-8 (default)",
                    items = Charset.availableCharsets().keys.sorted(),
                    onItemSelected = {
                        liveSettings.encoding = it
                        liveSettings.saveToPersistence()
                        previewService.launchProcessing(g4pFile)
                    },
                    expandedState = encodingExpanded,
                    searchFocusRequester = encodingSearchFocus,
                    surfaceColor = surfaceColor,
                    borderColor = borderColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    roundCornerShape = roundCornerShape,
                )
            }

            Spacer(modifier = Modifier.height(SettingsTheme.ITEM_SPACING))

            val caseExpanded = remember { mutableStateOf(false) }
            SettingItem(
                label = "Case Transformation",
                description =
                    "This setting normalizes input case before lexer processing by transforming the CharStream derived from the .g4p file content to uppercase or lowercase, without altering the .g4p file content itself. It affects only the preview's input stream, leaving grammar rules, generated parser code, and external executions unchanged.",
                roundCornerShape = roundCornerShape,
                activate = {
                    coroutineScope.launch {
                        focusManager.clearFocus()
                        rootFocusRequester.requestFocus()
                        caseExpanded.value = true
                    }
                },
            ) {
                val displayMap = CaseChangingStrategy.entries.associate { it.name to it.toString() }

                DropdownField(
                    value = displayMap[liveSettings.caseChangingStrategy.name] ?: "Leave as-is",
                    items = CaseChangingStrategy.entries.map { it.name },
                    onItemSelected = { selectedName ->
                        liveSettings.caseChangingStrategy = CaseChangingStrategy.valueOf(selectedName)
                        liveSettings.saveToPersistence()
                        previewService.launchProcessing(g4pFile)
                    },
                    expandedState = caseExpanded,
                    surfaceColor = surfaceColor,
                    borderColor = borderColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    roundCornerShape = roundCornerShape,
                )
            }

            Spacer(modifier = Modifier.height(SettingsTheme.SECTION_SPACING))

            CheckboxField(
                checked = liveSettings.generateListener,
                onCheckedChange = {
                    liveSettings.generateListener = it
                    liveSettings.saveToPersistence()
                    previewService.launchProcessing(g4pFile)
                },
                label = "Generate parse tree listener",
                description = "Generate parse tree listener classes",
                textPrimary = textPrimary,
                roundCornerShape = roundCornerShape,
            )

            Spacer(modifier = Modifier.height(SettingsTheme.ITEM_SPACING))

            CheckboxField(
                checked = liveSettings.generateVisitor,
                onCheckedChange = {
                    liveSettings.generateVisitor = it
                    liveSettings.saveToPersistence()
                    previewService.launchProcessing(g4pFile)
                },
                label = "Generate parse tree visitor",
                description = "Generate parse tree visitor classes",
                textPrimary = textPrimary,
                roundCornerShape = roundCornerShape,
            )
        }

        Spacer(modifier = Modifier.height(SettingsTheme.SECTION_SPACING * 2))

        SectionHeader("Lexer Pane (Global)", textPrimary)

        Spacer(modifier = Modifier.height(SettingsTheme.SECTION_SPACING))

        val projectSettings = project.getService(ProjectSettingsService::class.java)
        var colorizeTokens by remember { mutableStateOf(projectSettings.state.colorizeTokens) }
        var cosmeticTokenHighlight by remember {
            mutableStateOf(projectSettings.state.cosmeticTokenHighlight)
        }
        var gutterTokenIndicator by remember {
            mutableStateOf(projectSettings.state.gutterTokenIndicator)
        }

        CheckboxField(
            checked = colorizeTokens,
            onCheckedChange = { enabled ->
                colorizeTokens = enabled
                projectSettings.state.colorizeTokens = enabled
                val panel = previewService.tokenTimelinePanel
                if (panel != null) {
                    if (enabled) panel.enableHighlighting() else panel.disableHighlighting()
                }
            },
            label = "Colorize tokens in editor",
            description = "Highlight each token type with a distinct color in the .g4p editor",
            textPrimary = textPrimary,
            roundCornerShape = roundCornerShape,
        )

        Spacer(modifier = Modifier.height(SettingsTheme.ITEM_SPACING))

        CheckboxField(
            checked = cosmeticTokenHighlight,
            onCheckedChange = { enabled ->
                cosmeticTokenHighlight = enabled
                projectSettings.state.cosmeticTokenHighlight = enabled
                if (!enabled) {
                    val currentG4p = previewService.state.g4pInputFile ?: return@CheckboxField
                    val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    for (fe in fem.getEditors(currentG4p)) {
                        if (fe is com.intellij.openapi.fileEditor.TextEditor) {
                            fe.editor.markupModel.allHighlighters
                                .filter {
                                    it.getUserData(PreviewService.CARET_TOKEN_HIGHLIGHT_KEY) == true
                                }
                                .forEach { fe.editor.markupModel.removeHighlighter(it) }
                        }
                    }
                }
            },
            label = "Token highlight on caret",
            description = "Show a cosmetic highlight on the token under the caret in the .g4p editor",
            textPrimary = textPrimary,
            roundCornerShape = roundCornerShape,
        )

        Spacer(modifier = Modifier.height(SettingsTheme.ITEM_SPACING))

        CheckboxField(
            checked = gutterTokenIndicator,
            onCheckedChange = { enabled ->
                gutterTokenIndicator = enabled
                projectSettings.state.gutterTokenIndicator = enabled
                val currentG4p = previewService.state.g4pInputFile ?: return@CheckboxField
                val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                for (fe in fem.getEditors(currentG4p)) {
                    if (fe is com.intellij.openapi.fileEditor.TextEditor) {
                        fe.editor.markupModel.allHighlighters
                            .filter {
                                it.getUserData(PreviewService.CARET_TOKEN_HIGHLIGHT_KEY) == true
                            }
                            .forEach { fe.editor.markupModel.removeHighlighter(it) }
                    }
                }
            },
            label = "Gutter token indicator",
            description = "Show a colored bar in the gutter on the line of the token under the caret",
            textPrimary = textPrimary,
            roundCornerShape = roundCornerShape,
        )

        if (g4pFile != null && liveSettings != null) {
            Spacer(modifier = Modifier.height(SettingsTheme.SECTION_SPACING))

            SectionHeader("Preview Runtime", textPrimary)

            Spacer(modifier = Modifier.height(SettingsTheme.SECTION_SPACING))

            RuntimeCompilerSelector(
                selectedRuntime = selectedRuntime,
                onRuntimeSelected = { runtime ->
                    selectedRuntime = runtime
                    liveSettings.previewRuntime = runtime
                    liveSettings.saveToPersistence()
                    // Warm the Kotlin compiler before the first preview compilation.
                    if (runtime == "Kotlin" && !KotlinRuntimeCompiler.isWarmedUp()) {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            if (KotlinRuntimeCompiler.isAvailable() && !KotlinRuntimeCompiler.isWarmedUp()) {
                                KotlinRuntimeCompiler.warmup()
                            }
                        }
                    }
                    previewService.tokenTimelinePanel?.clearHighlightingState()
                    previewService.launchProcessingAfterRuntimeChange(g4pFile, runtime)
                },
                textSecondary = textSecondary,
                textPrimary = textPrimary,
                roundCornerShape = roundCornerShape,
                highlightAnimation = highlightRuntimeSelector,
                onHighlightComplete = { highlightRuntimeSelector = false },
            )

            Spacer(modifier = Modifier.height(SettingsTheme.SECTION_SPACING))
        }

        Spacer(modifier = Modifier.height(SettingsTheme.SECTION_SPACING))
    }
}
