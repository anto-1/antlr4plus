package com.antlr4plus.animation

import java.awt.Point
import java.util.function.BiConsumer
import java.util.function.DoubleUnaryOperator
import java.util.function.Supplier
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AnimationUtils {
    var MIN_SMOOTHING_FACTOR: Double = 0.1
    var MAX_SMOOTHING_FACTOR: Double = 1.0
    var DEFAULT_SMOOTHING_FACTOR: Double = 0.3

    var TIMER_INTERVAL_MS: Int = 16

    var MIN_DURATION_MS: Double = 300.0
    var MAX_DURATION_MS: Double = 700.0
    var DURATION_DISTANCE_SCALE: Double = 0.5

    fun createDynamicAnimation(
        setPosition: BiConsumer<Int, Int>,
        startX: Int,
        startY: Int,
        targetX: Int,
        targetY: Int,
        dynamicTargetSupplier: Supplier<Point>?,
        easingFunction: EasingFunction?,
        smoothingFactor: Double,
        durationMs: Double,
        onComplete: Runnable?,
    ): Animation =
        Animation(
            setPosition,
            startX,
            startY,
            targetX,
            targetY,
            durationMs,
            dynamicTargetSupplier,
            easingFunction,
            smoothingFactor,
            onComplete,
        )

    fun createDynamicAnimation(
        setPosition: BiConsumer<Int, Int>,
        startX: Int,
        startY: Int,
        targetX: Int,
        targetY: Int,
        dynamicTargetSupplier: Supplier<Point>?,
    ): Animation {
        val deltaX = (targetX - startX).toDouble()
        val deltaY = (targetY - startY).toDouble()
        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
        val durationMs =
            max(
                MIN_DURATION_MS,
                min(MAX_DURATION_MS, MIN_DURATION_MS + distance * DURATION_DISTANCE_SCALE),
            )
        return createDynamicAnimation(
            setPosition,
            startX,
            startY,
            targetX,
            targetY,
            dynamicTargetSupplier,
            EasingFunction.EASE_IN_OUT_QUAD,
            DEFAULT_SMOOTHING_FACTOR,
            durationMs,
            null,
        )
    }

    fun createAnimation(
        setPosition: BiConsumer<Int, Int>,
        startX: Int,
        startY: Int,
        targetX: Int,
        targetY: Int,
        easingFunction: EasingFunction?,
        durationMs: Double,
        onComplete: Runnable?,
    ): Animation =
        createDynamicAnimation(
            setPosition,
            startX,
            startY,
            targetX,
            targetY,
            null,
            easingFunction,
            DEFAULT_SMOOTHING_FACTOR,
            durationMs,
            onComplete,
        )

    enum class EasingFunction(private val function: DoubleUnaryOperator) {
        EASE_IN_OUT_QUAD(
            DoubleUnaryOperator { t: Double -> if (t < 0.5) 2 * t * t else -1 + (4 - 2 * t) * t },
        );

        fun apply(t: Double): Double = function.applyAsDouble(t)
    }

    class Animation(
        private val setPosition: BiConsumer<Int, Int>,
        private val startX: Int,
        private val startY: Int,
        private var targetX: Int,
        private var targetY: Int,
        durationMs: Double,
        dynamicTargetSupplier: Supplier<Point>?,
        easingFunction: EasingFunction?,
        smoothingFactor: Double,
        onComplete: Runnable?,
    ) {
        val timer: Timer
        private val startTime: Long
        private val durationMs: Double
        private val onComplete: Runnable?
        private var smoothedTargetX: Int
        private var smoothedTargetY: Int
        private val dynamicTargetSupplier: Supplier<Point>?
        private val easingFunction: EasingFunction
        private val smoothingFactor: Double

        init {
            this.smoothedTargetX = targetX
            this.smoothedTargetY = targetY
            this.durationMs = durationMs
            this.startTime = System.currentTimeMillis()
            this.dynamicTargetSupplier = dynamicTargetSupplier
            this.easingFunction = easingFunction ?: EasingFunction.EASE_IN_OUT_QUAD
            this.smoothingFactor = max(MIN_SMOOTHING_FACTOR, min(MAX_SMOOTHING_FACTOR, smoothingFactor))
            this.onComplete = onComplete
            this.timer = Timer(TIMER_INTERVAL_MS) { update() }
        }

        fun start() {
            timer.start()
        }

        fun stop() {
            timer.stop()
        }

        private fun update() {
            val elapsedMs = (System.currentTimeMillis() - startTime).toDouble()
            val progress = elapsedMs / durationMs

            if (dynamicTargetSupplier != null) {
                val newTarget = dynamicTargetSupplier.get()
                smoothedTargetX = (smoothedTargetX + smoothingFactor * (newTarget.x - smoothedTargetX)).toInt()
                smoothedTargetY = (smoothedTargetY + smoothingFactor * (newTarget.y - smoothedTargetY)).toInt()
                this.targetX = smoothedTargetX
                this.targetY = smoothedTargetY
            }

            if (progress >= 1.0) {
                setPosition.accept(targetX, targetY)
                stop()
                onComplete?.run()
            } else {
                val easedT = easingFunction.apply(progress)
                val newX = (startX + (targetX - startX) * easedT).toInt()
                val newY = (startY + (targetY - startY) * easedT).toInt()
                setPosition.accept(newX, newY)
            }
        }
    }
}
