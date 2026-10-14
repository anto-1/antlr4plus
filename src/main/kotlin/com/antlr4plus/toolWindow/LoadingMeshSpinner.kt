package com.antlr4plus.toolWindow

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import kotlin.math.cos
import kotlin.math.sin

/** Draws the animated mesh used by the loading overlay. */
internal class LoadingMeshSpinner(private val accentColor: JBColor) {
    private data class MeshVertex(
        val baseAngle: Double, // radians
        val orbitRadius: Double,
        val angularSpeed: Double, // radians per second; sign controls direction
        val radialAmp: Double,
        val radialFreq: Double, // Hz
    ) {
        fun posAt(t: Double): Pair<Double, Double> {
            val angle = baseAngle + angularSpeed * t
            val r = orbitRadius + radialAmp * sin(radialFreq * t * Math.PI * 2)
            return cos(angle) * r to sin(angle) * r
        }
    }

    private val meshVertices =
        listOf(
            MeshVertex(0.0, 36.0, 0.7, 10.0, 0.3),
            MeshVertex(0.7, 52.0, -0.5, 8.0, 0.25),
            MeshVertex(1.4, 68.0, 0.4, 12.0, 0.35),
            MeshVertex(2.1, 40.0, -0.8, 6.0, 0.4),
            MeshVertex(2.8, 60.0, 0.6, 10.0, 0.2),
            MeshVertex(3.5, 76.0, -0.35, 14.0, 0.3),
            MeshVertex(4.2, 44.0, 0.9, 8.0, 0.45),
            MeshVertex(4.9, 64.0, -0.55, 12.0, 0.28),
            MeshVertex(5.6, 32.0, 0.65, 6.0, 0.38),
        )

    // Fixed connectivity keeps the mesh coherent as vertices move.
    private val meshTriangles =
        listOf(
            Triple(0, 1, 3),
            Triple(1, 2, 4),
            Triple(2, 4, 5),
            Triple(3, 4, 6),
            Triple(4, 5, 7),
            Triple(5, 7, 8),
            Triple(0, 3, 6),
            Triple(6, 7, 4),
            Triple(7, 8, 2),
        )

    fun draw(g2: Graphics2D, centerX: Int, centerY: Int, alpha: Float) {
        val t = System.currentTimeMillis() / 1000.0 // seconds
        val globalPulse = 0.85 + 0.15 * sin(t * Math.PI * 0.8)

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val pts = meshVertices.map { v ->
            val (dx, dy) = v.posAt(t)
            Pair(centerX + dx, centerY + dy)
        }

        for ((i, tri) in meshTriangles.withIndex()) {
            val (a, b, c) = tri
            val phaseShift = i * 0.7
            val triPulse = 0.5 + 0.5 * sin(t * 1.2 + phaseShift)
            val fillAlpha = (alpha * globalPulse * triPulse * 0.12 * 255).toInt().coerceIn(0, 60)

            val poly = Polygon()
            poly.addPoint(pts[a].first.toInt(), pts[a].second.toInt())
            poly.addPoint(pts[b].first.toInt(), pts[b].second.toInt())
            poly.addPoint(pts[c].first.toInt(), pts[c].second.toInt())

            g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, fillAlpha)
            g2.fill(poly)
        }

        val drawnEdges = mutableSetOf<Long>()
        val oldStroke = g2.stroke
        g2.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for ((_, tri) in meshTriangles.withIndex()) {
            val edges = listOf(tri.first to tri.second, tri.second to tri.third, tri.first to tri.third)
            for ((ea, eb) in edges) {
                val key = minOf(ea, eb).toLong() * 100 + maxOf(ea, eb)
                if (!drawnEdges.add(key)) continue
                val edgePhase = key * 0.9
                val edgePulse = 0.6 + 0.4 * sin(t * 1.5 + edgePhase)
                val edgeAlpha = (alpha * globalPulse * edgePulse * 0.55 * 255).toInt().coerceIn(0, 180)
                g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, edgeAlpha)
                g2.drawLine(
                    pts[ea].first.toInt(),
                    pts[ea].second.toInt(),
                    pts[eb].first.toInt(),
                    pts[eb].second.toInt(),
                )
            }
        }
        g2.stroke = oldStroke

        for ((i, pt) in pts.withIndex()) {
            val vertPhase = i * 1.1
            val vertPulse = 0.7 + 0.3 * sin(t * 2.0 + vertPhase)
            val vertAlpha = (alpha * globalPulse * vertPulse * 0.9 * 255).toInt().coerceIn(0, 230)
            val dotR = (2.5 + 0.8 * sin(t * 1.8 + vertPhase)).toInt().coerceAtLeast(2)

            val glowAlpha = (vertAlpha * 0.3).toInt().coerceIn(0, 80)
            g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, glowAlpha)
            g2.fillOval(
                pt.first.toInt() - dotR - 2,
                pt.second.toInt() - dotR - 2,
                (dotR + 2) * 2,
                (dotR + 2) * 2,
            )
            g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, vertAlpha)
            g2.fillOval(pt.first.toInt() - dotR, pt.second.toInt() - dotR, dotR * 2, dotR * 2)
        }
    }
}
