package com.spoox.chess

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.*
import android.widget.*
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var pal: AppPalette
    private var panelLayout: LinearLayout? = null
    private var arrowView: BoardOverlayView? = null
    private lateinit var arrowParams: WindowManager.LayoutParams
    private var webView: WebView? = null
    private var moveMainText: TextView? = null
    private var evalBadgeText: TextView? = null
    private var statusDot: StatusDotView? = null
    private var evalBar: EvalBarView? = null

    private val fallbackEngine = SpooxEngine()
    private var stockfish: StockfishEngine? = null
    private var usingStockfish = false
    private val rng = Random()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val frameLock = Any()
    private var latestFrame: Bitmap? = null

    private var screenW = 1080
    private var screenH = 2400
    private var screenDpi = 420

    private var isBlackSide = false
    private var thinkTimeMs = 1500
    @Volatile private var antiBanEnabled = true
    @Volatile private var autoMoveEnabled = false
    private var isCalibrating = false
    private var isScanning = false
    private var hasStartedGame = false
    private var pendingAutoDetect = false

    private var pieceLumThreshold = 155f
    private val pieceSilhouettes = HashMap<Char, FloatArray>()

    private val handler = Handler(Looper.getMainLooper())
    private var candidateSig = ""
    private var confirmedSig = ""
    private var pendingMoveTask: Runnable? = null
    @Volatile private var autoClickLockUntilMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    private fun addTapFeel(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.90f).scaleY(0.90f).setDuration(75).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(1.7f)).start()
            }
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        cleanupResources()

        pal = ThemeManager.getPalette(this)
        isBlackSide = intent?.getBooleanExtra("IS_BLACK", ThemeManager.isBlackSide(this)) ?: ThemeManager.isBlackSide(this)
        thinkTimeMs = intent?.getIntExtra("TIME_MS", ThemeManager.getTimeMs(this)) ?: ThemeManager.getTimeMs(this)
        antiBanEnabled = intent?.getBooleanExtra("ANTI_BAN", ThemeManager.isAntiBan(this)) ?: ThemeManager.isAntiBan(this)
        autoMoveEnabled = intent?.getBooleanExtra("AUTO_MOVE", ThemeManager.isAutoMove(this)) ?: ThemeManager.isAutoMove(this)
        val alphaVal = intent?.getIntExtra("ALPHA", ThemeManager.getAlpha(this)) ?: ThemeManager.getAlpha(this)
        val resCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        stockfish = StockfishEngine(this)
        usingStockfish = stockfish?.isAvailable() == true
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenW = bounds.width()
            screenH = bounds.height()
            screenDpi = resources.displayMetrics.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
            screenDpi = metrics.densityDpi
        }

        if (resCode == Activity.RESULT_OK && resData != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resCode, resData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    isScanning = false
                    stopSelf()
                }
            }, handler)
            setupImageReader()
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val prefs = getSharedPreferences("spoox_chess_prefs", Context.MODE_PRIVATE)
        val defaultSize = screenW.toFloat()
        val defaultTop = (screenH - defaultSize) / 2f

        val savedRect = RectF(
            prefs.getFloat("b_left", 0f),
            prefs.getFloat("b_top", defaultTop),
            prefs.getFloat("b_right", defaultSize),
            prefs.getFloat("b_bottom", defaultTop + defaultSize)
        )

        val aView = BoardOverlayView(this, pal.arrowMain, pal.arrowOutline).apply {
            isFlipped = isBlackSide
            boardRect = savedRect
            onRectChanged = { r ->
                prefs.edit()
                    .putFloat("b_left", r.left)
                    .putFloat("b_top", r.top)
                    .putFloat("b_right", r.right)
                    .putFloat("b_bottom", r.bottom)
                    .apply()
            }
        }
        arrowView = aView

        arrowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(aView, arrowParams)

        val panelW = (screenW * 0.93).toInt()
        val panelParams = WindowManager.LayoutParams(
            panelW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - panelW) / 2
            y = dp(30)
        }

        val pLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpf(26f)
                setColor(ThemeManager.withAlpha(pal.surface, alphaVal))
                setStroke(dp(1), pal.outline)
            }
            setPadding(dp(13), dp(10), dp(13), dp(10))
            alpha = 0f
            scaleX = 0.90f
            scaleY = 0.90f
        }
        panelLayout = pLayout

        // Row 1: Main controls
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val sDot = StatusDotView(this, pal.primary, pal.outline).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(7) }
        }
        statusDot = sDot

        val dragTitle = TextView(this).apply {
            text = "Spoox"
            setTextColor(pal.textPrimary)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val runBtn = TextView(this).apply {
            text = Strings.ovStart(this@OverlayService)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(pal.onPrimary)
            background = ThemeManager.ripple(pal.primary, null, dpf(20f), 0, false)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(5) }
            addTapFeel(this)

            setOnClickListener {
                if (!isScanning) {
                    isScanning = true
                    text = Strings.ovPause(this@OverlayService)
                    setTextColor(pal.onSecondaryContainer)
                    background = ThemeManager.ripple(pal.secondaryContainer, pal.outline, dpf(20f), dp(1), true)
                    statusDot?.setState(StatusDotView.ACTIVE)
                    if (!hasStartedGame) {
                        hasStartedGame = true
                        startNewGameAndCalibrate()
                    } else {
                        candidateSig = ""
                        confirmedSig = ""
                        updateBanner(Strings.ovResumed(this@OverlayService), "")
                        startScanLoop()
                    }
                } else {
                    isScanning = false
                    cancelPendingMove()
                    stockfish?.stop()
                    fallbackEngine.stop()
                    evalBar?.setThinking(false)
                    text = Strings.ovStart(this@OverlayService)
                    setTextColor(pal.onPrimary)
                    background = ThemeManager.ripple(pal.primary, null, dpf(20f), 0, false)
                    statusDot?.setState(StatusDotView.IDLE)
                    updateBanner(Strings.ovPaused(this@OverlayService), "")
                }
            }
        }

        val newBtn = TextView(this).apply {
            text = "↺"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.textPrimary)
            background = ThemeManager.ripple(pal.surfaceContainer, pal.outline, dpf(20f), dp(1), true)
            setPadding(dp(10), dp(4), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(5) }
            addTapFeel(this)

            setOnClickListener {
                isScanning = true
                hasStartedGame = true
                runBtn.text = Strings.ovPause(this@OverlayService)
                runBtn.setTextColor(pal.onSecondaryContainer)
                runBtn.background = ThemeManager.ripple(pal.secondaryContainer, pal.outline, dpf(20f), dp(1), true)
                statusDot?.setState(StatusDotView.ACTIVE)
                startNewGameAndCalibrate()
            }
        }

        val sideBtn = TextView(this).apply {
            text = if (isBlackSide) Strings.ovSideB(this@OverlayService) else Strings.ovSideW(this@OverlayService)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(pal.textPrimary)
            background = ThemeManager.ripple(pal.surfaceContainer, pal.outline, dpf(20f), dp(1), true)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(5) }
            addTapFeel(this)

            setOnClickListener {
                isBlackSide = !isBlackSide
                ThemeManager.setBlackSide(this@OverlayService, isBlackSide)
                text = if (isBlackSide) Strings.ovSideB(this@OverlayService) else Strings.ovSideW(this@OverlayService)
                arrowView?.isFlipped = isBlackSide
                arrowView?.invalidate()
                val s = if (isBlackSide) "b" else "w"
                webView?.evaluateJavascript("if(window.setOverlaySide) window.setOverlaySide('$s');", null)
            }
        }

        val calibBtn = TextView(this).apply {
            text = "⊞"
            textSize = 13.5f
            setTextColor(pal.textPrimary)
            background = ThemeManager.ripple(pal.surfaceContainer, pal.outline, dpf(20f), dp(1), true)
            setPadding(dp(9), dp(4), dp(9), dp(5))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(5) }
            addTapFeel(this)

            setOnClickListener {
                isCalibrating = !isCalibrating
                arrowView?.calibrationMode = isCalibrating
                text = if (isCalibrating) "✓" else "⊞"
                background = ThemeManager.ripple(if (isCalibrating) pal.primary else pal.surfaceContainer, if (isCalibrating) null else pal.outline, dpf(20f), dp(1), !isCalibrating)
                setTextColor(if (isCalibrating) pal.onPrimary else pal.textPrimary)
                arrowParams.flags = if (isCalibrating) {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                } else {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                }
                arrowView?.let { windowManager.updateViewLayout(it, arrowParams) }
                arrowView?.invalidate()
            }
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 11.5f
            setTextColor(pal.textSecondary)
            background = ThemeManager.ripple(pal.surfaceContainer, pal.outline, dpf(20f), dp(1), true)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            addTapFeel(this)
            setOnClickListener { stopSelf() }
        }

        topBar.addView(sDot)
        topBar.addView(dragTitle)
        topBar.addView(runBtn)
        topBar.addView(newBtn)
        topBar.addView(sideBtn)
        topBar.addView(calibBtn)
        topBar.addView(closeBtn)

        // Row 2: Live Mode Pills (AUTO + AB) + Move Banner
        val bannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeManager.shape(pal.surfaceContainer, pal.outline, dpf(18f), dp(1))
            setPadding(dp(8), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        lateinit var autoBtn: TextView
        fun refreshAutoBtn() {
            val active = autoMoveEnabled && AutoClickService.isEnabled()
            autoBtn.background = ThemeManager.ripple(if (active) pal.primary else pal.surfaceHigh, null, dpf(14f), 0, !active)
            autoBtn.setTextColor(if (active) pal.onPrimary else pal.textSecondary)
        }

        autoBtn = TextView(this).apply {
            text = Strings.ovAuto(this@OverlayService)
            textSize = 10.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(5) }
            addTapFeel(this)
            setOnClickListener {
                if (!AutoClickService.isEnabled()) {
                    autoMoveEnabled = false
                    refreshAutoBtn()
                    Toast.makeText(this@OverlayService, Strings.accToast(this@OverlayService), Toast.LENGTH_LONG).show()
                    val intentAcc = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    startActivity(intentAcc)
                } else {
                    autoMoveEnabled = !autoMoveEnabled
                    ThemeManager.setAutoMove(this@OverlayService, autoMoveEnabled)
                    refreshAutoBtn()
                    updateBanner(if (autoMoveEnabled) Strings.ovAutoOn(this@OverlayService) else Strings.ovAutoOff(this@OverlayService), "")
                }
            }
        }
        refreshAutoBtn()

        lateinit var abBtn: TextView
        fun refreshAbBtn() {
            abBtn.background = ThemeManager.ripple(if (antiBanEnabled) pal.primary else pal.surfaceHigh, null, dpf(14f), 0, !antiBanEnabled)
            abBtn.setTextColor(if (antiBanEnabled) pal.onPrimary else pal.textSecondary)
        }

        abBtn = TextView(this).apply {
            text = Strings.ovAb(this@OverlayService)
            textSize = 10.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            addTapFeel(this)
            setOnClickListener {
                antiBanEnabled = !antiBanEnabled
                ThemeManager.setAntiBan(this@OverlayService, antiBanEnabled)
                refreshAbBtn()
                updateBanner(if (antiBanEnabled) Strings.ovAbOn(this@OverlayService) else Strings.ovAbOff(this@OverlayService), "")
            }
        }
        refreshAbBtn()

        val mText = TextView(this).apply {
            text = Strings.ovPressStart(this@OverlayService, usingStockfish)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(pal.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        moveMainText = mText

        val eBadge = TextView(this).apply {
            text = "0.0"
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(pal.onSecondaryContainer)
            background = ThemeManager.shape(pal.secondaryContainer, null, dpf(12f))
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        evalBadgeText = eBadge

        bannerRow.addView(autoBtn)
        bannerRow.addView(abBtn)
        bannerRow.addView(mText)
        bannerRow.addView(eBadge)

        val eBar = EvalBarView(this, pal.surfaceHigh, pal.primary).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
        }
        evalBar = eBar

        val wView = WebView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(1, 1)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            addJavascriptInterface(JSBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val s = if (isBlackSide) "b" else "w"
                    evaluateJavascript("if(window.resetFromOverlay) window.resetFromOverlay('$s', $thinkTimeMs);", null)
                }
            }
            loadUrl("file:///android_asset/index.html")
        }
        webView = wView

        pLayout.addView(topBar)
        pLayout.addView(bannerRow)
        pLayout.addView(eBar)
        pLayout.addView(wView)

        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
        topBar.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = panelParams.x; initY = panelParams.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = initX + (e.rawX - touchX).toInt()
                    panelParams.y = initY + (e.rawY - touchY).toInt()
                    panelLayout?.let { windowManager.updateViewLayout(it, panelParams) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val targetX = (screenW - panelW) / 2
                    if (abs(panelParams.x - targetX) < dp(34)) {
                        ValueAnimator.ofInt(panelParams.x, targetX).apply {
                            duration = 210
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { va ->
                                panelParams.x = va.animatedValue as Int
                                panelLayout?.let { try { windowManager.updateViewLayout(it, panelParams) } catch (_: Exception) {} }
                            }
                            start()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(pLayout, panelParams)
        pLayout.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(DecelerateInterpolator(1.8f)).start()
        return START_NOT_STICKY
    }

    private fun cancelPendingMove() {
        pendingMoveTask?.let { handler.removeCallbacks(it) }
        pendingMoveTask = null
    }

    private fun updateBanner(mainTxt: String, badgeTxt: String) {
        handler.post {
            moveMainText?.apply {
                if (text.toString() != mainTxt) {
                    alpha = 0.35f
                    text = mainTxt
                    animate().alpha(1f).setDuration(170).start()
                }
            }
            if (badgeTxt.isNotEmpty()) {
                evalBadgeText?.text = badgeTxt
            }
        }
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val planes = img.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val imgW = img.width
                val imgH = img.height
                val rowPadding = rowStride - pixelStride * imgW
                val bmp = Bitmap.createBitmap(imgW + rowPadding / pixelStride, imgH, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)
                synchronized(frameLock) {
                    latestFrame?.recycle()
                    latestFrame = bmp
                }
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SpooxChessCapture",
            screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
    }

    private fun startNewGameAndCalibrate() {
        cancelPendingMove()
        arrowView?.clearMove()
        evalBar?.setThinking(false)
        evalBar?.setEval("cp", 0)
        updateBanner(Strings.ovSearchingBoard(this), "0.0")
        val ok = tryDetectBoardAndCalibrate()
        pendingAutoDetect = !ok
        val sideStr = if (isBlackSide) "b" else "w"
        candidateSig = ""
        confirmedSig = ""
        webView?.evaluateJavascript("if(window.resetFromOverlay) window.resetFromOverlay('$sideStr', $thinkTimeMs);", null)
        startScanLoop()
    }

    private fun tryDetectBoardAndCalibrate(): Boolean {
        val bmpCopy: Bitmap = synchronized(frameLock) {
            latestFrame?.let { if (!it.isRecycled) it.copy(Bitmap.Config.ARGB_8888, false) else null }
        } ?: return false

        val detected = autoDetectBoardRect(bmpCopy)
        if (detected != null) {
            arrowView?.boardRect = detected
            arrowView?.flashDetectFrame()
            getSharedPreferences("spoox_chess_prefs", Context.MODE_PRIVATE).edit()
                .putFloat("b_left", detected.left)
                .putFloat("b_top", detected.top)
                .putFloat("b_right", detected.right)
                .putFloat("b_bottom", detected.bottom)
                .apply()
            calibrateThemeAndTemplates(bmpCopy, detected)
            updateBanner(Strings.ovBoardFound(this), "0.0")
        } else {
            arrowView?.flashDetectFrame()
            updateBanner(Strings.ovCheckGrid(this), "—")
        }
        bmpCopy.recycle()
        return true
    }

    /**
     * 16x16 бинарная маска силуэта фигуры (256 значений):
     * чётко отличает форму пешки (узкая и низкая) от ладьи (широкая и высокая) на любой клетке.
     */
    private fun sampleSilhouette16x16(bmp: Bitmap, rect: RectF, vr: Int, vc: Int): FloatArray {
        val cell = rect.width() / 8f
        val left = rect.left + vc * cell
        val top = rect.top + vr * cell

        val bgX = (left + 0.88f * cell).toInt().coerceIn(0, bmp.width - 1)
        val bgY = (top + 0.12f * cell).toInt().coerceIn(0, bmp.height - 1)
        val bgPx = bmp.getPixel(bgX, bgY)
        val bgR = Color.red(bgPx); val bgG = Color.green(bgPx); val bgB = Color.blue(bgPx)

        val out = FloatArray(256)
        var norm = 0f
        for (y in 0 until 16) {
            val py = (top + (0.12f + y * 0.052f) * cell).toInt().coerceIn(0, bmp.height - 1)
            for (x in 0 until 16) {
                val px = (left + (0.12f + x * 0.052f) * cell).toInt().coerceIn(0, bmp.width - 1)
                val c = bmp.getPixel(px, py)
                val d = abs(bgR - Color.red(c)) + abs(bgG - Color.green(c)) + abs(bgB - Color.blue(c))
                val v = if (d >= 42) 1f else 0f
                out[y * 16 + x] = v
                norm += v * v
            }
        }
        val inv = if (norm > 1f) 1f / sqrt(norm) else 0f
        for (i in 0 until 256) out[i] *= inv
        return out
    }

    private fun measureSquarePieceLum(bmp: Bitmap, rect: RectF, vr: Int, vc: Int): Float {
        val cell = rect.width() / 8f
        val left = rect.left + vc * cell
        val top = rect.top + vr * cell
        val bgX = (left + 0.88f * cell).toInt().coerceIn(0, bmp.width - 1)
        val bgY = (top + 0.12f * cell).toInt().coerceIn(0, bmp.height - 1)
        val bgPx = bmp.getPixel(bgX, bgY)
        val bgR = Color.red(bgPx); val bgG = Color.green(bgPx); val bgB = Color.blue(bgPx)

        val ptsX = floatArrayOf(0.50f, 0.50f, 0.50f, 0.40f, 0.60f, 0.42f, 0.58f)
        val ptsY = floatArrayOf(0.50f, 0.36f, 0.64f, 0.52f, 0.52f, 0.40f, 0.62f)
        val lums = FloatArray(ptsX.size)
        var cnt = 0
        for (k in ptsX.indices) {
            val px = bmp.getPixel(
                (left + ptsX[k] * cell).toInt().coerceIn(0, bmp.width - 1),
                (top + ptsY[k] * cell).toInt().coerceIn(0, bmp.height - 1)
            )
            val d = abs(bgR - Color.red(px)) + abs(bgG - Color.green(px)) + abs(bgB - Color.blue(px))
            if (d >= 42) lums[cnt++] = luminance(px)
        }
        if (cnt < 2) return -1f
        lums.sort(0, cnt)
        return lums[(cnt * 3) / 4]
    }

    private fun calibrateThemeAndTemplates(bmp: Bitmap, rect: RectF) {
        var wLumSum = 0f; var wCnt = 0
        var bLumSum = 0f; var bCnt = 0
        for (c in 0 until 8) {
            for (r in intArrayOf(6, 7)) {
                val vr = if (isBlackSide) 7 - r else r
                val vc = if (isBlackSide) 7 - c else c
                val l = measureSquarePieceLum(bmp, rect, vr, vc)
                if (l > 0f) { wLumSum += l; wCnt++ }
            }
            for (r in intArrayOf(0, 1)) {
                val vr = if (isBlackSide) 7 - r else r
                val vc = if (isBlackSide) 7 - c else c
                val l = measureSquarePieceLum(bmp, rect, vr, vc)
                if (l > 0f) { bLumSum += l; bCnt++ }
            }
        }
        if (wCnt >= 6 && bCnt >= 6) {
            val avgW = wLumSum / wCnt
            val avgB = bLumSum / bCnt
            if (avgW - avgB > 40f) pieceLumThreshold = (avgW + avgB) / 2f
        }

        val startGroups = mapOf(
            'r' to listOf(Pair(0, 0), Pair(0, 7)),
            'n' to listOf(Pair(0, 1), Pair(0, 6)),
            'b' to listOf(Pair(0, 2), Pair(0, 5)),
            'q' to listOf(Pair(0, 3)),
            'k' to listOf(Pair(0, 4)),
            'p' to (0..7).map { Pair(1, it) },
            'R' to listOf(Pair(7, 0), Pair(7, 7)),
            'N' to listOf(Pair(7, 1), Pair(7, 6)),
            'B' to listOf(Pair(7, 2), Pair(7, 5)),
            'Q' to listOf(Pair(7, 3)),
            'K' to listOf(Pair(7, 4)),
            'P' to (0..7).map { Pair(6, it) }
        )
        for ((ch, squares) in startGroups) {
            val avg = FloatArray(256)
            for (pos in squares) {
                val vr = if (isBlackSide) 7 - pos.first else pos.first
                val vc = if (isBlackSide) 7 - pos.second else pos.second
                val vec = sampleSilhouette16x16(bmp, rect, vr, vc)
                for (i in 0 until 256) avg[i] += vec[i]
            }
            var norm = 0f
            for (i in 0 until 256) norm += avg[i] * avg[i]
            val inv = if (norm > 1e-4f) 1f / sqrt(norm) else 0f
            for (i in 0 until 256) avg[i] *= inv
            pieceSilhouettes[ch] = avg
        }
    }

    private fun buildFenFromTemplates(bmp: Bitmap, rect: RectF, grid: IntArray): String {
        if (pieceSilhouettes.size < 12) return ""
        val boardChars = CharArray(64) { '.' }

        var bestWK = -1; var bestWKScore = -999f
        var bestBK = -1; var bestBKScore = -999f

        val nonKingW = charArrayOf('P', 'N', 'B', 'R', 'Q')
        val nonKingB = charArrayOf('p', 'n', 'b', 'r', 'q')

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val sq = r * 8 + c
                val sign = grid[sq]
                if (sign == 0) continue
                val vr = if (isBlackSide) 7 - r else r
                val vc = if (isBlackSide) 7 - c else c
                val feat = sampleSilhouette16x16(bmp, rect, vr, vc)

                val kingCh = if (sign == 1) 'K' else 'k'
                val kTpl = pieceSilhouettes[kingCh]
                if (kTpl != null) {
                    var kDot = 0f
                    for (i in 0 until 256) kDot += feat[i] * kTpl[i]
                    if (sign == 1 && kDot > bestWKScore) { bestWKScore = kDot; bestWK = sq }
                    if (sign == -1 && kDot > bestBKScore) { bestBKScore = kDot; bestBK = sq }
                }

                val candidates = if (sign == 1) nonKingW else nonKingB
                var bestCh = if (sign == 1) 'Q' else 'q'
                var bestDot = -999f
                for (ch in candidates) {
                    if ((ch == 'P' || ch == 'p') && (r == 0 || r == 7)) continue
                    val tpl = pieceSilhouettes[ch] ?: continue
                    var dot = 0f
                    for (i in 0 until 256) dot += feat[i] * tpl[i]
                    if (dot > bestDot) { bestDot = dot; bestCh = ch }
                }
                boardChars[sq] = bestCh
            }
        }

        if (bestWK == -1 || bestBK == -1) return ""
        boardChars[bestWK] = 'K'
        boardChars[bestBK] = 'k'

        val sb = StringBuilder()
        for (r in 0 until 8) {
            var empty = 0
            for (c in 0 until 8) {
                val ch = boardChars[r * 8 + c]
                if (ch == '.') empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < 7) sb.append('/')
        }

        val castling = StringBuilder()
        if (boardChars[60] == 'K' && boardChars[63] == 'R') castling.append('K')
        if (boardChars[60] == 'K' && boardChars[56] == 'R') castling.append('Q')
        if (boardChars[4] == 'k' && boardChars[7] == 'r') castling.append('k')
        if (boardChars[4] == 'k' && boardChars[0] == 'r') castling.append('q')
        val cStr = if (castling.isEmpty()) "-" else castling.toString()

        val sideChar = if (isBlackSide) "b" else "w"
        return "$sb $sideChar $cStr - 0 1"
    }

    private fun autoDetectBoardRect(bmp: Bitmap): RectF? {
        val w = min(bmp.width, screenW)
        val h = min(bmp.height, screenH)
        if (w < 200 || h < 200) return null

        var bestScore = -1.0
        var bestRect: RectF? = null
        val margins = intArrayOf(0, (w * 0.015f).toInt(), (w * 0.03f).toInt(), (w * 0.05f).toInt())

        for (margin in margins) {
            val size = w - margin * 2
            if (size < 200 || size >= h) continue
            val minTop = (h * 0.08f).toInt()
            val maxTop = min(h - size - 8, (h * 0.55f).toInt())
            var top = minTop
            while (top <= maxTop) {
                val sc = scoreBoardCandidate(bmp, margin, top, size)
                if (sc > bestScore) {
                    bestScore = sc
                    bestRect = RectF(margin.toFloat(), top.toFloat(), (margin + size).toFloat(), (top + size).toFloat())
                }
                top += 14
            }
        }

        val coarse = bestRect ?: return null
        val cLeft = coarse.left.toInt()
        val cSize = coarse.width().toInt()
        val refineStart = max(0, coarse.top.toInt() - 16)
        val refineEnd = min(h - cSize - 1, coarse.top.toInt() + 16)
        var top = refineStart
        while (top <= refineEnd) {
            val sc = scoreBoardCandidate(bmp, cLeft, top, cSize)
            if (sc > bestScore) {
                bestScore = sc
                bestRect = RectF(cLeft.toFloat(), top.toFloat(), (cLeft + cSize).toFloat(), (top + cSize).toFloat())
            }
            top += 2
        }

        return if (bestScore > 25.0) bestRect else null
    }

    private fun scoreBoardCandidate(bmp: Bitmap, left: Int, top: Int, size: Int): Double {
        val cell = size / 8f
        var lR = 0L; var lG = 0L; var lB = 0L; var lCnt = 0
        var dR = 0L; var dG = 0L; var dB = 0L; var dCnt = 0
        val pxColors = IntArray(64)

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val sx = (left + (c + 0.87f) * cell).toInt().coerceIn(0, bmp.width - 1)
                val sy = (top + (r + 0.12f) * cell).toInt().coerceIn(0, bmp.height - 1)
                val px = bmp.getPixel(sx, sy)
                pxColors[r * 8 + c] = px
                if (((r + c) and 1) == 0) {
                    lR += Color.red(px); lG += Color.green(px); lB += Color.blue(px); lCnt++
                } else {
                    dR += Color.red(px); dG += Color.green(px); dB += Color.blue(px); dCnt++
                }
            }
        }

        val mlR = (lR / lCnt).toInt(); val mlG = (lG / lCnt).toInt(); val mlB = (lB / lCnt).toInt()
        val mdR = (dR / dCnt).toInt(); val mdG = (dG / dCnt).toInt(); val mdB = (dB / dCnt).toInt()
        val contrast = abs(mlR - mdR) + abs(mlG - mdG) + abs(mlB - mdB)
        if (contrast < 55) return -1.0

        var variance = 0.0
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val px = pxColors[r * 8 + c]
                val pr = Color.red(px); val pg = Color.green(px); val pb = Color.blue(px)
                val d = if (((r + c) and 1) == 0) {
                    abs(pr - mlR) + abs(pg - mlG) + abs(pb - mlB)
                } else {
                    abs(pr - mdR) + abs(pg - mdG) + abs(pb - mdB)
                }
                variance += min(d, 120)
            }
        }
        return contrast - (variance / 64.0) * 2.2
    }

    private fun startScanLoop() {
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 180)
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isScanning) return
            if (pendingAutoDetect && tryDetectBoardAndCalibrate()) pendingAutoDetect = false
            processLatestFrame()
            handler.postDelayed(this, 180)
        }
    }

    private fun processLatestFrame() {
        if (System.currentTimeMillis() < autoClickLockUntilMs) return
        val bmp: Bitmap = synchronized(frameLock) {
            latestFrame?.let { if (!it.isRecycled) it.copy(Bitmap.Config.ARGB_8888, false) else null }
        } ?: return

        try {
            val av = arrowView ?: return
            val r = av.boardRect
            val imgW = bmp.width
            val imgH = bmp.height
            val left = r.left.toInt().coerceIn(0, imgW - 8)
            val top = r.top.toInt().coerceIn(0, imgH - 8)
            val size = r.width().toInt().coerceIn(8, min(imgW - left, imgH - top))

            val cellSize = size / 8f
            val grid = IntArray(64)

            // 12 точек (кольцо + центр): маленькая серая точка возможного хода Chess.com даёт максимум 1 попадание и отсекается (hits < 3)
            val ptsX = floatArrayOf(0.50f, 0.50f, 0.50f, 0.36f, 0.64f, 0.40f, 0.60f, 0.40f, 0.60f, 0.34f, 0.66f, 0.50f)
            val ptsY = floatArrayOf(0.50f, 0.34f, 0.68f, 0.52f, 0.52f, 0.38f, 0.38f, 0.64f, 0.64f, 0.72f, 0.72f, 0.78f)
            val hitLums = FloatArray(ptsX.size)

            for (vr in 0 until 8) {
                for (vc in 0 until 8) {
                    val boardR = if (isBlackSide) 7 - vr else vr
                    val boardC = if (isBlackSide) 7 - vc else vc
                    val sqIdx = boardR * 8 + boardC

                    val bgX = (left + (vc + 0.88f) * cellSize).toInt().coerceIn(0, imgW - 1)
                    val bgY = (top + (vr + 0.12f) * cellSize).toInt().coerceIn(0, imgH - 1)
                    val bgPx = bmp.getPixel(bgX, bgY)
                    val bgR = Color.red(bgPx); val bgG = Color.green(bgPx); val bgB = Color.blue(bgPx)

                    var hits = 0
                    for (k in ptsX.indices) {
                        val pxX = (left + (vc + ptsX[k]) * cellSize).toInt().coerceIn(0, imgW - 1)
                        val pxY = (top + (vr + ptsY[k]) * cellSize).toInt().coerceIn(0, imgH - 1)
                        val px = bmp.getPixel(pxX, pxY)
                        val diff = abs(bgR - Color.red(px)) + abs(bgG - Color.green(px)) + abs(bgB - Color.blue(px))
                        if (diff >= 40) hitLums[hits++] = luminance(px)
                    }

                    if (hits < 3) {
                        grid[sqIdx] = 0
                    } else {
                        hitLums.sort(0, hits)
                        val upperLum = hitLums[(hits * 3) / 4]
                        grid[sqIdx] = if (upperLum > pieceLumThreshold) 1 else -1
                    }
                }
            }

            val sig = grid.joinToString(",")
            if (sig == candidateSig) {
                if (sig != confirmedSig) {
                    confirmedSig = sig
                    val fallbackFen = buildFenFromTemplates(bmp, r, grid)
                    webView?.evaluateJavascript(
                        "if(window.onScreenGrid) window.onScreenGrid([$sig], '$fallbackFen');",
                        null
                    )
                }
            } else {
                candidateSig = sig
            }
        } catch (_: Exception) {
        } finally {
            bmp.recycle()
        }
    }

    private fun luminance(color: Int): Float {
        return 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)
    }

    private fun formatUciArrow(uci: String): String {
        if (uci.length < 4) return uci.uppercase(Locale.US)
        val from = uci.substring(0, 2).uppercase(Locale.US)
        val to = uci.substring(2, 4).uppercase(Locale.US)
        val promo = if (uci.length > 4) "=${uci.substring(4).uppercase(Locale.US)}" else ""
        return "$from → $to$promo"
    }

    inner class JSBridge {
        @JavascriptInterface
        fun drawMove(uci: String) {
            handler.post { arrowView?.setMove(uci) }
        }

        @JavascriptInterface
        fun clearMove() {
            handler.post {
                cancelPendingMove()
                arrowView?.clearMove()
                evalBar?.setThinking(false)
                updateBanner(Strings.ovOppTurn(this@OverlayService), "")
            }
        }

        @JavascriptInterface
        fun stopNativeEngine() {
            cancelPendingMove()
            stockfish?.stop()
            fallbackEngine.stop()
            handler.post { evalBar?.setThinking(false) }
        }

        @JavascriptInterface
        fun requestNativePosition(baseFen: String, movesCsv: String, timeMs: Int, reqId: Int) {
            cancelPendingMove()
            val moves = if (movesCsv.isBlank()) emptyList() else movesCsv.split(",")
            val fenOrNull = if (baseFen.isBlank()) null else baseFen

            handler.post {
                arrowView?.clearMove()
                statusDot?.setState(StatusDotView.THINKING)
                evalBar?.setThinking(true)
                updateBanner(Strings.ovThinking(this@OverlayService), "...")
            }

            fun deliverFinalMove(dp: Int, st: String, sv: Int, uci: String?, n: Long, ms: Long, nps: Long, wasHumanized: Boolean) {
                val uStr = uci ?: ""
                val humanPauseMs = if (antiBanEnabled && uci != null) (240L + rng.nextInt(680)) else 0L
                val task = Runnable {
                    statusDot?.setState(if (isScanning) StatusDotView.ACTIVE else StatusDotView.IDLE)
                    evalBar?.setThinking(false)
                    if (uci != null) {
                        arrowView?.setMove(uci)
                        evalBar?.setEval(st, sv)
                        val evalTxt = if (st == "mate") Strings.ovMate(this@OverlayService, abs(sv)) else String.format(Locale.US, "%+.1f", sv / 100f)
                        val abTag = if (wasHumanized) " [${Strings.ovAb(this@OverlayService)}]" else ""
                        updateBanner("${formatUciArrow(uci)}$abTag", evalTxt)

                        if (autoMoveEnabled && isScanning && !isCalibrating) {
                            val clicker = AutoClickService.instance
                            val rect = arrowView?.boardRect
                            if (clicker != null && rect != null) {
                                val clickDelay = if (antiBanEnabled) (120L + rng.nextInt(240)) else 80L
                                handler.postDelayed({
                                    if (isScanning && autoMoveEnabled && !isCalibrating) {
                                        autoClickLockUntilMs = System.currentTimeMillis() + 620L
                                        clicker.performMove(uci, rect, isBlackSide, antiBanEnabled) {
                                            autoClickLockUntilMs = System.currentTimeMillis() + 200L
                                        }
                                    }
                                }, clickDelay)
                            }
                        }
                    }
                    webView?.evaluateJavascript(
                        "if(window.onNativeEngineMsg) window.onNativeEngineMsg('end',$reqId,$dp,'$st',$sv,'$uStr',$n,$ms,$nps);",
                        null
                    )
                }
                pendingMoveTask = task
                if (humanPauseMs > 0L) handler.postDelayed(task, humanPauseMs) else handler.post(task)
            }

            if (usingStockfish) {
                stockfish?.searchPositionAsync(
                    baseFen = fenOrNull,
                    movesUci = moves,
                    timeMs = timeMs.toLong(),
                    antiBan = antiBanEnabled,
                    onInfo = { dp, st, sv, uci, ponder, n, ms, nps ->
                        handler.post {
                            moveMainText?.text = Strings.ovDepth(this@OverlayService, dp)
                            webView?.evaluateJavascript(
                                "if(window.onNativeEngineMsg) window.onNativeEngineMsg('inf',$reqId,$dp,'$st',$sv,'$uci',$n,$ms,$nps);",
                                null
                            )
                        }
                    },
                    onEnd = { dp, st, sv, uci, ponder, n, ms, nps, wasHumanized ->
                        deliverFinalMove(dp, st, sv, uci, n, ms, nps, wasHumanized)
                    }
                )
            } else {
                fallbackEngine.searchPositionAsync(
                    baseFen = fenOrNull,
                    movesUci = moves,
                    timeMs = timeMs.toLong(),
                    onInfo = { dp, st, sv, uci, n, ms, nps ->
                        handler.post {
                            moveMainText?.text = Strings.ovDepth(this@OverlayService, dp)
                            webView?.evaluateJavascript(
                                "if(window.onNativeEngineMsg) window.onNativeEngineMsg('inf',$reqId,$dp,'$st',$sv,'$uci',$n,$ms,$nps);",
                                null
                            )
                        }
                    },
                    onEnd = { dp, st, sv, uci, n, ms, nps ->
                        deliverFinalMove(dp, st, sv, uci, n, ms, nps, false)
                    }
                )
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "spoox_chess_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Spoox Chess", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Spoox Chess v2.1")
            .setContentText(Strings.ovNotif(this))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun cleanupResources() {
        isScanning = false
        pendingAutoDetect = false
        cancelPendingMove()
        stockfish?.stop()
        fallbackEngine.stop()
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
        panelLayout?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panelLayout = null
        arrowView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        arrowView = null
        stockfish?.destroy()
        stockfish = null
        webView?.destroy()
        webView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()
    }

    class StatusDotView(context: Context, private val activeColor: Int, private val idleColor: Int) : View(context) {
        companion object {
            const val IDLE = 0
            const val ACTIVE = 1
            const val THINKING = 2
        }
        private var state = IDLE
        private var pulse = 1f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var anim: ValueAnimator? = null

        fun setState(newState: Int) {
            state = newState
            anim?.cancel()
            if (state == THINKING) {
                anim = ValueAnimator.ofFloat(0.25f, 1f).apply {
                    duration = 420
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else {
                pulse = 1f
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = min(width, height) / 2f
            paint.color = when (state) {
                ACTIVE -> activeColor
                THINKING -> ThemeManager.withAlpha(activeColor, (pulse * 255).toInt())
                else -> idleColor
            }
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
        }
    }

    class EvalBarView(context: Context, trackColor: Int, fillColor: Int) : View(context) {
        private var ratio = 0.5f
        private var thinking = false
        private var wavePos = 0f
        private var evalAnim: ValueAnimator? = null
        private var thinkAnim: ValueAnimator? = null
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }

        fun setThinking(active: Boolean) {
            if (thinking == active) return
            thinking = active
            thinkAnim?.cancel()
            if (active) {
                thinkAnim = ValueAnimator.ofFloat(-0.35f, 1.35f).apply {
                    duration = 950
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { wavePos = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else {
                invalidate()
            }
        }

        fun setEval(st: String, sv: Int) {
            val target = if (st == "mate") {
                if (sv > 0) 0.98f else 0.02f
            } else {
                val c = sv.coerceIn(-1000, 1000)
                (0.5 + 0.5 * (2.0 / (1.0 + exp(-0.004 * c)) - 1.0)).toFloat().coerceIn(0.04f, 0.96f)
            }
            evalAnim?.cancel()
            evalAnim = ValueAnimator.ofFloat(ratio, target).apply {
                duration = 320
                interpolator = DecelerateInterpolator()
                addUpdateListener { ratio = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, bgPaint)
            if (thinking) {
                val segW = w * 0.32f
                val l = (wavePos * w - segW / 2f).coerceIn(0f, w)
                val r = (wavePos * w + segW / 2f).coerceIn(0f, w)
                if (r > l) canvas.drawRoundRect(l, 0f, r, h, h / 2f, h / 2f, fillPaint)
            } else {
                canvas.drawRoundRect(0f, 0f, w * ratio, h, h / 2f, h / 2f, fillPaint)
            }
        }
    }

    class BoardOverlayView(context: Context, accentColor: Int, outlineColor: Int) : View(context) {
        var isFlipped = false
        var calibrationMode = false
        var boardRect = RectF(0f, 500f, 1080f, 1580f)
        var onRectChanged: ((RectF) -> Unit)? = null

        private var flashUntilMs = 0L
        private var fromSq: Pair<Int, Int>? = null
        private var toSq: Pair<Int, Int>? = null
        private var lastUci = ""

        private var animProgress = 1f
        private var arrowAnim: ValueAnimator? = null

        private var dragMode = 0
        private var lastX = 0f
        private var lastY = 0f

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThemeManager.withAlpha(accentColor, 175)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }

        private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
        }

        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.FILL
        }

        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThemeManager.withAlpha(outlineColor, 215)
            style = Paint.Style.STROKE
            strokeWidth = 17f
            strokeCap = Paint.Cap.ROUND
        }

        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThemeManager.withAlpha(accentColor, 240)
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
        }

        private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThemeManager.withAlpha(accentColor, 248)
            style = Paint.Style.FILL
        }

        private val ringOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThemeManager.withAlpha(outlineColor, 195)
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThemeManager.withAlpha(accentColor, 235)
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        fun flashDetectFrame() {
            flashUntilMs = System.currentTimeMillis() + 1250L
            invalidate()
            postDelayed({ invalidate() }, 1300L)
        }

        fun clearMove() {
            arrowAnim?.cancel()
            fromSq = null
            toSq = null
            lastUci = ""
            invalidate()
        }

        fun setMove(uci: String) {
            if (uci.length < 4) return
            if (uci == lastUci) return
            lastUci = uci
            fromSq = parseSquare(uci.substring(0, 2))
            toSq = parseSquare(uci.substring(2, 4))

            arrowAnim?.cancel()
            arrowAnim = ValueAnimator.ofFloat(0.08f, 1f).apply {
                duration = 250
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener {
                    animProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun parseSquare(sq: String): Pair<Int, Int> {
            val col = sq[0] - 'a'
            val row = 8 - (sq[1] - '0')
            return if (!isFlipped) Pair(col, row) else Pair(7 - col, 7 - row)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!calibrationMode) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    val distCorner = hypot((event.rawX - boardRect.right).toDouble(), (event.rawY - boardRect.bottom).toDouble())
                    dragMode = when {
                        distCorner < 100 -> 2
                        boardRect.contains(event.rawX, event.rawY) -> 1
                        else -> 0
                    }
                    return dragMode != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    lastX = event.rawX
                    lastY = event.rawY
                    if (dragMode == 1) {
                        boardRect.offset(dx, dy)
                    } else if (dragMode == 2) {
                        val newSize = max(240f, boardRect.width() + dx)
                        boardRect.right = boardRect.left + newSize
                        boardRect.bottom = boardRect.top + newSize
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragMode != 0) onRectChanged?.invoke(boardRect)
                    dragMode = 0
                    return true
                }
            }
            return false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cellW = boardRect.width() / 8f
            val cellH = boardRect.height() / 8f

            val showGrid = calibrationMode || System.currentTimeMillis() < flashUntilMs
            if (showGrid) {
                canvas.drawRect(boardRect, gridPaint)
                for (i in 1..7) {
                    canvas.drawLine(boardRect.left + i * cellW, boardRect.top, boardRect.left + i * cellW, boardRect.bottom, gridPaint)
                    canvas.drawLine(boardRect.left, boardRect.top + i * cellH, boardRect.right, boardRect.top + i * cellH, gridPaint)
                }
                val bl = cellW * 0.45f
                canvas.drawLine(boardRect.left, boardRect.top, boardRect.left + bl, boardRect.top, bracketPaint)
                canvas.drawLine(boardRect.left, boardRect.top, boardRect.left, boardRect.top + bl, bracketPaint)
                canvas.drawLine(boardRect.right, boardRect.top, boardRect.right - bl, boardRect.top, bracketPaint)
                canvas.drawLine(boardRect.right, boardRect.top, boardRect.right, boardRect.top + bl, bracketPaint)
                canvas.drawLine(boardRect.left, boardRect.bottom, boardRect.left + bl, boardRect.bottom, bracketPaint)
                canvas.drawLine(boardRect.left, boardRect.bottom, boardRect.left, boardRect.bottom - bl, bracketPaint)
                if (calibrationMode) {
                    canvas.drawCircle(boardRect.right, boardRect.bottom, 28f, handlePaint)
                }
            }

            val from = fromSq ?: return
            val to = toSq ?: return

            val cx1 = boardRect.left + (from.first + 0.5f) * cellW
            val cy1 = boardRect.top + (from.second + 0.5f) * cellH
            val cx2 = boardRect.left + (to.first + 0.5f) * cellW
            val cy2 = boardRect.top + (to.second + 0.5f) * cellH

            val ringR = cellW * 0.42f
            canvas.drawCircle(cx1, cy1, ringR, ringOutlinePaint)
            canvas.drawCircle(cx1, cy1, ringR, ringPaint)

            if (animProgress > 0.50f) {
                val tAlpha = ((animProgress - 0.50f) / 0.50f * 235f).toInt().coerceIn(0, 235)
                ringOutlinePaint.alpha = (tAlpha * 0.8f).toInt()
                ringPaint.alpha = tAlpha
                val scale = 0.84f + 0.16f * animProgress
                canvas.drawCircle(cx2, cy2, ringR * scale, ringOutlinePaint)
                canvas.drawCircle(cx2, cy2, ringR * scale, ringPaint)
                ringOutlinePaint.alpha = 195
                ringPaint.alpha = 235
            }

            val dist = hypot((cx2 - cx1).toDouble(), (cy2 - cy1).toDouble()).toFloat()
            if (dist > ringR * 1.9f) {
                val angle = atan2((cy2 - cy1).toDouble(), (cx2 - cx1).toDouble())
                val sx = (cx1 + cos(angle) * ringR).toFloat()
                val sy = (cy1 + sin(angle) * ringR).toFloat()
                val tx = (cx2 - cos(angle) * ringR).toFloat()
                val ty = (cy2 - sin(angle) * ringR).toFloat()

                val curEx = sx + (tx - sx) * animProgress
                val curEy = sy + (ty - sy) * animProgress
                val headLen = cellW * 0.28f * animProgress
                val lineEndX = (curEx - cos(angle) * headLen * 0.65).toFloat()
                val lineEndY = (curEy - sin(angle) * headLen * 0.65).toFloat()

                canvas.drawLine(sx, sy, lineEndX, lineEndY, outlinePaint)
                canvas.drawLine(sx, sy, lineEndX, lineEndY, arrowPaint)

                val path = Path().apply {
                    moveTo(curEx, curEy)
                    lineTo((curEx - headLen * cos(angle - 0.52)).toFloat(), (curEy - headLen * sin(angle - 0.52)).toFloat())
                    lineTo((curEx - headLen * cos(angle + 0.52)).toFloat(), (curEy - headLen * sin(angle + 0.52)).toFloat())
                    close()
                }
                canvas.drawPath(path, headPaint)
            }
        }
    }
}
