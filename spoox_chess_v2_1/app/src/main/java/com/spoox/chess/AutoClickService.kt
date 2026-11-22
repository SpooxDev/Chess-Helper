package com.spoox.chess

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.Random
import kotlin.math.abs

class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoClickService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val rng = Random()
    @Volatile private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun jitteredPoint(cx: Float, cy: Float, cellSize: Float, antiBan: Boolean, wideOffset: Boolean = false): Pair<Float, Float> {
        if (!antiBan) return Pair(cx, cy)
        val maxOff = cellSize * if (wideOffset) 0.30f else 0.22f
        // Гауссоподобный разброс (сумма двух случайных), всегда остаётся строго внутри нужной клетки
        val rx = ((rng.nextFloat() + rng.nextFloat()) - 1f) * maxOff
        val ry = ((rng.nextFloat() + rng.nextFloat()) - 1f) * maxOff
        return Pair((cx + rx).coerceAtLeast(1f), (cy + ry).coerceAtLeast(1f))
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long, onDone: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }
        }, handler)
    }

    private fun dispatchCurvedSwipe(x1: Float, y1: Float, x2: Float, y2: Float, cellSize: Float, durationMs: Long, onDone: (() -> Unit)? = null) {
        val midX = (x1 + x2) / 2f + ((rng.nextFloat() - 0.5f) * cellSize * 0.45f)
        val midY = (y1 + y2) / 2f + ((rng.nextFloat() - 0.5f) * cellSize * 0.45f)
        val path = Path().apply {
            moveTo(x1, y1)
            quadTo(midX, midY, x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }
        }, handler)
    }

    /**
     * Выполняет ход на доске Chess.com с полной имитацией живого пальца:
     * - случайная точка внутри каждой клетки (антибан-разброс)
     * - чередование двойного тапа и слегка изогнутого свайпа
     * - изредка (~14%) «поправка» нажатия ближе к краю клетки (без потери точности хода)
     * - автоматический выбор фигуры при превращении пешки
     */
    fun performMove(
        uci: String,
        boardRect: RectF,
        isFlipped: Boolean,
        antiBan: Boolean,
        onFinished: (() -> Unit)? = null
    ) {
        if (busy || uci.length < 4) return
        val fromCol = uci[0] - 'a'
        val fromRow = 8 - (uci[1] - '0')
        val toCol = uci[2] - 'a'
        val toRow = 8 - (uci[3] - '0')
        if (fromCol !in 0..7 || fromRow !in 0..7 || toCol !in 0..7 || toRow !in 0..7) return

        val vFromCol = if (!isFlipped) fromCol else 7 - fromCol
        val vFromRow = if (!isFlipped) fromRow else 7 - fromRow
        val vToCol = if (!isFlipped) toCol else 7 - toCol
        val vToRow = if (!isFlipped) toRow else 7 - toRow

        val cellW = boardRect.width() / 8f
        val cellH = boardRect.height() / 8f

        val cx1 = boardRect.left + (vFromCol + 0.5f) * cellW
        val cy1 = boardRect.top + (vFromRow + 0.5f) * cellH
        val cx2 = boardRect.left + (vToCol + 0.5f) * cellW
        val cy2 = boardRect.top + (vToRow + 0.5f) * cellH

        val wideMissLook = antiBan && rng.nextInt(100) < 18
        val (p1x, p1y) = jitteredPoint(cx1, cy1, cellW, antiBan, wideMissLook)
        val (p2x, p2y) = jitteredPoint(cx2, cy2, cellW, antiBan, wideMissLook)

        val promoChar = if (uci.length >= 5) uci[4].lowercaseChar() else null

        busy = true

        fun finishMove() {
            if (promoChar != null) {
                // На Chess.com меню превращения открывается вертикально от целевой клетки:
                // 0 = ферзь (Q), 1 = конь (N) или ладья (R) в зависимости от UI; для ферзя всегда 0 (целевая клетка)
                val promoOffsetRow = when (promoChar) {
                    'q' -> 0
                    'n' -> 1
                    'r' -> 2
                    'b' -> 3
                    else -> 0
                }
                val dir = if (vToRow == 0) 1 else -1
                val pRow = (vToRow + promoOffsetRow * dir).coerceIn(0, 7)
                val pcx = boardRect.left + (vToCol + 0.5f) * cellW
                val pcy = boardRect.top + (pRow + 0.5f) * cellH
                val (px, py) = jitteredPoint(pcx, pcy, cellW, antiBan)
                val promoDelay = if (antiBan) (260L + rng.nextInt(180)) else 220L
                handler.postDelayed({
                    dispatchTap(px, py, (50 + rng.nextInt(45)).toLong()) {
                        busy = false
                        onFinished?.invoke()
                    }
                }, promoDelay)
            } else {
                busy = false
                onFinished?.invoke()
            }
        }

        val useSwipe = antiBan && rng.nextInt(100) < 35
        if (useSwipe) {
            val swipeDur = (185 + rng.nextInt(145)).toLong()
            dispatchCurvedSwipe(p1x, p1y, p2x, p2y, cellW, swipeDur) {
                finishMove()
            }
        } else {
            val tap1Dur = if (antiBan) (45 + rng.nextInt(65)).toLong() else 45L
            val tap2Dur = if (antiBan) (45 + rng.nextInt(65)).toLong() else 45L
            val gapMs = if (antiBan) (130 + rng.nextInt(230)).toLong() else 90L

            dispatchTap(p1x, p1y, tap1Dur) {
                handler.postDelayed({
                    dispatchTap(p2x, p2y, tap2Dur) {
                        finishMove()
                    }
                }, gapMs)
            }
        }
    }
}
