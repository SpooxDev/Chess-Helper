package com.spoox.chess

import android.content.Context

object Strings {
    private fun ru(ctx: Context): Boolean = ThemeManager.getLang(ctx) == "ru"

    // Tabs
    fun tabPlay(ctx: Context) = if (ru(ctx)) "Игра" else "Play"
    fun tabEngine(ctx: Context) = if (ru(ctx)) "Движок" else "Engine"
    fun tabStyle(ctx: Context) = if (ru(ctx)) "Стиль" else "Style"

    fun appSubtitle(ctx: Context) = if (ru(ctx)) "v2.1 • Material You" else "v2.1 • Material You"
    fun langLabel(ctx: Context) = if (ru(ctx)) "Язык интерфейса" else "Language"
    fun themeLabel(ctx: Context) = if (ru(ctx)) "Тема оформления" else "Theme mode"
    fun themeDark(ctx: Context) = if (ru(ctx)) "Тёмная" else "Dark"
    fun themeLight(ctx: Context) = if (ru(ctx)) "Светлая" else "Light"
    fun sideLabel(ctx: Context) = if (ru(ctx)) "Сторона игры" else "Playing side"
    fun white(ctx: Context) = if (ru(ctx)) "Белые" else "White"
    fun black(ctx: Context) = if (ru(ctx)) "Чёрные" else "Black"
    fun paletteLabel(ctx: Context) = if (ru(ctx)) "Цветовая палитра" else "Color palette"
    fun antiBanTitle(ctx: Context) = if (ru(ctx)) "Антибан (жёсткий легит)" else "Anti-Ban (Legit Mode)"
    fun antiBanDesc(ctx: Context) = if (ru(ctx)) "MultiPV 2 (до 12 cp), живые паузы и разброс тапов. Выкл = макс. 3500+ ELO." else "MultiPV 2 (max 12 cp), human pauses and tap jitter. Off = max 3500+ ELO."
    fun autoMoveTitle(ctx: Context) = if (ru(ctx)) "Автоход (ходить за меня)" else "Auto-Move (Hands-Free)"
    fun accActive(ctx: Context) = if (ru(ctx)) "Служба автохода активна" else "Auto-Move service active"
    fun accNeed(ctx: Context) = if (ru(ctx)) "Включи Спец. возможности" else "Enable Accessibility service"
    fun accOnBtn(ctx: Context) = if (ru(ctx)) "Активно" else "Active"
    fun accEnableBtn(ctx: Context) = if (ru(ctx)) "Включить" else "Enable"
    fun accToast(ctx: Context) = if (ru(ctx)) "Включи «Spoox Chess Auto-Move» в Спец. возможностях" else "Enable 'Spoox Chess Auto-Move' in Accessibility Settings"
    fun thinkTimeLabel(ctx: Context) = if (ru(ctx)) "Время расчёта" else "Think time"
    fun opacityLabel(ctx: Context, pct: Int) = if (ru(ctx)) "Непрозрачность панели ($pct%)" else "Overlay opacity ($pct%)"
    fun launchBtn(ctx: Context) = if (ru(ctx)) "Запустить оверлей" else "Launch overlay"
    fun stopBtn(ctx: Context) = if (ru(ctx)) "Остановить сервис" else "Stop service"
    fun overlayPermToast(ctx: Context) = if (ru(ctx)) "Разреши показ поверх окон" else "Allow display over other apps"
    fun serviceStoppedToast(ctx: Context) = if (ru(ctx)) "Оверлей остановлен" else "Overlay stopped"
    fun statusSummary(ctx: Context, timeMs: Int, ab: Boolean, auto: Boolean): String {
        val sec = String.format("%.1f", timeMs / 1000f)
        return if (ru(ctx)) {
            "Время: ${sec}с  •  АБ: ${if (ab) "ВКЛ" else "ВЫКЛ"}  •  Авто: ${if (auto) "ВКЛ" else "ВЫКЛ"}"
        } else {
            "Time: ${sec}s  •  AB: ${if (ab) "ON" else "OFF"}  •  Auto: ${if (auto) "ON" else "OFF"}"
        }
    }

    // Overlay strings
    fun ovStart(ctx: Context) = if (ru(ctx)) "Старт" else "Start"
    fun ovPause(ctx: Context) = if (ru(ctx)) "Пауза" else "Pause"
    fun ovSideW(ctx: Context) = if (ru(ctx)) "Б" else "W"
    fun ovSideB(ctx: Context) = if (ru(ctx)) "Ч" else "B"
    fun ovAuto(ctx: Context) = if (ru(ctx)) "АВТО" else "AUTO"
    fun ovAb(ctx: Context) = if (ru(ctx)) "АБ" else "AB"
    fun ovPressStart(ctx: Context, sf: Boolean) = if (ru(ctx)) {
        if (sf) "Нажми Старт" else "Старт (Native)"
    } else {
        if (sf) "Press Start" else "Start (Native)"
    }
    fun ovResumed(ctx: Context) = if (ru(ctx)) "Продолжено" else "Resumed"
    fun ovPaused(ctx: Context) = if (ru(ctx)) "На паузе" else "Paused"
    fun ovAutoOn(ctx: Context) = if (ru(ctx)) "Автоход ВКЛ" else "Auto-Move ON"
    fun ovAutoOff(ctx: Context) = if (ru(ctx)) "Автоход ВЫКЛ" else "Auto-Move OFF"
    fun ovAbOn(ctx: Context) = if (ru(ctx)) "Антибан ВКЛ" else "Anti-Ban ON"
    fun ovAbOff(ctx: Context) = if (ru(ctx)) "Антибан ВЫКЛ" else "Anti-Ban OFF"
    fun ovSearchingBoard(ctx: Context) = if (ru(ctx)) "Поиск доски..." else "Finding board..."
    fun ovBoardFound(ctx: Context) = if (ru(ctx)) "Доска найдена" else "Board locked"
    fun ovCheckGrid(ctx: Context) = if (ru(ctx)) "Проверь сетку ⊞" else "Check grid ⊞"
    fun ovOppTurn(ctx: Context) = if (ru(ctx)) "Ход соперника..." else "Opponent turn..."
    fun ovThinking(ctx: Context) = if (ru(ctx)) "Думаю..." else "Thinking..."
    fun ovDepth(ctx: Context, d: Int) = if (ru(ctx)) "Анализ • d$d" else "Depth $d"
    fun ovMate(ctx: Context, m: Int) = if (ru(ctx)) "МАТ $m" else "MATE $m"
    fun ovNotif(ctx: Context) = if (ru(ctx)) "Оверлей активен" else "Overlay active"
}
