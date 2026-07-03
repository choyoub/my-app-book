package com.netice.myapp.durumari.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.netice.myapp.durumari.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class ScrollArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var compact: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var unrollProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var sealProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var tasselProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ScrollPalette.INK
        textAlign = Paint.Align.CENTER
    }
    private val sealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()
    private val dragonCloudMotif: Drawable? = context.getDrawable(R.drawable.dragon_cloud_motif)?.mutate()
    private val calligraphyTypeface = loadTypeface("fonts/Dokdo-Regular.ttf")
        ?: loadTypeface("fonts/MaruBuri.ttf")
        ?: Typeface.SERIF

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        textPaint.typeface = Typeface.create(calligraphyTypeface, Typeface.BOLD)
        sealPaint.typeface = Typeface.create(calligraphyTypeface, Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val geometry = buildGeometry()
        val paperVisibleHeight = geometry.paperHeight * unrollProgress
        val centerX = geometry.stageLeft + geometry.stageWidth / 2f

        if (paperVisibleHeight > 0.5f) {
            drawPaper(canvas, geometry, paperVisibleHeight)
        }
        drawScrollRod(canvas, centerX, geometry.stageTop + geometry.rodTop, geometry.rodWidth, axis = false, geometry.unit)
        drawScrollRod(
            canvas,
            centerX,
            geometry.stageTop + geometry.bottomRodTop + paperVisibleHeight,
            geometry.rodWidth,
            axis = true,
            geometry.unit,
        )
        drawYuso(canvas, centerX, geometry)
    }

    private fun buildGeometry(): ScrollGeometry {
        val density = resources.displayMetrics.density
        val viewWidthDp = width / density
        val viewHeightDp = height / density
        val rootHeightDp = if (rootView.height > 0) rootView.height / density else 0f
        val screenHeightDp = resources.configuration.screenHeightDp.takeIf { it > 0 }?.toFloat() ?: 0f
        val baseHeightDp = max(viewHeightDp, max(rootHeightDp, screenHeightDp))

        val paperWidthDp = if (compact) {
            min(182f, max(154f, min(viewWidthDp * 0.44f, baseHeightDp * 0.26f)))
        } else {
            min(222f, max(176f, min(viewWidthDp * 0.56f, baseHeightDp * 0.30f)))
        }
        val basePaperHeightDp = if (compact) {
            min(226f, max(184f, baseHeightDp * 0.26f))
        } else {
            min(292f, max(218f, baseHeightDp * 0.33f))
        }
        val paperHeightDp = basePaperHeightDp + BOTTOM_SILK_ROD_HEIGHT_DP
        val rodWidthDp = paperWidthDp + 28f
        val rodTopDp = if (compact) 46f else 52f
        val paperTopDp = rodTopDp + 20f
        val bottomRodTopDp = rodTopDp + 14f
        val stageWidthDp = rodWidthDp + 54f
        val stageHeightDp = bottomRodTopDp + paperHeightDp + 34f
        val fitScale = min(1f, min(width / (stageWidthDp * density), height / (stageHeightDp * density)))
        val unit = density * fitScale
        val stageWidth = stageWidthDp * unit
        val stageHeight = stageHeightDp * unit

        return ScrollGeometry(
            unit = unit,
            stageLeft = (width - stageWidth) / 2f,
            stageTop = (height - stageHeight) / 2f,
            stageWidth = stageWidth,
            stageHeight = stageHeight,
            paperWidth = paperWidthDp * unit,
            paperHeight = paperHeightDp * unit,
            rodWidth = rodWidthDp * unit,
            rodTop = rodTopDp * unit,
            paperTop = paperTopDp * unit,
            bottomRodTop = bottomRodTopDp * unit,
            characterHeight = (if (compact) 11f else 14f) * unit,
            columnWidth = (if (compact) 13f else 18f) * unit,
        )
    }

    private fun drawYuso(canvas: Canvas, centerX: Float, geometry: ScrollGeometry) {
        val u = geometry.unit
        val rodTop = geometry.stageTop + geometry.rodTop
        val rodLeft = centerX - geometry.rodWidth / 2f
        val rodRight = centerX + geometry.rodWidth / 2f
        val nailCenterX = centerX
        val nailCenterY = rodTop - 54f * u
        val cordLeftX = rodLeft + 28f * u
        val cordRightX = rodRight - 28f * u
        val cordAnchorY = rodTop + 5f * u

        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = 2.2f * u
        strokePaint.color = ScrollPalette.CORD

        path.reset()
        path.moveTo(cordLeftX, cordAnchorY)
        path.lineTo(nailCenterX, nailCenterY)
        path.lineTo(cordRightX, cordAnchorY)
        canvas.drawPath(path, strokePaint)

        strokePaint.strokeWidth = 0.85f * u
        strokePaint.color = withAlpha(ScrollPalette.GOLD_LIGHT, 0.62f)
        path.reset()
        path.moveTo(cordLeftX + 1.8f * u, cordAnchorY + 1f * u)
        path.lineTo(nailCenterX + 1.6f * u, nailCenterY + 2f * u)
        path.lineTo(cordRightX - 1.8f * u, cordAnchorY + 1f * u)
        canvas.drawPath(path, strokePaint)

        fillPaint.shader = null
        fillPaint.style = Paint.Style.FILL
        rect.set(nailCenterX - 8f * u, nailCenterY - 8f * u, nailCenterX + 8f * u, nailCenterY + 8f * u)
        fillPaint.alpha = 255
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(ScrollPalette.GOLD_LIGHT, ScrollPalette.CORD, ScrollPalette.CORD_DARK),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(rect, fillPaint)
        fillPaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f * u
        strokePaint.color = ScrollPalette.CORD_DARK
        canvas.drawOval(rect, strokePaint)

        drawCordWrap(canvas, cordLeftX, rodTop + 11f * u, u)
        drawCordWrap(canvas, cordRightX, rodTop + 11f * u, u)
        drawRightRodTassel(canvas, rodRight + 13f * u, rodTop + 62f * u, u)
    }

    private fun drawCordWrap(canvas: Canvas, centerX: Float, centerY: Float, u: Float) {
        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = 1.15f * u
        strokePaint.color = withAlpha(ScrollPalette.CORD_DARK, 0.86f)
        for (index in 0 until 4) {
            val x = centerX + (index - 1.5f) * 2.2f * u
            canvas.drawLine(x, centerY - 13f * u, x + 1.7f * u, centerY + 13f * u, strokePaint)
        }
    }

    private fun drawRightRodTassel(canvas: Canvas, centerX: Float, top: Float, u: Float) {
        val cordProgress = (tasselProgress / 0.75f).coerceIn(0f, 1f)
        val swingProgress = ((tasselProgress - 0.75f) / 0.25f).coerceIn(0f, 1f)
        val swing = if (swingProgress > 0f) {
            (sin(swingProgress * PI * 4.0) * (1f - swingProgress) * 5.5f * u).toFloat()
        } else {
            0f
        }
        val finalCordLength = 52f * u
        val initialCordLength = 11f * u
        val cordLength = initialCordLength + (finalCordLength - initialCordLength) * cordProgress
        val anchorY = top - finalCordLength
        val knotY = anchorY + cordLength
        val tasselCenterX = centerX + swing

        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = 1.35f * u
        strokePaint.color = ScrollPalette.CORD_DARK
        path.reset()
        path.moveTo(centerX, anchorY)
        path.quadTo(centerX + swing * 0.35f, anchorY + cordLength * 0.55f, tasselCenterX, knotY - 1f * u)
        canvas.drawPath(path, strokePaint)
        strokePaint.strokeWidth = 0.7f * u
        strokePaint.color = withAlpha(ScrollPalette.GOLD_LIGHT, 0.7f)
        path.reset()
        path.moveTo(centerX + 1.5f * u, anchorY + 2f * u)
        path.quadTo(centerX + 1.5f * u + swing * 0.35f, anchorY + cordLength * 0.55f, tasselCenterX + 1.5f * u, knotY - 2f * u)
        canvas.drawPath(path, strokePaint)

        val knotSize = 11f * u
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = null
        fillPaint.color = ScrollPalette.TASSEL_RED
        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f * u
        strokePaint.color = ScrollPalette.GOLD
        drawDiamond(canvas, tasselCenterX, knotY, knotSize, fillPaint, strokePaint)

        val bandTop = knotY + 8f * u
        rect.set(tasselCenterX - 5f * u, bandTop, tasselCenterX + 5f * u, bandTop + 14f * u)
        fillPaint.alpha = 255
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(ScrollPalette.GOLD_LIGHT, ScrollPalette.CORD, ScrollPalette.CORD_DARK),
            null,
            Shader.TileMode.CLAMP,
        )
        fillPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, 2f * u, 2f * u, fillPaint)
        fillPaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 0.65f * u
        strokePaint.color = ScrollPalette.CORD_DARK
        canvas.drawRoundRect(rect, 2f * u, 2f * u, strokePaint)

        strokePaint.strokeCap = Paint.Cap.ROUND
        val threadTop = bandTop + 13f * u
        val offsets = floatArrayOf(-6f, -3.5f, -1.2f, 1.2f, 3.5f, 6f)
        offsets.forEachIndexed { index, offset ->
            strokePaint.strokeWidth = (if (index == 2 || index == 3) 1.45f else 1.1f) * u
            strokePaint.color = if (index % 2 == 0) ScrollPalette.TASSEL_RED else ScrollPalette.CORD
            path.reset()
            path.moveTo(tasselCenterX + offset * 0.2f * u, threadTop)
            path.cubicTo(
                tasselCenterX + offset * 0.50f * u + swing * 0.10f,
                threadTop + 9f * u,
                tasselCenterX + offset * 1.10f * u + swing * 0.18f,
                threadTop + 24f * u,
                tasselCenterX + offset * 1.30f * u + swing * 0.25f,
                threadTop + 44f * u,
            )
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun drawScrollRod(canvas: Canvas, centerX: Float, top: Float, width: Float, axis: Boolean, u: Float) {
        val wrapHeight = if (axis) 34f * u else 30f * u
        val bodyHeight = if (axis) 24f * u else 21f * u
        val capSize = if (axis) 18f * u else 15f * u
        val wrapWidth = width + 44f * u
        val wrapLeft = centerX - wrapWidth / 2f
        val bodyLeft = centerX - width / 2f
        val bodyTop = top + (wrapHeight - bodyHeight) / 2f
        val bodyRadius = 8f * u

        drawRodHandle(canvas, bodyLeft - 25f * u, top + (wrapHeight - 11f * u) / 2f, true, u)
        drawRodHandle(canvas, bodyLeft + width - 3f * u, top + (wrapHeight - 11f * u) / 2f, false, u)

        rect.set(bodyLeft, bodyTop, bodyLeft + width, bodyTop + bodyHeight)
        fillPaint.alpha = 255
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.left,
            rect.bottom,
            intArrayOf(
                ScrollPalette.ROLLED_SILK_LIGHT,
                ScrollPalette.ROLLED_SILK,
                ScrollPalette.ROLLED_SILK_DARK,
                ScrollPalette.ROLLED_SILK,
                ScrollPalette.ROLLED_SILK_LIGHT,
            ),
            floatArrayOf(0f, 0.22f, 0.5f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, bodyRadius, bodyRadius, fillPaint)
        fillPaint.shader = null
        drawRolledSilkPattern(canvas, rect, bodyRadius, u)
        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f * u
        strokePaint.color = ScrollPalette.ROLLED_SILK_DARK
        canvas.drawRoundRect(rect, bodyRadius, bodyRadius, strokePaint)

        drawRodCap(canvas, wrapLeft, top + 8f * u, capSize, u)
        drawRodCap(canvas, wrapLeft + wrapWidth - capSize, top + 8f * u, capSize, u)
    }

    private fun drawRodHandle(canvas: Canvas, left: Float, top: Float, leftSide: Boolean, u: Float) {
        rect.set(left, top, left + 28f * u, top + 11f * u)
        fillPaint.alpha = 255
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(ScrollPalette.WOOD_LIGHT, ScrollPalette.WOOD, ScrollPalette.WOOD_DARK),
            null,
            Shader.TileMode.CLAMP,
        )
        fillPaint.style = Paint.Style.FILL
        val radius = 10f * u
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        fillPaint.shader = null
        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f * u
        strokePaint.color = ScrollPalette.WOOD_DARK
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        if (leftSide) return
    }

    private fun drawRodCap(canvas: Canvas, left: Float, top: Float, size: Float, u: Float) {
        rect.set(left, top, left + size, top + size)
        fillPaint.alpha = 255
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(ScrollPalette.WOOD_LIGHT, ScrollPalette.WOOD, ScrollPalette.WOOD_DARK),
            null,
            Shader.TileMode.CLAMP,
        )
        fillPaint.style = Paint.Style.FILL
        canvas.drawOval(rect, fillPaint)
        fillPaint.shader = null
        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f * u
        strokePaint.color = ScrollPalette.WOOD_DARK
        canvas.drawOval(rect, strokePaint)
    }

    private fun drawPaper(canvas: Canvas, geometry: ScrollGeometry, visibleHeight: Float) {
        val u = geometry.unit
        val left = geometry.stageLeft + (geometry.stageWidth - geometry.paperWidth) / 2f
        val top = geometry.stageTop + geometry.paperTop
        val right = left + geometry.paperWidth
        val visibleBottom = top + visibleHeight
        val fullBottom = top + geometry.paperHeight

        canvas.save()
        canvas.clipRect(left, top, right, visibleBottom)
        rect.set(left, top, right, fullBottom)
        fillPaint.alpha = 255
        fillPaint.shader = LinearGradient(
            left,
            top,
            right,
            top,
            intArrayOf(
                ScrollPalette.PAPER_SHADOW,
                ScrollPalette.PAPER,
                ScrollPalette.PAPER_LIGHT,
                ScrollPalette.PAPER_SHADOW,
            ),
            floatArrayOf(0f, 0.12f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, fillPaint)
        fillPaint.shader = null

        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f * u
        strokePaint.color = ScrollPalette.PAPER_EDGE
        canvas.drawLine(left, top, left, fullBottom, strokePaint)
        canvas.drawLine(right, top, right, fullBottom, strokePaint)

        rect.set(left + 5f * u, top + 5f * u, right - 5f * u, fullBottom - 5f * u)
        strokePaint.color = withAlpha(ScrollPalette.PAPER_EDGE, 0.28f)
        canvas.drawRect(rect, strokePaint)

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = withAlpha(ScrollPalette.PAPER_FIBER, 0.055f)
        canvas.drawRect(left + geometry.paperWidth * 0.18f, top, left + geometry.paperWidth * 0.18f + u, fullBottom, fillPaint)
        fillPaint.color = withAlpha(ScrollPalette.PAPER_FIBER, 0.045f)
        canvas.drawRect(right - geometry.paperWidth * 0.24f, top, right - geometry.paperWidth * 0.24f + u, fullBottom, fillPaint)
        drawHanjiFibers(canvas, left, top, geometry.paperWidth, geometry.paperHeight, u)

        drawSilkBand(canvas, left, top + u, geometry.paperWidth - 2f * u, u)
        drawSilkBand(canvas, left, fullBottom - 39f * u, geometry.paperWidth - 2f * u, u)
        drawCalligraphy(canvas, left, top, geometry)
        drawSeal(canvas, left, top, geometry)
        canvas.restore()
    }

    private fun drawSilkBand(canvas: Canvas, left: Float, top: Float, width: Float, u: Float) {
        val height = 38f * u
        rect.set(left, top, left + width, top + height)
        fillPaint.alpha = 255
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(
                ScrollPalette.TRIM_SILK_DARK,
                ScrollPalette.TRIM_SILK_LIGHT,
                ScrollPalette.TRIM_SILK,
                ScrollPalette.TRIM_SILK_DARK,
            ),
            floatArrayOf(0f, 0.22f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        fillPaint.style = Paint.Style.FILL
        canvas.drawRect(rect, fillPaint)
        fillPaint.shader = null
        drawSilkDamask(canvas, rect, u, alpha = 0.22f)

        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f * u
        strokePaint.color = ScrollPalette.GOLD
        canvas.drawLine(left, top, left + width, top, strokePaint)
        canvas.drawLine(left, top + height, left + width, top + height, strokePaint)

        fillPaint.color = ScrollPalette.GOLD
        canvas.drawRect(left + 8f * u, top + 3f * u, left + width - 8f * u, top + 4f * u, fillPaint)
        canvas.drawRect(left + 8f * u, top + height - 4f * u, left + width - 8f * u, top + height - 3f * u, fillPaint)

        drawDragonCloudPatternRows(
            canvas = canvas,
            bounds = rect,
            u = u,
            columnWidth = 27f * u,
            motifWidth = 18f * u,
            motifHeight = 9.2f * u,
            alpha = 0.64f,
            topInset = 5f * u,
            bottomInset = 5f * u,
        )
    }

    private fun drawCalligraphy(canvas: Canvas, paperLeft: Float, paperTop: Float, geometry: ScrollGeometry) {
        val u = geometry.unit
        val areaTop = paperTop + 54f * u
        val areaBottom = paperTop + geometry.paperHeight - 74f * u
        val areaHeight = (areaBottom - areaTop).coerceAtLeast(120f * u)
        val textSize = (if (compact) 12.6f else 18.2f) * u
        val lineHeight = textSize * 1.28f
        val wordGap = textSize * 0.48f
        val columnWidth = textSize * 1.52f
        val columns = DASAN_QUOTE_COLUMNS
        val totalColumnsWidth = columns.size * columnWidth
        val areaCenterX = paperLeft + geometry.paperWidth / 2f
        val firstColumnX = areaCenterX + totalColumnsWidth / 2f - columnWidth / 2f

        textPaint.shader = null
        textPaint.style = Paint.Style.FILL
        textPaint.color = ScrollPalette.INK
        textPaint.textSize = textSize
        textPaint.setShadowLayer(0.35f * u, 0.25f * u, 0.35f * u, withAlpha(ScrollPalette.INK, 0.28f))

        val fontMetrics = textPaint.fontMetrics
        val baselineAdjust = -(fontMetrics.ascent + fontMetrics.descent) / 2f

        columns.forEachIndexed { columnIndex, columnText ->
            val words = columnText.split(" ")
            var rowY = areaTop + lineHeight / 2f
            val drawX = firstColumnX - columnIndex * columnWidth
            words.forEachIndexed { wordIndex, word ->
                word.forEach { char ->
                    canvas.drawText(char.toString(), drawX, rowY + baselineAdjust, textPaint)
                    rowY += lineHeight
                }
                if (wordIndex < words.lastIndex) {
                    rowY += wordGap
                }
            }
        }
        textPaint.clearShadowLayer()
    }

    private fun drawSeal(canvas: Canvas, paperLeft: Float, paperTop: Float, geometry: ScrollGeometry) {
        if (sealProgress <= 0f) return
        val u = geometry.unit
        val size = (if (compact) 24f else 32f) * u
        val left = paperLeft + (if (compact) 16f else 24f) * u
        val top = paperTop + geometry.paperHeight - (if (compact) 56f else 60f) * u - size
        val alpha = (sealProgress * 255f).toInt().coerceIn(0, 255)
        val scale = 1.8f - 0.8f * sealProgress

        canvas.save()
        canvas.translate(left + size / 2f, top + size / 2f)
        canvas.rotate(-5f)
        canvas.scale(scale, scale)

        rect.set(-size / 2f, -size / 2f, size / 2f, size / 2f)
        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 2f * u
        strokePaint.color = withAlpha(ScrollPalette.SEAL, alpha / 255f)
        canvas.drawRect(rect, strokePaint)

        sealPaint.shader = null
        sealPaint.style = Paint.Style.FILL
        sealPaint.color = withAlpha(ScrollPalette.SEAL, alpha / 255f)
        sealPaint.textSize = (if (compact) 10f else 12f) * u
        val lineGap = (if (compact) 9f else 12f) * u
        val fontMetrics = sealPaint.fontMetrics
        val adjust = -(fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText("다", 0f, -lineGap / 2f + adjust, sealPaint)
        canvas.drawText("산", 0f, lineGap / 2f + adjust, sealPaint)
        canvas.restore()
    }

    private fun drawRolledSilkPattern(canvas: Canvas, bounds: RectF, radius: Float, u: Float) {
        val left = bounds.left
        val top = bounds.top
        val right = bounds.right
        val bottom = bounds.bottom
        val width = bounds.width()
        val height = bounds.height()

        canvas.save()
        path.reset()
        path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)

        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 0.55f * u
        strokePaint.color = withAlpha(ScrollPalette.SILK_PATTERN, 0.28f)
        val step = 34f * u
        var x = left - width
        while (x < right + width) {
            canvas.drawLine(x, bottom - 2f * u, x + 48f * u, top + 2f * u, strokePaint)
            x += step
        }

        drawDragonCloudPatternRows(
            canvas = canvas,
            bounds = bounds,
            u = u,
            columnWidth = 24f * u,
            motifWidth = 15.5f * u,
            motifHeight = 6.5f * u,
            alpha = 0.56f,
            topInset = 2.5f * u,
            bottomInset = 2.5f * u,
        )

        fillPaint.color = withAlpha(Color.WHITE, 0.12f)
        rect.set(left + 4f * u, top + height * 0.18f, right - 4f * u, top + height * 0.28f)
        canvas.drawRoundRect(rect, 3f * u, 3f * u, fillPaint)
        fillPaint.color = withAlpha(Color.BLACK, 0.20f)
        rect.set(left + 3f * u, bottom - height * 0.28f, right - 3f * u, bottom - height * 0.16f)
        canvas.drawRoundRect(rect, 3f * u, 3f * u, fillPaint)
        canvas.restore()
    }

    private fun drawHanjiFibers(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, u: Float) {
        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 0.42f * u
        strokePaint.color = withAlpha(ScrollPalette.PAPER_FIBER, 0.07f)
        for (index in 0 until 12) {
            val x = left + width * ((index * 37 % 100) / 100f)
            val startY = top + height * ((index * 17 % 100) / 100f)
            canvas.drawLine(x, startY, x + (if (index % 2 == 0) 16f else -12f) * u, startY + 18f * u, strokePaint)
        }
        strokePaint.strokeWidth = 0.30f * u
        strokePaint.color = withAlpha(ScrollPalette.PAPER_FIBER, 0.045f)
        for (index in 0 until 10) {
            val y = top + height * ((index * 23 % 100) / 100f)
            val startX = left + width * ((index * 11 % 100) / 100f)
            canvas.drawLine(startX, y, startX + 28f * u, y + (index % 3 - 1) * 2f * u, strokePaint)
        }
    }

    private fun drawSilkDamask(canvas: Canvas, bounds: RectF, u: Float, alpha: Float) {
        val left = bounds.left
        val top = bounds.top
        val right = bounds.right
        val bottom = bounds.bottom
        val width = bounds.width()
        val height = bounds.height()

        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 0.55f * u
        strokePaint.color = withAlpha(ScrollPalette.SILK_PATTERN, alpha)
        val repeat = 28f * u
        var x = left - repeat
        while (x < right + repeat) {
            path.reset()
            path.moveTo(x, top + height * 0.18f)
            path.quadTo(x + repeat * 0.35f, top + height * 0.48f, x + repeat * 0.75f, top + height * 0.22f)
            path.quadTo(x + repeat, top + height * 0.04f, x + repeat * 1.25f, top + height * 0.35f)
            canvas.drawPath(path, strokePaint)
            path.reset()
            path.moveTo(x + repeat * 0.18f, bottom - height * 0.20f)
            path.quadTo(x + repeat * 0.55f, bottom - height * 0.50f, x + repeat, bottom - height * 0.24f)
            canvas.drawPath(path, strokePaint)
            x += repeat
        }
    }

    private fun drawDragonCloudVector(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        motifWidth: Float,
        motifHeight: Float,
        alpha: Float,
        facingRight: Boolean,
    ) {
        val drawable = dragonCloudMotif ?: return
        val direction = if (facingRight) 1f else -1f

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(direction, 1f)
        drawable.setTint(withAlpha(ScrollPalette.SILK_MOTIF, alpha))
        drawable.setBounds(
            (-motifWidth / 2f).toInt(),
            (-motifHeight / 2f).toInt(),
            (motifWidth / 2f).toInt(),
            (motifHeight / 2f).toInt(),
        )
        drawable.draw(canvas)
        canvas.restore()
    }

    private fun drawDragonCloudPatternRows(
        canvas: Canvas,
        bounds: RectF,
        u: Float,
        columnWidth: Float,
        motifWidth: Float,
        motifHeight: Float,
        alpha: Float,
        topInset: Float,
        bottomInset: Float,
    ) {
        val left = bounds.left + 8f * u
        val right = bounds.right - 8f * u
        val rowTop = bounds.top + topInset + motifHeight / 2f
        val rowBottom = bounds.bottom - bottomInset - motifHeight / 2f

        drawDragonCloudPatternRow(
            canvas = canvas,
            startX = left + columnWidth / 2f,
            endX = right + columnWidth / 2f,
            centerY = rowTop,
            columnWidth = columnWidth,
            motifWidth = motifWidth,
            motifHeight = motifHeight,
            alpha = alpha,
            rowOffset = 0,
        )
        drawDragonCloudPatternRow(
            canvas = canvas,
            startX = left + columnWidth,
            endX = right + columnWidth / 2f,
            centerY = rowBottom,
            columnWidth = columnWidth,
            motifWidth = motifWidth,
            motifHeight = motifHeight,
            alpha = alpha * 0.92f,
            rowOffset = 1,
        )
    }

    private fun drawDragonCloudPatternRow(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        centerY: Float,
        columnWidth: Float,
        motifWidth: Float,
        motifHeight: Float,
        alpha: Float,
        rowOffset: Int,
    ) {
        var index = 0
        var cx = startX
        while (cx <= endX) {
            drawDragonCloudVector(
                canvas = canvas,
                centerX = cx,
                centerY = centerY,
                motifWidth = motifWidth,
                motifHeight = motifHeight,
                alpha = alpha,
                facingRight = (index + rowOffset) % 2 == 0,
            )
            index += 1
            cx += columnWidth
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        return Color.argb(
            (Color.alpha(color) * alpha).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    private fun drawDiamond(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        size: Float,
        fill: Paint,
        stroke: Paint,
    ) {
        val half = size / 2f
        path.reset()
        path.moveTo(centerX, centerY - half)
        path.lineTo(centerX + half, centerY)
        path.lineTo(centerX, centerY + half)
        path.lineTo(centerX - half, centerY)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }

    private fun loadTypeface(assetPath: String): Typeface? =
        runCatching { Typeface.createFromAsset(context.assets, assetPath) }.getOrNull()

    private data class ScrollGeometry(
        val unit: Float,
        val stageLeft: Float,
        val stageTop: Float,
        val stageWidth: Float,
        val stageHeight: Float,
        val paperWidth: Float,
        val paperHeight: Float,
        val rodWidth: Float,
        val rodTop: Float,
        val paperTop: Float,
        val bottomRodTop: Float,
        val characterHeight: Float,
        val columnWidth: Float,
    )

    private companion object {
        private const val BOTTOM_SILK_ROD_HEIGHT_DP = 34f

        private object ScrollPalette {
            val PAPER = Color.rgb(251, 244, 227)
            val PAPER_LIGHT = Color.rgb(255, 252, 242)
            val PAPER_SHADOW = Color.rgb(235, 220, 184)
            val PAPER_EDGE = Color.rgb(178, 148, 90)
            val PAPER_FIBER = Color.rgb(170, 136, 74)

            val INK = Color.rgb(26, 18, 12)

            val SEAL = Color.rgb(217, 42, 36)
            val SEAL_DARK = Color.rgb(151, 20, 18)
            val SEAL_LIGHT = Color.rgb(255, 94, 74)

            val ROLLED_SILK = Color.rgb(204, 72, 56)
            val ROLLED_SILK_LIGHT = Color.rgb(239, 121, 96)
            val ROLLED_SILK_DARK = Color.rgb(137, 36, 27)

            val TRIM_SILK = Color.rgb(90, 154, 174)
            val TRIM_SILK_LIGHT = Color.rgb(158, 202, 209)
            val TRIM_SILK_DARK = Color.rgb(49, 105, 125)

            val SILK_PATTERN = Color.rgb(231, 184, 80)
            val SILK_MOTIF = Color.rgb(255, 224, 154)
            val GOLD = Color.rgb(243, 199, 102)
            val GOLD_LIGHT = Color.rgb(255, 226, 140)
            val GOLD_DARK = Color.rgb(188, 128, 42)

            val WOOD = Color.rgb(122, 58, 24)
            val WOOD_LIGHT = Color.rgb(188, 101, 48)
            val WOOD_DARK = Color.rgb(54, 22, 9)

            val CORD = Color.rgb(89, 52, 31)
            val CORD_DARK = Color.rgb(46, 26, 18)
            val TASSEL_RED = Color.rgb(128, 35, 26)
        }

        private val DASAN_QUOTE_COLUMNS = listOf(
            "여가가 생긴 뒤에",
            "책을 읽으려 한다면",
            "결코 책을 읽을",
            "기회가 없을",
            "것이다.",
        )
    }
}
