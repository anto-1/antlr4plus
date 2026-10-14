package com.antlr4plus.compose

import com.intellij.openapi.components.Service

/** Checks Compose availability when this application service is instantiated. */
@Service(Service.Level.APP)
class ComposeInitializer {
    init {
        ComposeFallbackLoader.ensureComposeAvailable()
    }
}
