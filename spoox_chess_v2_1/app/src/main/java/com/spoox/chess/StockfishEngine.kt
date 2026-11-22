package com.spoox.chess

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class StockfishEngine(private val context: Context) {

    private data class PvLine(
        val move: String,
        val ponder: String?,
        val st: String,
        val sv: Int,
        val depth: Int
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val searchGen = AtomicInteger(0)
    private val rng = Random()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    @Volatile private var ready = false
    private var currentMultiPv = 1

    fun isAvailable(): Boolean {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
        return bin.exists() && bin.canExecute()
    }

    @Synchronized
    private fun ensureStarted(): Boolean {
        if (ready && process != null) {
            try {
                if (process!!.isAlive) return true
            } catch (_: Exception) {}
            destroy()
        }
        val bin = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
        if (!bin.exists()) return false
        return try {
            val p = ProcessBuilder(bin.absolutePath)
                .redirectErrorStream(true)
                .start()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            reader = BufferedReader(InputStreamReader(p.inputStream))
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            send("uci")
            send("setoption name Threads value $threads")
            send("setoption name Hash value 128")
            send("setoption name Move Overhead value 20")
            send("setoption name Skill Level value 20")
            send("setoption name UCI_LimitStrength value false")
            send("setoption name MultiPV value 1")
            currentMultiPv = 1
            waitReady()
            ready
        } catch (_: Exception) {
            ready = false
            false
        }
    }

    private fun waitReady(): Boolean {
        send("isready")
        try {
            while (true) {
                val line = reader?.readLine() ?: return false
                if (line.contains("readyok")) {
                    ready = true
                    return true
                }
            }
        } catch (_: Exception) {
            ready = false
            return false
        }
    }

    private fun send(cmd: String) {
        try {
            writer?.write(cmd)
            writer?.newLine()
            writer?.flush()
        } catch (_: Exception) {}
    }

    fun stop() {
        searchGen.incrementAndGet()
        if (ready) send("stop")
    }

    fun destroy() {
        searchGen.incrementAndGet()
        try {
            send("quit")
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        ready = false
    }

    /**
     * Выбирает человекоподобный ход из MultiPV (1..4) при включённом Антибане:
     * - часто (~50%) делает слегка неточные, «странноватые» ходы (PV2/PV3/PV4)
     * - НИКОГДА не упускает форсированный мат и не отдаёт выигранную партию
     */
    private fun pickAntiBanMove(
        pvMap: Map<Int, PvLine>,
        turnSign: Int,
        plyCount: Int,
        fallbackBest: String?,
        fallbackPonder: String?,
        fallbackSt: String,
        fallbackSv: Int,
        fallbackDp: Int
    ): Triple<PvLine, Boolean, Int> {
        val pv1 = pvMap[1] ?: PvLine(fallbackBest ?: "", fallbackPonder, fallbackSt, fallbackSv, fallbackDp)
        if (pv1.st == "mate" || pvMap.size < 2 || plyCount < 2) {
            return Triple(pv1, false, 1)
        }

        // Оценка с точки зрения стороны, делающей ход
        val bestMyScore = pv1.sv * turnSign

        // Допустимая потеря в сантипешках зависит от перевеса:
        // если у нас огромный перевес (+3.5+), можем позволить неточность до 175 cp (исход не меняется),
        // в равной позиции — до 105 cp, в защите — максимум 55 cp
        // Жёсткий легит для 2800-3200+: микроскопическая потеря, не влияющая на победу
        val maxAllowedDrop = when {
            bestMyScore >= 450 -> 30
            bestMyScore >= 200 -> 18
            bestMyScore >= -50 -> 12
            else -> 8
        }

        val cand = pvMap[2] ?: return Triple(pv1, false, 1)
        if (cand.st == "mate") {
            return if (cand.sv * turnSign > 0 && rng.nextInt(100) < 35) {
                Triple(cand, true, 2)
            } else {
                Triple(pv1, false, 1)
            }
        }

        val candMyScore = cand.sv * turnSign
        val drop = bestMyScore - candMyScore

        // Никогда не отдаём выигранную позицию и не ослабляем защиту
        if (bestMyScore >= 150 && candMyScore < 120) return Triple(pv1, false, 1)

        // ~38% шанс выбрать 2-ю линию, если она практически равна 1-й (разница 5..maxAllowedDrop cp)
        if (drop in 5..maxAllowedDrop && rng.nextInt(100) < 38) {
            return Triple(cand, true, 2)
        }
        return Triple(pv1, false, 1)
    }

    fun searchPositionAsync(
        baseFen: String?,
        movesUci: List<String>,
        timeMs: Long,
        antiBan: Boolean,
        onInfo: (dp: Int, st: String, sv: Int, uci: String, ponder: String?, n: Long, ms: Long, nps: Long) -> Unit,
        onEnd: (dp: Int, st: String, sv: Int, uci: String?, ponder: String?, n: Long, ms: Long, nps: Long, wasHumanized: Boolean) -> Unit
    ) {
        val myGen = searchGen.incrementAndGet()
        if (ready) send("stop")

        val hasFen = !baseFen.isNullOrBlank()
        val posPrefix = if (hasFen) "position fen ${baseFen!!.trim()}" else "position startpos"
        val posCmd = if (movesUci.isEmpty()) posPrefix else "$posPrefix moves ${movesUci.joinToString(" ")}"

        val baseSide = if (hasFen && baseFen!!.contains(" b ")) -1 else 1
        val turnSign = if (movesUci.size % 2 == 0) baseSide else -baseSide
        val targetMultiPv = if (antiBan) 2 else 1

        executor.execute {
            if (searchGen.get() != myGen) return@execute
            if (!ensureStarted()) {
                onEnd(0, "cp", 0, null, null, 0L, 0L, 0L, false)
                return@execute
            }

            if (currentMultiPv != targetMultiPv) {
                send("setoption name MultiPV value $targetMultiPv")
                currentMultiPv = targetMultiPv
            }

            if (!waitReady()) {
                destroy()
                if (!ensureStarted()) {
                    onEnd(0, "cp", 0, null, null, 0L, 0L, 0L, false)
                    return@execute
                }
            }
            if (searchGen.get() != myGen) return@execute

            send(posCmd)
            send("go movetime $timeMs")

            var lastDp = 1
            var lastSt = "cp"
            var lastSv = 0
            var lastNodes = 0L
            var lastMs = 1L
            var lastNps = 0L
            var lastPonder: String? = null
            var bestMove: String? = null
            var bestPonder: String? = null

            val pvMap = HashMap<Int, PvLine>()

            try {
                while (true) {
                    val line = reader?.readLine() ?: break
                    if (line.startsWith("info ") && line.contains(" pv ")) {
                        val parts = line.split(" ")
                        var dp = lastDp; var st = lastSt; var sv = lastSv
                        var n = lastNodes; var ms = lastMs; var nps = lastNps
                        var mpv = 1
                        var pvMove: String? = null
                        var pvPonder: String? = null

                        var i = 1
                        while (i < parts.size) {
                            when (parts[i]) {
                                "depth" -> if (i + 1 < parts.size) dp = parts[++i].toIntOrNull() ?: dp
                                "multipv" -> if (i + 1 < parts.size) mpv = parts[++i].toIntOrNull() ?: 1
                                "score" -> {
                                    if (i + 2 < parts.size) {
                                        st = parts[++i]
                                        val raw = parts[++i].toIntOrNull() ?: 0
                                        sv = raw * turnSign
                                    }
                                }
                                "nodes" -> if (i + 1 < parts.size) n = parts[++i].toLongOrNull() ?: n
                                "time" -> if (i + 1 < parts.size) ms = parts[++i].toLongOrNull() ?: ms
                                "nps" -> if (i + 1 < parts.size) nps = parts[++i].toLongOrNull() ?: nps
                                "pv" -> {
                                    if (i + 1 < parts.size) pvMove = parts[++i]
                                    if (i + 1 < parts.size) pvPonder = parts[++i]
                                    break
                                }
                            }
                            i++
                        }
                        if (pvMove != null && searchGen.get() == myGen) {
                            pvMap[mpv] = PvLine(pvMove, pvPonder, st, sv, dp)
                            if (mpv == 1) {
                                lastDp = dp; lastSt = st; lastSv = sv
                                lastNodes = n; lastMs = ms; lastNps = nps
                                lastPonder = pvPonder
                                onInfo(dp, st, sv, pvMove, pvPonder, n, ms, nps)
                            }
                        }
                    } else if (line.startsWith("bestmove ")) {
                        val parts = line.split(" ")
                        if (parts.size >= 2 && parts[1] != "(none)") {
                            bestMove = parts[1]
                        }
                        if (parts.size >= 4 && parts[2] == "ponder") {
                            bestPonder = parts[3]
                        }
                        break
                    }
                }
            } catch (_: Exception) {}

            if (searchGen.get() == myGen) {
                if (antiBan && bestMove != null) {
                    val (picked, humanized, _) = pickAntiBanMove(
                        pvMap, turnSign, movesUci.size, bestMove, bestPonder ?: lastPonder, lastSt, lastSv, lastDp
                    )
                    val finalUci = if (picked.move.isNotBlank()) picked.move else bestMove
                    onEnd(picked.depth, picked.st, picked.sv, finalUci, picked.ponder, lastNodes, lastMs, lastNps, humanized)
                } else {
                    onEnd(lastDp, lastSt, lastSv, bestMove, bestPonder ?: lastPonder, lastNodes, lastMs, lastNps, false)
                }
            }
        }
    }
}
