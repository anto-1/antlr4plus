package com.antlr4plus.util

import java.awt.*
import java.awt.font.FontRenderContext
import java.awt.font.GlyphVector
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.NoninvertibleTransformException
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.BufferedImageOp
import java.awt.image.ImageObserver
import java.awt.image.RenderedImage
import java.awt.image.renderable.RenderableImage
import java.text.AttributedCharacterIterator

/** Minimal SVG exporter for shapes and text, using SVG transform attributes. */
class SimpleSVGGraphics2D(
    private val width: Double,
    private val height: Double,
    private val sb: StringBuilder = StringBuilder(),
    private val clipPaths: MutableList<String> = mutableListOf(),
    private val clipPathPrefix: String = "_${System.identityHashCode(StringBuilder())}clip-",
) : Graphics2D() {
    private var currentColor: Color = Color.BLACK
    private var currentFont: Font = Font("SansSerif", Font.PLAIN, 12)
    private var currentStroke: Stroke = BasicStroke(1f)
    private var clip: Shape? = null
    private var transform: AffineTransform = AffineTransform()

    private val line = Line2D.Double()
    private val rect = Rectangle2D.Double()
    private val roundRect = RoundRectangle2D.Double()
    private val oval = Ellipse2D.Double()
    private val arc = Arc2D.Double()

    val svgDocument: String
        get() = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("\n")
            append("""<svg xmlns="http://www.w3.org/2000/svg" """)
            append("""width="$width" height="$height" """)
            append("""viewBox="0 0 $width $height">""")
            append("\n")
            if (clipPaths.isNotEmpty()) {
                append("<defs>\n")
                clipPaths.forEachIndexed { index, pathData ->
                    append(
                        """<clipPath id="$clipPathPrefix$index"><path d="$pathData"/></clipPath>""",
                    )
                    append("\n")
                }
                append("</defs>\n")
            }
            append(sb)
            append("</svg>\n")
        }

    private fun colorToSvg(color: Color): String =
        if (color.alpha == 255) {
            "rgb(${color.red},${color.green},${color.blue})"
        } else {
            "rgba(${color.red},${color.green},${color.blue},${String.format("%.3f", color.alpha / 255.0)})"
        }

    private fun escapeXml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun getStrokeWidth(): Float =
        if (currentStroke is BasicStroke) {
            (currentStroke as BasicStroke).lineWidth
        } else {
            1f
        }

    private fun getSvgTransform(): String {
        val m = DoubleArray(6)
        transform.getMatrix(m)
        return "matrix(${m[0]},${m[1]},${m[2]},${m[3]},${m[4]},${m[5]})"
    }

    private fun appendTransform(sb: StringBuilder) {
        if (!transform.isIdentity) {
            sb.append(" transform=\"${getSvgTransform()}\"")
        }
    }

    private fun appendClipPath(sb: StringBuilder) {
        // Stored clips use device coordinates; SVG clip paths need the current user coordinates.
        val userClip = getClip() ?: return
        val ref = registerClipPath(userClip)
        sb.append(" clip-path=\"url(#$ref)\"")
    }

    private fun registerClipPath(shape: Shape): String {
        val pathData = shapeToSvgPath(shape)
        val existingIndex = clipPaths.indexOf(pathData)
        val index =
            if (existingIndex >= 0) {
                existingIndex
            } else {
                clipPaths.add(pathData)
                clipPaths.size - 1
            }
        return "$clipPathPrefix$index"
    }

    private fun getStrokeStyle(): String {
        val strokeWidth = getStrokeWidth()
        val style = StringBuilder()
        style.append("stroke:${colorToSvg(currentColor)};stroke-width:$strokeWidth")

        if (currentStroke is BasicStroke) {
            val bs = currentStroke as BasicStroke
            when (bs.endCap) {
                BasicStroke.CAP_BUTT -> style.append(";stroke-linecap:butt")
                BasicStroke.CAP_ROUND -> style.append(";stroke-linecap:round")
                BasicStroke.CAP_SQUARE -> style.append(";stroke-linecap:square")
            }
            when (bs.lineJoin) {
                BasicStroke.JOIN_MITER -> style.append(";stroke-linejoin:miter")
                BasicStroke.JOIN_ROUND -> style.append(";stroke-linejoin:round")
                BasicStroke.JOIN_BEVEL -> style.append(";stroke-linejoin:bevel")
            }
            val dash = bs.dashArray
            if (dash != null && dash.isNotEmpty()) {
                style.append(";stroke-dasharray:${dash.joinToString(",")}")
            }
        }
        return style.toString()
    }

    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) {
        line.setLine(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble())
        draw(line)
    }

    override fun drawRect(x: Int, y: Int, width: Int, height: Int) {
        rect.setRect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
        draw(rect)
    }

    override fun fillRect(x: Int, y: Int, width: Int, height: Int) {
        rect.setRect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
        fill(rect)
    }

    override fun drawRoundRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        arcWidth: Int,
        arcHeight: Int,
    ) {
        roundRect.setRoundRect(
            x.toDouble(),
            y.toDouble(),
            width.toDouble(),
            height.toDouble(),
            arcWidth.toDouble(),
            arcHeight.toDouble(),
        )
        draw(roundRect)
    }

    override fun fillRoundRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        arcWidth: Int,
        arcHeight: Int,
    ) {
        roundRect.setRoundRect(
            x.toDouble(),
            y.toDouble(),
            width.toDouble(),
            height.toDouble(),
            arcWidth.toDouble(),
            arcHeight.toDouble(),
        )
        fill(roundRect)
    }

    override fun drawOval(x: Int, y: Int, width: Int, height: Int) {
        oval.setFrame(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
        draw(oval)
    }

    override fun fillOval(x: Int, y: Int, width: Int, height: Int) {
        oval.setFrame(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
        fill(oval)
    }

    override fun drawArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) {
        arc.setArc(
            x.toDouble(),
            y.toDouble(),
            width.toDouble(),
            height.toDouble(),
            startAngle.toDouble(),
            arcAngle.toDouble(),
            Arc2D.OPEN,
        )
        draw(arc)
    }

    override fun fillArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) {
        arc.setArc(
            x.toDouble(),
            y.toDouble(),
            width.toDouble(),
            height.toDouble(),
            startAngle.toDouble(),
            arcAngle.toDouble(),
            Arc2D.PIE,
        )
        fill(arc)
    }

    override fun drawPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) =
        draw(Polygon(xPoints, yPoints, nPoints))

    override fun fillPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) =
        fill(Polygon(xPoints, yPoints, nPoints))

    override fun drawPolyline(xPoints: IntArray, yPoints: IntArray, nPoints: Int) {
        if (nPoints < 2) return
        val path = Path2D.Double()
        path.moveTo(xPoints[0].toDouble(), yPoints[0].toDouble())
        for (i in 1 until nPoints) {
            path.lineTo(xPoints[i].toDouble(), yPoints[i].toDouble())
        }
        draw(path)
    }

    override fun draw(s: Shape) {
        when (s) {
            is Line2D -> {
                sb.append("<line x1=\"${s.x1}\" y1=\"${s.y1}\" x2=\"${s.x2}\" y2=\"${s.y2}\"")
                appendTransform(sb)
                appendClipPath(sb)
                sb.append(" style=\"${getStrokeStyle()}\"/>\n")
            }
            else -> appendShape(s, filled = false)
        }
    }

    override fun fill(s: Shape) = appendShape(s, filled = true)

    private fun appendShape(s: Shape, filled: Boolean) {
        when (s) {
            is Rectangle2D -> {
                sb.append(
                    "<rect x=\"${s.x}\" y=\"${s.y}\" width=\"${s.width}\" height=\"${s.height}\"",
                )
            }
            is RoundRectangle2D -> {
                sb.append(
                    "<rect x=\"${s.x}\" y=\"${s.y}\" width=\"${s.width}\" height=\"${s.height}\"",
                )
                sb.append(" rx=\"${s.arcWidth / 2}\" ry=\"${s.arcHeight / 2}\"")
            }
            is Ellipse2D -> {
                val cx = s.centerX
                val cy = s.centerY
                val rx = s.width / 2
                val ry = s.height / 2
                sb.append("<ellipse cx=\"$cx\" cy=\"$cy\" rx=\"$rx\" ry=\"$ry\"")
            }
            else -> {
                val pathData = shapeToSvgPath(s)
                if (pathData.isEmpty()) return
                sb.append("<path d=\"$pathData\"")
            }
        }
        appendTransform(sb)
        appendClipPath(sb)
        sb.append(
            if (filled) " style=\"fill:${colorToSvg(currentColor)};stroke:none\"/>\n"
            else " style=\"fill:none;${getStrokeStyle()}\"/>\n",
        )
    }

    private fun shapeToSvgPath(s: Shape): String {
        val path = StringBuilder()
        val coords = DoubleArray(6)
        val pathIterator = s.getPathIterator(null)
        while (!pathIterator.isDone) {
            when (pathIterator.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> path.append("M${coords[0]} ${coords[1]} ")
                PathIterator.SEG_LINETO -> path.append("L${coords[0]} ${coords[1]} ")
                PathIterator.SEG_QUADTO -> path.append("Q${coords[0]} ${coords[1]} ${coords[2]} ${coords[3]} ")
                PathIterator.SEG_CUBICTO ->
                    path.append(
                        "C${coords[0]} ${coords[1]} ${coords[2]} ${coords[3]} ${coords[4]} ${coords[5]} ",
                    )
                PathIterator.SEG_CLOSE -> path.append("Z ")
            }
            pathIterator.next()
        }
        return path.toString().trim()
    }

    override fun drawString(str: String, x: Int, y: Int) = drawString(str, x.toFloat(), y.toFloat())

    override fun drawString(str: String, x: Float, y: Float) {
        sb.append("<text x=\"$x\" y=\"$y\"")
        appendTransform(sb)
        appendClipPath(sb)
        sb.append(" style=\"${getSvgFontStyle()}\">")
        sb.append(escapeXml(str))
        sb.append("</text>\n")
    }

    private fun fontFamilyToCss(family: String): String =
        when (family.lowercase()) {
            "sansserif",
            "dialog",
            "dialoginput" -> "sans-serif"
            "serif" -> "serif"
            "monospaced" -> "monospace"
            else -> {
                "'$family', sans-serif"
            }
        }

    private fun getSvgFontStyle(): String {
        val style = StringBuilder()
        style.append("fill:${colorToSvg(currentColor)}")
        style.append(";font-family:${fontFamilyToCss(currentFont.family)}")
        style.append(";font-size:${currentFont.size}px")
        if (currentFont.isBold) style.append(";font-weight:bold")
        if (currentFont.isItalic) style.append(";font-style:italic")
        return style.toString()
    }

    override fun drawString(iterator: AttributedCharacterIterator, x: Int, y: Int) =
        drawString(iterator, x.toFloat(), y.toFloat())

    override fun drawString(iterator: AttributedCharacterIterator, x: Float, y: Float) {
        val keys = iterator.allAttributeKeys
        if (keys.isEmpty()) {
            val text = StringBuilder()
            var c = iterator.first()
            while (c != AttributedCharacterIterator.DONE) {
                text.append(c)
                c = iterator.next()
            }
            if (text.isNotEmpty()) {
                drawString(text.toString(), x, y)
            }
            return
        }

        // TextLayout preserves attributed-text placement by rendering glyphs as SVG paths.
        val textLayout = TextLayout(iterator, fontRenderContext)
        textLayout.draw(this, x, y)
    }

    override fun drawGlyphVector(g: GlyphVector, x: Float, y: Float) = fill(g.getOutline(x, y))

    override fun setColor(c: Color?) {
        if (c != null) currentColor = c
    }

    override fun getColor(): Color = currentColor

    override fun setFont(font: Font?) {
        if (font != null) currentFont = font
    }

    override fun getFont(): Font = currentFont

    override fun setStroke(s: Stroke) {
        currentStroke = s
    }

    override fun getStroke(): Stroke = currentStroke

    override fun translate(x: Int, y: Int) = translate(x.toDouble(), y.toDouble())

    override fun translate(tx: Double, ty: Double) = transform.translate(tx, ty)

    override fun rotate(theta: Double) = transform.rotate(theta)

    override fun rotate(theta: Double, x: Double, y: Double) = transform.rotate(theta, x, y)

    override fun scale(sx: Double, sy: Double) = transform.scale(sx, sy)

    override fun shear(shx: Double, shy: Double) = transform.shear(shx, shy)

    override fun transform(affineTransform: AffineTransform) = transform.concatenate(affineTransform)

    override fun getTransform(): AffineTransform = transform.clone() as AffineTransform

    override fun setTransform(affineTransform: AffineTransform?) {
        transform = if (affineTransform == null) AffineTransform() else AffineTransform(affineTransform)
    }

    override fun setClip(x: Int, y: Int, width: Int, height: Int) {
        clip = Rectangle(x, y, width, height)
    }

    override fun setClip(shape: Shape?) {
        clip =
            if (shape != null) {
                transform.createTransformedShape(shape)
            } else {
                null
            }
    }

    override fun getClip(): Shape? {
        if (clip == null) return null
        return try {
            val inv = transform.createInverse()
            inv.createTransformedShape(clip)
        } catch (e: NoninvertibleTransformException) {
            null
        }
    }

    override fun getClipBounds(): Rectangle? = getClip()?.bounds

    override fun clipRect(x: Int, y: Int, width: Int, height: Int) = clip(Rectangle(x, y, width, height))

    override fun clip(s: Shape?) {
        if (s == null) return
        val shape = if (s is Line2D) s.bounds2D else s

        if (clip == null) {
            clip = shape
            return
        }

        val transformedShape = transform.createTransformedShape(shape)
        if (!transformedShape.intersects(clip!!.bounds2D)) {
            clip = Rectangle2D.Double()
        } else {
            val a1 = Area(transformedShape)
            val a2 = Area(clip)
            a1.intersect(a2)
            clip = Path2D.Double(a1)
        }
    }

    override fun create(): Graphics {
        // Share output and clip definitions so child drawing appears in the same document.
        val copy = SimpleSVGGraphics2D(width, height, sb, clipPaths, clipPathPrefix)
        copy.currentColor = this.currentColor
        copy.currentFont = this.currentFont
        copy.currentStroke = this.currentStroke
        copy.transform = AffineTransform(this.transform)
        copy.clip = this.clip
        copy.hints.putAll(this.hints)
        copy.currentComposite = this.currentComposite
        copy.currentPaint = this.currentPaint
        copy.currentBackground = this.currentBackground
        return copy
    }

    override fun dispose() {}

    override fun getFontMetrics(f: Font): FontMetrics {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g2d = img.createGraphics()
        g2d.font = f
        val fm = g2d.fontMetrics
        g2d.dispose()
        return fm
    }

    override fun getFontRenderContext(): FontRenderContext = FontRenderContext(null, true, true)

    private val hints = RenderingHints(null as Map<RenderingHints.Key, Any>?)

    override fun setRenderingHint(hintKey: RenderingHints.Key, hintValue: Any?) {
        if (hintValue != null) hints[hintKey] = hintValue
    }

    override fun getRenderingHint(hintKey: RenderingHints.Key): Any? = hints[hintKey]

    override fun setRenderingHints(hints: Map<*, *>?) {
        this.hints.clear()
        if (hints != null) {
            @Suppress("UNCHECKED_CAST") this.hints.putAll(hints as Map<RenderingHints.Key, Any>)
        }
    }

    override fun addRenderingHints(hints: Map<*, *>?) {
        if (hints != null) {
            @Suppress("UNCHECKED_CAST") this.hints.putAll(hints as Map<RenderingHints.Key, Any>)
        }
    }

    override fun getRenderingHints(): RenderingHints = hints.clone() as RenderingHints

    private var currentComposite: Composite = AlphaComposite.SrcOver
    private var currentPaint: Paint = currentColor
    private var currentBackground: Color = Color.WHITE

    override fun setComposite(comp: Composite) {
        currentComposite = comp
    }

    override fun getComposite(): Composite = currentComposite

    override fun setPaint(paint: Paint?) {
        currentPaint = paint ?: currentColor
        if (paint is Color) currentColor = paint
    }

    override fun getPaint(): Paint = currentPaint

    override fun setBackground(color: Color?) {
        if (color != null) currentBackground = color
    }

    override fun getBackground(): Color = currentBackground

    override fun clearRect(x: Int, y: Int, width: Int, height: Int) {
        val prevColor = currentColor
        currentColor = currentBackground
        fillRect(x, y, width, height)
        currentColor = prevColor
    }

    // Image drawing is unsupported; these overrides emit nothing.

    override fun drawImage(img: Image?, x: Int, y: Int, observer: ImageObserver?): Boolean = true

    override fun drawImage(
        img: Image?,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        observer: ImageObserver?,
    ): Boolean = true

    override fun drawImage(
        img: Image?,
        x: Int,
        y: Int,
        bgcolor: Color?,
        observer: ImageObserver?,
    ): Boolean = true

    override fun drawImage(
        img: Image?,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        bgcolor: Color?,
        observer: ImageObserver?,
    ): Boolean = true

    override fun drawImage(
        img: Image?,
        dx1: Int,
        dy1: Int,
        dx2: Int,
        dy2: Int,
        sx1: Int,
        sy1: Int,
        sx2: Int,
        sy2: Int,
        observer: ImageObserver?,
    ): Boolean = true

    override fun drawImage(
        img: Image?,
        dx1: Int,
        dy1: Int,
        dx2: Int,
        dy2: Int,
        sx1: Int,
        sy1: Int,
        sx2: Int,
        sy2: Int,
        bgcolor: Color?,
        observer: ImageObserver?,
    ): Boolean = true

    override fun drawImage(img: Image, xform: AffineTransform?, obs: ImageObserver?): Boolean = true

    override fun drawImage(img: BufferedImage, op: BufferedImageOp?, x: Int, y: Int) {}

    override fun drawRenderedImage(img: RenderedImage, xform: AffineTransform?) {}

    override fun drawRenderableImage(img: RenderableImage, xform: AffineTransform?) {}

    override fun hit(rect: Rectangle, s: Shape, onStroke: Boolean): Boolean = rect.intersects(s.bounds2D)

    override fun getDeviceConfiguration(): GraphicsConfiguration? = null

    override fun copyArea(x: Int, y: Int, width: Int, height: Int, dx: Int, dy: Int) {}

    override fun setPaintMode() {}

    override fun setXORMode(c1: Color?) {}
}
