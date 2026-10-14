package com.antlr4plus.onboarding

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.Logger

/** Persists onboarding completion across IDE sessions. */
object OnboardingManager {

    private val LOG = Logger.getInstance(OnboardingManager::class.java)

    private const val ONBOARDING_COMPLETED_KEY = "antlr4plus.onboarding.completed"
    private const val ONBOARDING_VERSION_KEY = "antlr4plus.onboarding.version"

    /** Increment to show onboarding again after a significant change. */
    private const val CURRENT_ONBOARDING_VERSION = 1

    fun hasCompletedOnboarding(): Boolean {
        val properties = PropertiesComponent.getInstance()
        val completed = properties.getBoolean(ONBOARDING_COMPLETED_KEY, false)
        val version = properties.getInt(ONBOARDING_VERSION_KEY, 0)

        if (completed && version < CURRENT_ONBOARDING_VERSION) {
            LOG.info(
                "Onboarding version changed ($version -> $CURRENT_ONBOARDING_VERSION), will re-show onboarding",
            )
            return false
        }

        return completed
    }

    fun markOnboardingComplete() {
        val properties = PropertiesComponent.getInstance()
        properties.setValue(ONBOARDING_COMPLETED_KEY, true)
        properties.setValue(ONBOARDING_VERSION_KEY, CURRENT_ONBOARDING_VERSION, 0)
        LOG.info("Onboarding marked as complete (version $CURRENT_ONBOARDING_VERSION)")
    }
}
