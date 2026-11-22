package com.spoox.chess

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

data class AppPalette(
    val id: String,
    val nameRu: String,
    val nameEn: String,
    val swatchHex: String,
    val bg: Int,
    val surface: Int,
    val surfaceContainer: Int,
    val surfaceHigh: Int,
    val outline: Int,
    val primary: Int,
    val onPrimary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val arrowMain: Int,
    val arrowOutline: Int
)

object ThemeManager {
    private const val PREFS = "spoox_chess_prefs"

    const val PALETTE_MINT = "mint"
    const val PALETTE_LAVENDER = "lavender"
    const val PALETTE_PEACH = "peach"
    const val PALETTE_SKY = "sky"
    const val PALETTE_MONO = "mono"

    val paletteOrder = listOf(PALETTE_MINT, PALETTE_LAVENDER, PALETTE_PEACH, PALETTE_SKY, PALETTE_MONO)

    fun isDark(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("is_dark", true)
    fun setDark(ctx: Context, dark: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("is_dark", dark).apply()

    fun getPaletteId(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("palette_id", PALETTE_MINT) ?: PALETTE_MINT
    fun setPaletteId(ctx: Context, id: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("palette_id", id).apply()

    fun getLang(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", "ru") ?: "ru"
    fun setLang(ctx: Context, lang: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lang", lang).apply()

    fun isBlackSide(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("is_black", false)
    fun setBlackSide(ctx: Context, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("is_black", v).apply()

    fun getTimeMs(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("time_ms", 1500)
    fun setTimeMs(ctx: Context, v: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("time_ms", v).apply()

    fun isAntiBan(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("anti_ban", false)
    fun setAntiBan(ctx: Context, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("anti_ban", v).apply()

    fun isAutoMove(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_move", false)
    fun setAutoMove(ctx: Context, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_move", v).apply()

    fun getAlpha(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("overlay_alpha", 242)
    fun setAlpha(ctx: Context, v: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("overlay_alpha", v).apply()

    private fun c(hex: String): Int = Color.parseColor(hex)

    fun getPalette(ctx: Context): AppPalette = getPalette(getPaletteId(ctx), isDark(ctx))

    fun getPalette(id: String, dark: Boolean): AppPalette {
        return when (id) {
            PALETTE_LAVENDER -> if (dark) AppPalette(
                id, "Лаванда", "Lavender", "#D0BCFF",
                bg = c("#0B0A10"), surface = c("#171522"), surfaceContainer = c("#211E30"), surfaceHigh = c("#2C2840"),
                outline = c("#3D3759"), primary = c("#D0BCFF"), onPrimary = c("#27194A"),
                secondaryContainer = c("#332B4C"), onSecondaryContainer = c("#EADDFF"),
                textPrimary = c("#F3EFFA"), textSecondary = c("#A9A1BD"),
                arrowMain = c("#D0BCFF"), arrowOutline = c("#1D1433")
            ) else AppPalette(
                id, "Лаванда", "Lavender", "#7C5DFA",
                bg = c("#F7F4FF"), surface = c("#FFFFFF"), surfaceContainer = c("#EFEAFF"), surfaceHigh = c("#E3DAFF"),
                outline = c("#CFC4EC"), primary = c("#6750A4"), onPrimary = c("#FFFFFF"),
                secondaryContainer = c("#E8DEF8"), onSecondaryContainer = c("#1D192B"),
                textPrimary = c("#1C1B1F"), textSecondary = c("#5B556B"),
                arrowMain = c("#7C5DFA"), arrowOutline = c("#1C1533")
            )

            PALETTE_PEACH -> if (dark) AppPalette(
                id, "Персик", "Peach", "#FFB599",
                bg = c("#110D0B"), surface = c("#1F1815"), surfaceContainer = c("#2A211D"), surfaceHigh = c("#382B26"),
                outline = c("#4E3C35"), primary = c("#FFB599"), onPrimary = c("#4A1E0C"),
                secondaryContainer = c("#422A21"), onSecondaryContainer = c("#FFDBCE"),
                textPrimary = c("#FAF1EE"), textSecondary = c("#BDA79E"),
                arrowMain = c("#FFB599"), arrowOutline = c("#2E140A")
            ) else AppPalette(
                id, "Персик", "Peach", "#E07A5F",
                bg = c("#FFF6F3"), surface = c("#FFFFFF"), surfaceContainer = c("#FCECE6"), surfaceHigh = c("#F7DDD3"),
                outline = c("#E6C5B8"), primary = c("#B85035"), onPrimary = c("#FFFFFF"),
                secondaryContainer = c("#FFDBCE"), onSecondaryContainer = c("#38150A"),
                textPrimary = c("#201A18"), textSecondary = c("#6B5852"),
                arrowMain = c("#E07A5F"), arrowOutline = c("#2E140A")
            )

            PALETTE_SKY -> if (dark) AppPalette(
                id, "Небо", "Sky", "#A8C7FA",
                bg = c("#0A0E14"), surface = c("#141B24"), surfaceContainer = c("#1D2633"), surfaceHigh = c("#273345"),
                outline = c("#374861"), primary = c("#A8C7FA"), onPrimary = c("#072E6B"),
                secondaryContainer = c("#22344D"), onSecondaryContainer = c("#D3E3FD"),
                textPrimary = c("#EFF4FA"), textSecondary = c("#9BA9BC"),
                arrowMain = c("#A8C7FA"), arrowOutline = c("#0B1B36")
            ) else AppPalette(
                id, "Небо", "Sky", "#4A89F3",
                bg = c("#F3F7FD"), surface = c("#FFFFFF"), surfaceContainer = c("#E8F0FC"), surfaceHigh = c("#D7E5FA"),
                outline = c("#BCCEE8"), primary = c("#1B6EF3"), onPrimary = c("#FFFFFF"),
                secondaryContainer = c("#D3E3FD"), onSecondaryContainer = c("#07224D"),
                textPrimary = c("#181C22"), textSecondary = c("#536073"),
                arrowMain = c("#3B82F6"), arrowOutline = c("#0B1B36")
            )

            PALETTE_MONO -> if (dark) AppPalette(
                id, "Графит", "Mono", "#E4E4E7",
                bg = c("#0B0C0E"), surface = c("#16171B"), surfaceContainer = c("#202228"), surfaceHigh = c("#2B2E36"),
                outline = c("#3A3E48"), primary = c("#F4F4F5"), onPrimary = c("#0B0C0E"),
                secondaryContainer = c("#272930"), onSecondaryContainer = c("#E4E4E7"),
                textPrimary = c("#F4F4F5"), textSecondary = c("#9CA3AF"),
                arrowMain = c("#F4F4F5"), arrowOutline = c("#0B0C0E")
            ) else AppPalette(
                id, "Графит", "Mono", "#3F3F46",
                bg = c("#F4F4F6"), surface = c("#FFFFFF"), surfaceContainer = c("#EAECEF"), surfaceHigh = c("#DCDFE5"),
                outline = c("#C5C9D1"), primary = c("#18181B"), onPrimary = c("#FFFFFF"),
                secondaryContainer = c("#E4E4E7"), onSecondaryContainer = c("#18181B"),
                textPrimary = c("#111214"), textSecondary = c("#525663"),
                arrowMain = c("#27272A"), arrowOutline = c("#FFFFFF")
            )

            else -> if (dark) AppPalette( // PALETTE_MINT (по умолчанию, как на скрине)
                id, "Мята", "Mint", "#A3ECEC",
                bg = c("#0B1010"), surface = c("#151D1D"), surfaceContainer = c("#1D2828"), surfaceHigh = c("#263535"),
                outline = c("#334747"), primary = c("#A3ECEC"), onPrimary = c("#003737"),
                secondaryContainer = c("#203434"), onSecondaryContainer = c("#C8F6F6"),
                textPrimary = c("#EEF6F6"), textSecondary = c("#97ADAD"),
                arrowMain = c("#A3ECEC"), arrowOutline = c("#072626")
            ) else AppPalette(
                id, "Мята", "Mint", "#2CA6A4",
                bg = c("#F2F9F9"), surface = c("#FFFFFF"), surfaceContainer = c("#E4F2F2"), surfaceHigh = c("#D3EBEB"),
                outline = c("#B5D4D4"), primary = c("#006A6A"), onPrimary = c("#FFFFFF"),
                secondaryContainer = c("#CCE8E8"), onSecondaryContainer = c("#002020"),
                textPrimary = c("#141C1C"), textSecondary = c("#4E6363"),
                arrowMain = c("#0F8B8D"), arrowOutline = c("#052626")
            )
        }
    }

    fun shape(color: Int, strokeColor: Int? = null, radiusPx: Float, strokePx: Int = 2): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
            if (strokeColor != null) setStroke(strokePx, strokeColor)
        }
    }

    fun ripple(color: Int, strokeColor: Int? = null, radiusPx: Float, strokePx: Int = 2, isLightRipple: Boolean = true): Drawable {
        val content = shape(color, strokeColor, radiusPx, strokePx)
        val ripColor = if (isLightRipple) Color.parseColor("#33FFFFFF") else Color.parseColor("#26000000")
        return RippleDrawable(ColorStateList.valueOf(ripColor), content, content)
    }

    fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}
