package com.antlr4plus.toolWindow

import java.awt.Component
import javax.swing.JPanel

internal class LoadingOverlayPanel : JPanel() {
    private val loadingOverlay = LoadingOverlay()

    init {
        layout = null
    }

    override fun doLayout() {
        super.doLayout()
        loadingOverlay.setBounds(0, 0, width, height)
    }

    override fun addImpl(comp: Component, constraints: Any?, index: Int) {
        super.addImpl(comp, constraints, index)

        if (comp != loadingOverlay) {
            if (loadingOverlay.parent == null) {
                super.addImpl(loadingOverlay, null, 0)
            }
            setComponentZOrder(loadingOverlay, 0)
        }
    }

    fun showLoadingStatus(message: String) {
        if (loadingOverlay.parent == null) {
            super.addImpl(loadingOverlay, null, 0)
        }
        setComponentZOrder(loadingOverlay, 0)
        loadingOverlay.setBounds(0, 0, width, height)
        loadingOverlay.showStatus(message)
    }

    fun hideLoadingStatus(): Unit = loadingOverlay.hideOverlay()

    fun setLoadingCancellable(cancellable: Boolean): Unit = loadingOverlay.setCancellable(cancellable)

    fun triggerSuccessAnimation() {
        if (loadingOverlay.isVisible) {
            loadingOverlay.hideImmediately()
        }
    }

    fun setOnCancelCallback(callback: () -> Unit): Unit = loadingOverlay.setOnCancelCallback(callback)

    fun showActionButton(text: String, callback: () -> Unit): Unit = loadingOverlay.showActionButton(text, callback)

    fun hideActionButton(): Unit = loadingOverlay.hideActionButton()

    fun addExcludedComponent(component: Component): Unit = loadingOverlay.addExcludedComponent(component)
}
