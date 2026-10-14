package com.antlr4plus.onboarding

import com.intellij.ui.JBColor
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.Timer

/**
 * Reveals text progressively while reserving space for the complete text. Inspired by DuckDuckGo's onboarding dialogs
 * and the TypedKt library.
 */
class TypewriterLabel(
    private val fullText: String = "",
    private val charDelayMs: Int = 35,
    private val initialDelayMs: Int = 300,
) : JLabel() {

    private var currentIndex = 0
    private var timer: Timer? = null
    private var onComplete: (() -> Unit)? = null
    private var isAnimating = false
    private var calculatedPreferredSize: Dimension? = null

    init {
        text = ""
        font = font.deriveFont(font.size * 1.30f)
        foreground = JBColor.foreground()
    }

    override fun getPreferredSize(): Dimension {
        calculatedPreferredSize?.let {
            return it
        }

        val oldText = text
        super.setText(fullText)
        val size = super.getPreferredSize()
        super.setText(oldText)

        calculatedPreferredSize = size
        return size
    }

    fun startAnimation(onComplete: (() -> Unit)? = null) {
        if (isAnimating) return

        this.onComplete = onComplete
        currentIndex = 0
        text = ""
        isAnimating = true

        Timer(initialDelayMs) {
                startTyping()
            }
            .apply {
                isRepeats = false
                start()
            }
    }

    private fun startTyping() {
        timer?.stop()
        timer =
            Timer(charDelayMs) {
                    if (currentIndex < fullText.length) {
                        currentIndex++
                        text = fullText.substring(0, currentIndex)
                    } else {
                        stopAnimation()
                        onComplete?.invoke()
                    }
                }
                .apply {
                    isRepeats = true
                    start()
                }
    }

    /** Shows the complete text and invokes the completion callback. */
    fun skipAnimation() {
        timer?.stop()
        timer = null
        text = fullText
        currentIndex = fullText.length
        isAnimating = false
        onComplete?.invoke()
    }

    fun stopAnimation() {
        timer?.stop()
        timer = null
        isAnimating = false
    }

    fun reset() {
        stopAnimation()
        currentIndex = 0
        text = ""
    }

    fun isAnimating(): Boolean = isAnimating

    fun dispose() {
        timer?.stop()
        timer = null
        onComplete = null
    }

    companion object {
        fun create(
            text: String,
            charDelayMs: Int = 35,
            initialDelayMs: Int = 300,
        ): TypewriterLabel = TypewriterLabel(text, charDelayMs, initialDelayMs)
    }
}

class TypewriterSequence(
    private val labels: List<TypewriterLabel>,
    private val delayBetweenMs: Int = 500,
) {
    private var currentLabelIndex = 0
    private var onSequenceComplete: (() -> Unit)? = null

    fun startSequence(onComplete: (() -> Unit)? = null) {
        this.onSequenceComplete = onComplete
        currentLabelIndex = 0
        animateNext()
    }

    private fun animateNext() {
        if (currentLabelIndex >= labels.size) {
            onSequenceComplete?.invoke()
            return
        }

        val label = labels[currentLabelIndex]
        label.startAnimation {
            currentLabelIndex++
            if (currentLabelIndex < labels.size) {
                Timer(delayBetweenMs) {
                        animateNext()
                    }
                    .apply {
                        isRepeats = false
                        start()
                    }
            } else {
                onSequenceComplete?.invoke()
            }
        }
    }

    fun skipAll() {
        labels.forEach { it.skipAnimation() }
        onSequenceComplete?.invoke()
    }

    fun dispose() {
        labels.forEach { it.dispose() }
    }
}
