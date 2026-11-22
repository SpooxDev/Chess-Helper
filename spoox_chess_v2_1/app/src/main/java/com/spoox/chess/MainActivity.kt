package com.spoox.chess

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var currentTab = 0 // 0 = Play, 1 = Engine, 2 = Style

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
                putExtra("IS_BLACK", ThemeManager.isBlackSide(this@MainActivity))
                putExtra("TIME_MS", ThemeManager.getTimeMs(this@MainActivity))
                putExtra("ANTI_BAN", ThemeManager.isAntiBan(this@MainActivity))
                putExtra("AUTO_MOVE", ThemeManager.isAutoMove(this@MainActivity))
                putExtra("ALPHA", ThemeManager.getAlpha(this@MainActivity))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            moveTaskToBack(true)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    private fun addBounce(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(85).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator(1.6f)).start()
            }
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        renderScreen(animateTab = true)
    }

    override fun onResume() {
        super.onResume()
        renderScreen(animateTab = false)
    }

    private fun renderScreen(animateTab: Boolean) {
        val pal = ThemeManager.getPalette(this)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg

        val outer = FrameLayout(this).apply {
            setBackgroundColor(pal.bg)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val contentCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(42), dp(20), dp(104))
        }

        val tabViews: List<View> = when (currentTab) {
            0 -> buildPlayTab(pal)
            1 -> buildEngineTab(pal)
            else -> buildStyleTab(pal)
        }

        tabViews.forEachIndexed { idx, v ->
            contentCol.addView(v)
            if (animateTab) {
                v.alpha = 0f
                v.translationY = dp(12).toFloat()
                v.animate().alpha(1f).translationY(0f).setStartDelay(idx * 35L).setDuration(230)
                    .setInterpolator(DecelerateInterpolator(1.7f)).start()
            }
        }

        scroll.addView(contentCol)

        // === Floating Material You Bottom Navigation Bar (3 Categories) ===
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeManager.shape(pal.surface, pal.outline, dpf(30f), dp(1))
            elevation = dp(10).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(62),
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(18)
            }
        }

        val tabs = listOf(
            0 to Strings.tabPlay(this),
            1 to Strings.tabEngine(this),
            2 to Strings.tabStyle(this)
        )

        tabs.forEach { (tabIdx, label) ->
            val active = tabIdx == currentTab
            val tabBtn = TextView(this).apply {
                text = label
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(if (active) pal.onPrimary else pal.textSecondary)
                background = if (active) ThemeManager.shape(pal.primary, null, dpf(24f)) else null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                addBounce(this)
                setOnClickListener {
                    if (currentTab != tabIdx) {
                        currentTab = tabIdx
                        renderScreen(animateTab = true)
                    }
                }
            }
            navBar.addView(tabBtn)
        }

        outer.addView(scroll)
        outer.addView(navBar)
        setContentView(outer)
    }

    // =========================================================================
    // TAB 1: PLAY (Hero + Side + Quick Status + Launch / Stop)
    // =========================================================================
    private fun buildPlayTab(pal: AppPalette): List<View> {
        val hasSf = File(applicationInfo.nativeLibraryDir, "libstockfish.so").exists()
        val isBlack = ThemeManager.isBlackSide(this)
        val timeMs = ThemeManager.getTimeMs(this)
        val antiBan = ThemeManager.isAntiBan(this)
        val autoMove = ThemeManager.isAutoMove(this)

        // Hero Card
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeManager.shape(pal.surface, pal.outline, dpf(28f), dp(1))
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
        }
        val scallop = ScallopBadgeView(this, pal.primary, pal.onPrimary, "2.1").apply {
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(16) }
            addBounce(this)
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "Spoox Chess"
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(pal.textPrimary)
        })
        titleCol.addView(TextView(this).apply {
            text = Strings.appSubtitle(this@MainActivity)
            textSize = 12.5f
            setTextColor(pal.textSecondary)
        })
        val engineChip = TextView(this).apply {
            text = if (hasSf) "SF NNUE" else "NATIVE"
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(pal.onSecondaryContainer)
            background = ThemeManager.shape(pal.secondaryContainer, null, dpf(18f))
            setPadding(dp(13), dp(7), dp(13), dp(7))
        }
        heroCard.addView(scallop)
        heroCard.addView(titleCol)
        heroCard.addView(engineChip)

        // Side Selector Card
        val sideCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.shape(pal.surface, pal.outline, dpf(28f), dp(1))
            setPadding(dp(18), dp(16), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
        }
        sideCard.addView(TextView(this).apply {
            text = Strings.sideLabel(this@MainActivity)
            textSize = 13f
            setTextColor(pal.textSecondary)
            setPadding(dp(2), 0, 0, dp(12))
        })

        val segContainer = FrameLayout(this).apply {
            background = ThemeManager.shape(pal.surfaceContainer, null, dpf(26f))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        }
        val slider = View(this).apply {
            background = ThemeManager.shape(pal.primary, null, dpf(22f))
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val segRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        lateinit var lblW: TextView
        lateinit var lblB: TextView
        fun selectSide(black: Boolean, anim: Boolean) {
            ThemeManager.setBlackSide(this@MainActivity, black)
            lblW.setTextColor(if (!black) pal.onPrimary else pal.textSecondary)
            lblB.setTextColor(if (black) pal.onPrimary else pal.textSecondary)
            val tx = if (black) slider.width.toFloat() else 0f
            if (anim) {
                ObjectAnimator.ofFloat(slider, "translationX", tx).apply {
                    duration = 240
                    interpolator = OvershootInterpolator(0.95f)
                    start()
                }
            } else {
                slider.translationX = tx
            }
        }
        lblW = TextView(this).apply {
            text = Strings.white(this@MainActivity)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { selectSide(false, true) }
        }
        lblB = TextView(this).apply {
            text = Strings.black(this@MainActivity)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { selectSide(true, true) }
        }
        segRow.addView(lblW)
        segRow.addView(lblB)
        segContainer.addView(slider)
        segContainer.addView(segRow)
        segContainer.post {
            val half = (segContainer.width - dp(8)) / 2
            slider.layoutParams = FrameLayout.LayoutParams(half, FrameLayout.LayoutParams.MATCH_PARENT)
            selectSide(isBlack, false)
        }
        sideCard.addView(segContainer)

        // Status summary pill
        val summaryPill = TextView(this).apply {
            text = Strings.statusSummary(this@MainActivity, timeMs, antiBan, autoMove)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(pal.textSecondary)
            background = ThemeManager.shape(pal.surfaceContainer, pal.outline, dpf(20f), dp(1))
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) }
        }

        // Launch Button
        val btnLaunch = TextView(this).apply {
            text = "▶   ${Strings.launchBtn(this@MainActivity)}"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(pal.onPrimary)
            background = ThemeManager.ripple(pal.primary, null, dpf(30f), 0, false)
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)).apply { bottomMargin = dp(12) }
            addBounce(this)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, Strings.overlayPermToast(this@MainActivity), Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            }
        }

        // Stop Button
        val btnStop = TextView(this).apply {
            text = "■   ${Strings.stopBtn(this@MainActivity)}"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(pal.onSecondaryContainer)
            background = ThemeManager.ripple(pal.secondaryContainer, pal.outline, dpf(28f), dp(1), true)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            addBounce(this)
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, Strings.serviceStoppedToast(this@MainActivity), Toast.LENGTH_SHORT).show()
            }
        }

        return listOf(heroCard, sideCard, summaryPill, btnLaunch, btnStop)
    }

    // =========================================================================
    // TAB 2: ENGINE (Think Time + Anti-Ban + Auto-Move & Accessibility)
    // =========================================================================
    private fun buildEngineTab(pal: AppPalette): List<View> {
        val timeMs = ThemeManager.getTimeMs(this)
        val antiBan = ThemeManager.isAntiBan(this)
        val autoMove = ThemeManager.isAutoMove(this)

        val header = TextView(this).apply {
            text = Strings.tabEngine(this@MainActivity)
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(pal.textPrimary)
            setPadding(dp(4), 0, 0, dp(16))
        }

        // Think Time Card
        val timeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.shape(pal.surface, pal.outline, dpf(28f), dp(1))
            setPadding(dp(18), dp(16), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        timeCard.addView(TextView(this).apply {
            text = Strings.thinkTimeLabel(this@MainActivity)
            textSize = 13.5f
            setTextColor(pal.textSecondary)
            setPadding(dp(2), 0, 0, dp(12))
        })

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
        }
        val presets = listOf(500 to "0.5s", 1500 to "1.5s", 3000 to "3.0s", 5000 to "5.0s")
        val timeBtns = ArrayList<TextView>()
        var curTime = timeMs

        fun refreshTime() {
            presets.forEachIndexed { idx, (msVal, _) ->
                val active = msVal == curTime
                timeBtns[idx].background = ThemeManager.ripple(if (active) pal.primary else pal.surfaceContainer, null, dpf(22f), 0, !active)
                timeBtns[idx].setTextColor(if (active) pal.onPrimary else pal.textPrimary)
            }
        }
        presets.forEachIndexed { idx, (msVal, label) ->
            val tv = TextView(this).apply {
                text = label
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(8)
                }
                addBounce(this)
                setOnClickListener {
                    curTime = msVal
                    ThemeManager.setTimeMs(this@MainActivity, msVal)
                    refreshTime()
                }
            }
            timeBtns.add(tv)
            timeRow.addView(tv)
        }
        refreshTime()
        timeCard.addView(timeRow)

        // Anti-Ban Card
        val abCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.shape(pal.surface, pal.outline, dpf(28f), dp(1))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        abCard.addView(Switch(this).apply {
            text = Strings.antiBanTitle(this@MainActivity)
            textSize = 14.5f
            setTextColor(pal.textPrimary)
            isChecked = antiBan
            thumbTintList = ColorStateList.valueOf(pal.primary)
            trackTintList = ColorStateList.valueOf(pal.surfaceHigh)
            setPadding(dp(2), dp(2), dp(2), dp(8))
            setOnCheckedChangeListener { _, checked -> ThemeManager.setAntiBan(this@MainActivity, checked) }
        })
        abCard.addView(TextView(this).apply {
            text = Strings.antiBanDesc(this@MainActivity)
            textSize = 12f
            setTextColor(pal.textSecondary)
            setPadding(dp(2), 0, dp(2), dp(2))
        })

        // Auto-Move Card
        val autoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.shape(pal.surface, pal.outline, dpf(28f), dp(1))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        autoCard.addView(Switch(this).apply {
            text = Strings.autoMoveTitle(this@MainActivity)
            textSize = 14.5f
            setTextColor(pal.textPrimary)
            isChecked = autoMove
            thumbTintList = ColorStateList.valueOf(pal.primary)
            trackTintList = ColorStateList.valueOf(pal.surfaceHigh)
            setPadding(dp(2), dp(2), dp(2), dp(12))
            setOnCheckedChangeListener { _, checked ->
                ThemeManager.setAutoMove(this@MainActivity, checked)
                if (checked && !AutoClickService.isEnabled()) {
                    Toast.makeText(this@MainActivity, Strings.accToast(this@MainActivity), Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        })

        val accOk = AutoClickService.isEnabled()
        val accRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeManager.shape(pal.surfaceContainer, null, dpf(20f))
            setPadding(dp(14), dp(10), dp(10), dp(10))
        }
        accRow.addView(TextView(this).apply {
            text = if (accOk) Strings.accActive(this@MainActivity) else Strings.accNeed(this@MainActivity)
            textSize = 12.5f
            setTextColor(if (accOk) pal.textPrimary else pal.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        accRow.addView(TextView(this).apply {
            text = if (accOk) Strings.accOnBtn(this@MainActivity) else Strings.accEnableBtn(this@MainActivity)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (accOk) pal.onPrimary else pal.onSecondaryContainer)
            background = ThemeManager.ripple(if (accOk) pal.primary else pal.secondaryContainer, null, dpf(16f), 0, !accOk)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addBounce(this)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        autoCard.addView(accRow)

        return listOf(header, timeCard, abCard, autoCard)
    }

    // =========================================================================
    // TAB 3: STYLE (Language + Dark/Light Theme + 5 Palettes + Opacity Slider)
    // =========================================================================
    private fun buildStyleTab(pal: AppPalette): List<View> {
        val isDark = ThemeManager.isDark(this)
        val isRu = ThemeManager.getLang(this) == "ru"
        val alphaVal = ThemeManager.getAlpha(this)

        val header = TextView(this).apply {
            text = Strings.tabStyle(this@MainActivity)
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(pal.textPrimary)
            setPadding(dp(4), 0, 0, dp(16))
        }

        // Language & Theme Card
        val modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.shape(pal.surface, pal.outline, dpf(28f), dp(1))
            setPadding(dp(18), dp(16), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }

        modeCard.addView(TextView(this).apply {
            text = Strings.langLabel(this@MainActivity)
            textSize = 13f
            setTextColor(pal.textSecondary)
            setPadding(dp(2), 0, 0, dp(8))
        })
        val langPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ThemeManager.shape(pal.surfaceContainer, null, dpf(24f))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(16) }
        }
        langPill.addView(TextView(this).apply {
            text = "Русский"
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = if (isRu) ThemeManager.shape(pal.primary, null, dpf(20f)) else null
            setTextColor(if (isRu) pal.onPrimary else pal.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { if (!isRu) { ThemeManager.setLang(this@MainActivity, "ru"); renderScreen(false) } }
        })
        langPill.addView(TextView(this).apply {
            text = "English"
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = if (!isRu) ThemeManager.shape(pal.primary, null, dpf(20f)) else null
            setTextColor(if (!isRu) pal.onPrimary else pal.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { if (isRu) { ThemeManager.setLang(this@MainActivity, "en"); renderScreen(false) } }
        })
        modeCard.addView(langPill)

        modeCard.addView(TextView(this).apply {
            text = Strings.themeLabel(this@MainActivity)
            textSize = 13f
            setTextColor(pal.textSecondary)
            setPadding(dp(2), 0, 0, dp(8))
        })
        val themePill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ThemeManager.shape(pal.surfaceContainer, null, dpf(24f))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
        }
        themePill.addView(TextView(this).apply {
            text = Strings.themeDark(this@MainActivity)
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = if (isDark) ThemeManager.shape(pal.primary, null, dpf(20f)) else null
            setTextColor(if (isDark) pal.onPrimary else pal.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { if (!isDark) { ThemeManager.setDark(this@MainActivity, true); renderScreen(false) } }
        })
        themePill.addView(TextView(this).apply {
            text = Strings.themeLight(this@MainActivity)
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = if (!isDark) ThemeManager.shape(pal.primary, null, dpf(20f)) else null
            setTextColor(if (!isDark) pal.onPrimary else pal.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { if (isDark) { ThemeManager.setDark(this@MainActivity, false); renderScreen(false) } }
        })
        modeCard.addView(themePill)

        // Palette Card
        val palCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.shape(pal.surface, pal.outline, dpf(28f), dp(1))
            setPadding(dp(18), dp(16), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        palCard.addView(TextView(this).apply {
            text = Strings.paletteLabel(this@MainActivity)
            textSize = 13f
            setTextColor(pal.textSecondary)
            setPadding(dp(2), 0, 0, dp(12))
        })
        val swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val activePalId = ThemeManager.getPaletteId(this)
        ThemeManager.paletteOrder.forEach { pid ->
            val pObj = ThemeManager.getPalette(pid, isDark)
            val selected = pid == activePalId
            val itemCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addBounce(this)
                setOnClickListener {
                    if (pid != activePalId) {
                        ThemeManager.setPaletteId(this@MainActivity, pid)
                        renderScreen(false)
                    }
                }
            }
            itemCol.addView(View(this).apply {
                background = ThemeManager.shape(
                    Color.parseColor(pObj.swatchHex),
                    if (selected) pal.textPrimary else pal.outline,
                    dpf(if (selected) 16f else 22f),
                    dp(if (selected) 3 else 1)
                )
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { bottomMargin = dp(6) }
            })
            itemCol.addView(TextView(this).apply {
                text = if (isRu) pObj.nameRu else pObj.nameEn
                textSize = 11.5f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (selected) pal.textPrimary else pal.textSecondary)
            })
            swatchRow.addView(itemCol)
        }
        palCard.addView(swatchRow)

        // Opacity Card
        val opCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.shape(pal.surface, pal.outline, dpf(28f), dp(1))
            setPadding(dp(18), dp(16), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val pctInit = (alphaVal * 100 / 255)
        val alphaLbl = TextView(this).apply {
            text = Strings.opacityLabel(this@MainActivity, pctInit)
            textSize = 13f
            setTextColor(pal.textSecondary)
            setPadding(dp(2), 0, 0, dp(10))
        }
        val expressiveSlider = ExpressiveSliderView(this, pal.primary, pal.surfaceHigh, pal.surface).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
            setProgress((alphaVal - 110) / 145f)
            onChanged = { frac ->
                val v = (110 + frac * 145f).toInt().coerceIn(110, 255)
                ThemeManager.setAlpha(this@MainActivity, v)
                alphaLbl.text = Strings.opacityLabel(this@MainActivity, (v * 100 / 255))
            }
        }
        opCard.addView(alphaLbl)
        opCard.addView(expressiveSlider)

        return listOf(header, modeCard, palCard, opCard)
    }

    class ScallopBadgeView(
        context: Context,
        private val fillColor: Int,
        private val textColor: Int,
        private val label: String
    ) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor; style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val baseR = min(cx, cy) * 0.88f
            val amp = baseR * 0.085f
            val lobes = 10
            path.reset()
            for (i in 0..120) {
                val theta = (i.toDouble() / 120) * 2.0 * PI
                val r = baseR + amp * sin(lobes * theta).toFloat()
                val x = cx + r * cos(theta).toFloat()
                val y = cy + r * sin(theta).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, fillPaint)
            textPaint.textSize = baseR * 0.68f
            val fm = textPaint.fontMetrics
            canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
        }
    }

    class ExpressiveSliderView(
        context: Context,
        private val activeColor: Int,
        private val trackColor: Int,
        private val gapColor: Int
    ) : View(context) {
        private var progress = 0.85f
        var onChanged: ((Float) -> Unit)? = null

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = activeColor }
        private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gapColor }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = activeColor }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = activeColor }

        fun setProgress(p: Float) {
            progress = p.coerceIn(0f, 1f)
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val pad = width * 0.04f
                    val usable = (width - pad * 2f).coerceAtLeast(1f)
                    progress = ((event.x - pad) / usable).coerceIn(0f, 1f)
                    onChanged?.invoke(progress)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val pad = w * 0.03f
            val trackH = h * 0.44f
            val top = (h - trackH) / 2f
            val bottom = top + trackH
            val r = trackH / 2f
            val thumbX = pad + progress * (w - pad * 2f)
            val gapW = h * 0.34f
            val thumbW = h * 0.13f

            canvas.drawRoundRect(pad, top, w - pad, bottom, r, r, trackPaint)
            if (thumbX - gapW / 2f > pad) {
                canvas.drawRoundRect(pad, top, (thumbX - gapW / 2f).coerceAtLeast(pad), bottom, r, r, activePaint)
            }
            canvas.drawCircle(w - pad - r * 0.8f, h / 2f, trackH * 0.14f, dotPaint)
            canvas.drawRect(thumbX - gapW / 2f, top - 2f, thumbX + gapW / 2f, bottom + 2f, gapPaint)
            canvas.drawRoundRect(thumbX - thumbW / 2f, h * 0.08f, thumbX + thumbW / 2f, h * 0.92f, thumbW / 2f, thumbW / 2f, thumbPaint)
        }
    }
}
