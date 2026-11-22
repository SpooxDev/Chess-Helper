package com.spoox.chess

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class SpooxEngine {

    companion object {
        private const val MATE = 30000
        private const val INF = 32000
        private const val MAX_PLY = 64

        private const val FCAP = 1 shl 18
        private const val FEP = 1 shl 19
        private const val FCAS = 1 shl 20

        private const val TT_SIZE = 1 shl 20
        private const val TT_MASK = TT_SIZE - 1

        private val MV = intArrayOf(0, 82, 337, 365, 477, 1025, 20000)
        private val EV = intArrayOf(0, 94, 281, 297, 512, 936, 20000)
        private val PW = intArrayOf(0, 0, 1, 1, 2, 4, 0)
        private val CAPV = intArrayOf(0, 100, 325, 340, 500, 950, 10000)

        private val PMG = intArrayOf(0, 6, 14, 28, 52, 88, 145, 0)
        private val PEG = intArrayOf(0, 14, 28, 52, 92, 155, 240, 0)

        private val MP = arrayOf(
            IntArray(0),
            intArrayOf(0,0,0,0,0,0,0,0,98,134,61,95,68,126,34,-11,-6,7,26,31,65,56,25,-20,-14,13,6,21,23,12,17,-23,-27,-2,-5,12,17,6,10,-25,-26,-4,-4,-10,3,3,33,-12,-35,-1,-20,-23,-15,24,38,-22,0,0,0,0,0,0,0,0),
            intArrayOf(-167,-89,-34,-49,61,-97,-15,-107,-73,-41,72,36,23,62,7,-17,-47,60,37,65,84,129,73,44,-9,17,19,53,37,69,18,22,-13,4,16,13,28,19,21,-8,-23,-9,12,10,19,17,25,-16,-29,-53,-12,-3,-1,18,-14,-19,-105,-21,-58,-33,-17,-28,-19,-23),
            intArrayOf(-29,4,-82,-37,-25,-42,7,-8,-26,16,-18,-13,30,59,18,-47,-16,37,43,40,35,50,37,-2,-4,5,19,50,37,37,7,-2,-6,13,13,26,34,12,10,4,0,15,15,15,14,27,18,10,4,15,16,0,7,21,33,1,-33,-3,-14,-21,-13,-12,-39,-21),
            intArrayOf(32,42,32,51,63,9,31,43,27,32,58,62,80,67,26,44,-5,19,26,36,17,45,61,16,-24,-11,7,26,24,35,-8,-20,-36,-26,-12,-1,9,-7,6,-23,-45,-25,-16,-17,3,0,-5,-33,-44,-16,-20,-9,-1,11,-6,-71,-19,-13,1,17,16,7,-37,-26),
            intArrayOf(-28,0,29,12,59,44,43,45,-24,-39,-5,1,-16,57,28,54,-13,-17,7,8,29,56,47,57,-27,-27,-16,-16,-1,17,-2,1,-9,-26,-9,-10,-2,-4,3,-3,-14,2,-11,-2,-5,2,14,5,-35,-8,11,2,8,15,-3,1,-1,-18,-9,10,-15,-25,-31,-50),
            intArrayOf(-65,23,16,-15,-56,-34,2,13,29,-1,-20,-7,-8,-4,-38,-29,-9,24,2,-16,-20,6,22,-22,-17,-20,-12,-27,-30,-25,-14,-36,-49,-1,-27,-39,-46,-44,-33,-51,-14,-14,-22,-46,-44,-30,-15,-27,1,7,-8,-64,-43,-16,9,8,-15,36,12,-54,8,-28,24,14)
        )

        private val EP = arrayOf(
            IntArray(0),
            intArrayOf(0,0,0,0,0,0,0,0,178,173,158,134,147,132,165,187,94,100,85,67,56,53,82,84,32,24,13,5,-2,4,17,17,13,9,-3,-7,-7,-8,3,-1,4,7,-6,1,0,-5,-1,-8,13,8,8,10,13,0,2,-7,0,0,0,0,0,0,0,0),
            intArrayOf(-58,-38,-13,-28,-31,-27,-63,-99,-25,-8,-25,-2,-9,-25,-24,-52,-24,-20,10,9,-1,-9,-19,-41,-17,3,22,22,22,11,8,-18,-18,-6,16,25,16,17,4,-18,-23,-3,-1,15,10,-3,-20,-22,-42,-20,-10,-5,-2,-20,-23,-44,-29,-51,-23,-15,-22,-18,-50,-64),
            intArrayOf(-14,-21,-11,-8,-7,-9,-17,-24,-8,-4,7,-12,-3,-13,-4,-14,2,-8,0,-1,-2,6,0,4,-3,9,12,9,14,10,3,2,-6,3,13,19,7,10,-3,-9,-12,-3,8,10,13,3,-7,-15,-14,-18,-7,-1,4,-9,-15,-27,-23,-9,-23,-5,-9,-16,-5,-17),
            intArrayOf(13,10,18,15,12,12,8,5,11,13,13,11,-3,3,8,3,7,7,7,5,4,-3,-5,-3,4,3,13,1,2,1,-1,2,3,5,8,4,-5,-6,-8,-11,-4,0,-5,-1,-7,-12,-8,-16,-6,-6,0,2,-9,-9,-11,-3,-9,2,3,-1,-5,-13,4,-20),
            intArrayOf(-9,22,22,27,27,19,10,20,-17,20,32,41,58,25,30,0,-20,6,9,49,47,35,19,9,3,22,24,45,57,40,57,36,-18,28,19,47,31,34,39,23,-16,-27,15,6,9,17,10,5,-22,-23,-30,-16,-16,-23,-36,-32,-33,-28,-22,-43,-5,-32,-20,-41),
            intArrayOf(-74,-35,-18,-18,-11,15,4,-17,-12,17,14,17,17,38,23,11,10,17,23,15,20,45,44,13,-8,22,24,27,26,33,26,3,-18,-4,21,24,27,23,9,-11,-19,-3,11,21,23,16,7,-9,-27,-11,4,13,14,4,-5,-17,-53,-34,-21,-11,-28,-14,-24,-43)
        )

        private val ND = intArrayOf(-33, -31, -18, -14, 14, 18, 31, 33)
        private val KD = intArrayOf(-17, -16, -15, -1, 1, 15, 16, 17)
        private val BD = intArrayOf(-17, -15, 15, 17)
        private val RD = intArrayOf(-16, -1, 1, 16)
        private val TROPISM = intArrayOf(0, 0, 6, 5, 8, 14, 0)

        private val LMR = Array(64) { d ->
            IntArray(64) { m ->
                if (d >= 3 && m >= 3) max(1, (0.55 + ln(d.toDouble()) * ln(m.toDouble()) / 2.1).toInt()) else 0
            }
        }
    }

    @Volatile var mateFocus: Boolean = true

    private val executor = Executors.newSingleThreadExecutor()
    private val stopFlag = AtomicBoolean(false)

    private val zPiece = LongArray(13 * 64)
    private val zSide: Long
    private val zCastle = LongArray(16)
    private val zEp = LongArray(65)

    private val ttKey = LongArray(TT_SIZE)
    private val ttMove = IntArray(TT_SIZE)
    private val ttScore = ShortArray(TT_SIZE)
    private val ttDepth = ByteArray(TT_SIZE)
    private val ttFlag = ByteArray(TT_SIZE)

    private val b = IntArray(128)
    private val cm = IntArray(128) { 15 }.apply {
        this[0x00] = 7; this[0x04] = 3; this[0x07] = 11
        this[0x70] = 13; this[0x74] = 12; this[0x77] = 14
    }

    private val mb = IntArray(MAX_PLY * 256)
    private val sb = IntArray(MAX_PLY * 256)

    private val uCap = IntArray(512)
    private val uCas = IntArray(512)
    private val uEp = IntArray(512)
    private val uWk = IntArray(512)
    private val uBk = IntArray(512)
    private val uHash = LongArray(512)

    private val repHash = LongArray(1024)
    private var repPly = 0

    private val k1 = IntArray(MAX_PLY)
    private val k2 = IntArray(MAX_PLY)
    private val history = IntArray(2 * 64 * 64)
    private val counterMove = IntArray(64 * 64)

    private val wpr = IntArray(8)
    private val bpr = IntArray(8)
    private val wpc = IntArray(8)
    private val bpc = IntArray(8)

    private var turn = 1
    private var rootTurn = 1
    private var cas = 15
    private var ep = -1
    private var wk = 0x74
    private var bk = 0x04
    private var curHash = 0L
    private var uTop = 0

    private var nodes = 0L
    private var endTimeMs = 0L
    private var stopped = false

    init {
        var s = 0x9E3779B97F4A7C15UL.toLong()
        fun nextRand(): Long {
            s = s xor (s shl 13)
            s = s xor (s ushr 7)
            s = s xor (s shl 17)
            return s
        }
        for (i in zPiece.indices) zPiece[i] = nextRand()
        zSide = nextRand()
        for (i in zCastle.indices) zCastle[i] = nextRand()
        for (i in zEp.indices) zEp[i] = nextRand()
    }

    fun stop() {
        stopFlag.set(true)
    }

    fun searchAsync(
        movesUci: List<String>,
        timeMs: Long,
        onInfo: (dp: Int, st: String, sv: Int, uci: String, n: Long, ms: Long, nps: Long) -> Unit,
        onEnd: (dp: Int, st: String, sv: Int, uci: String?, n: Long, ms: Long, nps: Long) -> Unit
    ) = searchPositionAsync(null, movesUci, timeMs, onInfo, onEnd)

    private fun enc(f: Int, t: Int, pr: Int, fl: Int): Int = f or (t shl 7) or (pr shl 14) or fl
    private fun s64(sq: Int): Int = ((sq and 0x70) shr 1) or (sq and 7)

    private fun toUci(m: Int): String {
        val fl = "abcdefgh"
        val f = m and 127
        val t = (m shr 7) and 127
        val pr = (m shr 14) and 15
        val pm = arrayOf("", "", "n", "b", "r", "q")
        return "${fl[f and 7]}${8 - (f shr 4)}${fl[t and 7]}${8 - (t shr 4)}${if (pr != 0) pm[pr] else ""}"
    }

    private fun computeHash(): Long {
        var h = 0L
        for (r in 0 until 8) {
            val ro = r shl 4
            for (c in 0 until 8) {
                val p = b[ro or c]
                if (p != 0) h = h xor zPiece[(p + 6) * 64 + ((r shl 3) or c)]
            }
        }
        if (turn == -1) h = h xor zSide
        h = h xor zCastle[cas]
        h = h xor zEp[if (ep == -1) 64 else s64(ep)]
        return h
    }


    private fun loadFen(fen: String) {
        b.fill(0)
        val parts = fen.trim().split(" ")
        val rows = parts[0].split("/")
        if (rows.size != 8) { initBoard(); return }
        for (r in 0 until 8) {
            var c = 0
            for (ch in rows[r]) {
                if (ch in '1'..'8') {
                    c += (ch - '0')
                } else if (c < 8) {
                    val p = when (ch) {
                        'P' -> 1; 'N' -> 2; 'B' -> 3; 'R' -> 4; 'Q' -> 5; 'K' -> 6
                        'p' -> -1; 'n' -> -2; 'b' -> -3; 'r' -> -4; 'q' -> -5; 'k' -> -6
                        else -> 0
                    }
                    val sq = (r shl 4) or c
                    b[sq] = p
                    if (p == 6) wk = sq
                    if (p == -6) bk = sq
                    c++
                }
            }
        }
        turn = if (parts.size > 1 && parts[1] == "b") -1 else 1
        cas = 0
        if (parts.size > 2) {
            val cs = parts[2]
            if ('K' in cs) cas = cas or 1
            if ('Q' in cs) cas = cas or 2
            if ('k' in cs) cas = cas or 4
            if ('q' in cs) cas = cas or 8
        }
        ep = -1
        uTop = 0; repPly = 0
        curHash = computeHash()
        repHash[0] = curHash
        repPly = 1
    }

    fun searchPositionAsync(
        baseFen: String?,
        movesUci: List<String>,
        timeMs: Long,
        onInfo: (dp: Int, st: String, sv: Int, uci: String, n: Long, ms: Long, nps: Long) -> Unit,
        onEnd: (dp: Int, st: String, sv: Int, uci: String?, n: Long, ms: Long, nps: Long) -> Unit
    ) {
        stopFlag.set(true)
        executor.execute {
            stopFlag.set(false)
            runSearch(baseFen, movesUci, timeMs, onInfo, onEnd)
        }
    }

    private fun initBoard() {
        b.fill(0)
        val back = intArrayOf(4, 2, 3, 5, 6, 3, 2, 4)
        for (c in 0 until 8) {
            b[c] = -back[c]
            b[0x10 or c] = -1
            b[0x60 or c] = 1
            b[0x70 or c] = back[c]
        }
        turn = 1; cas = 15; ep = -1; wk = 0x74; bk = 0x04
        uTop = 0; repPly = 0
        curHash = computeHash()
        repHash[0] = curHash
        repPly = 1
    }

    private fun isAttacked(sq: Int, by: Int): Boolean {
        if (by == 1) {
            val s1 = sq + 15; val s2 = sq + 17
            if ((s1 and 0x88) == 0 && b[s1] == 1) return true
            if ((s2 and 0x88) == 0 && b[s2] == 1) return true
        } else {
            val s1 = sq - 15; val s2 = sq - 17
            if ((s1 and 0x88) == 0 && b[s1] == -1) return true
            if ((s2 and 0x88) == 0 && b[s2] == -1) return true
        }
        val nc = if (by == 1) 2 else -2
        for (d in ND) {
            val t = sq + d
            if ((t and 0x88) == 0 && b[t] == nc) return true
        }
        val kc = if (by == 1) 6 else -6
        for (d in KD) {
            val t = sq + d
            if ((t and 0x88) == 0 && b[t] == kc) return true
        }
        val bc = if (by == 1) 3 else -3
        val qc = if (by == 1) 5 else -5
        for (d in BD) {
            var t = sq + d
            while ((t and 0x88) == 0) {
                val p = b[t]
                if (p != 0) { if (p == bc || p == qc) return true; break }
                t += d
            }
        }
        val rc = if (by == 1) 4 else -4
        for (d in RD) {
            var t = sq + d
            while ((t and 0x88) == 0) {
                val p = b[t]
                if (p != 0) { if (p == rc || p == qc) return true; break }
                t += d
            }
        }
        return false
    }

    private fun genMoves(ply: Int, capsOnly: Boolean): Int {
        val base = ply shl 8
        var n = 0
        val s = turn

        for (r in 0 until 8) {
            val ro = r shl 4
            for (c in 0 until 8) {
                val sq = ro or c
                val p = b[sq]
                if (p == 0 || (if (p > 0) 1 else -1) != s) continue
                val tp = abs(p)

                if (tp == 1) {
                    val pu = if (s == 1) -16 else 16
                    val sr = if (s == 1) 6 else 1
                    val pr = if (s == 1) 0 else 7
                    val t1 = sq + pu

                    if (!capsOnly && (t1 and 0x88) == 0 && b[t1] == 0) {
                        if ((t1 shr 4) == pr) {
                            mb[base + n++] = enc(sq, t1, 5, 0)
                            mb[base + n++] = enc(sq, t1, 4, 0)
                            mb[base + n++] = enc(sq, t1, 3, 0)
                            mb[base + n++] = enc(sq, t1, 2, 0)
                        } else {
                            mb[base + n++] = enc(sq, t1, 0, 0)
                            if (r == sr) {
                                val t2 = t1 + pu
                                if (b[t2] == 0) mb[base + n++] = enc(sq, t2, 0, 0)
                            }
                        }
                    }

                    val c1 = sq + (if (s == 1) -17 else 15)
                    val c2 = sq + (if (s == 1) -15 else 17)
                    for (k in 0..1) {
                        val t = if (k == 0) c1 else c2
                        if ((t and 0x88) != 0) continue
                        val tg = b[t]
                        if (tg != 0 && (if (tg > 0) 1 else -1) != s) {
                            if ((t shr 4) == pr) {
                                mb[base + n++] = enc(sq, t, 5, FCAP)
                                mb[base + n++] = enc(sq, t, 4, FCAP)
                                mb[base + n++] = enc(sq, t, 3, FCAP)
                                mb[base + n++] = enc(sq, t, 2, FCAP)
                            } else {
                                mb[base + n++] = enc(sq, t, 0, FCAP)
                            }
                        } else if (t == ep) {
                            mb[base + n++] = enc(sq, t, 0, FCAP or FEP)
                        }
                    }
                } else if (tp == 2) {
                    for (d in ND) {
                        val t = sq + d
                        if ((t and 0x88) != 0) continue
                        val tg = b[t]
                        if (tg == 0) { if (!capsOnly) mb[base + n++] = enc(sq, t, 0, 0) }
                        else if ((if (tg > 0) 1 else -1) != s) mb[base + n++] = enc(sq, t, 0, FCAP)
                    }
                } else if (tp == 6) {
                    for (d in KD) {
                        val t = sq + d
                        if ((t and 0x88) != 0) continue
                        val tg = b[t]
                        if (tg == 0) { if (!capsOnly) mb[base + n++] = enc(sq, t, 0, 0) }
                        else if ((if (tg > 0) 1 else -1) != s) mb[base + n++] = enc(sq, t, 0, FCAP)
                    }
                    if (!capsOnly) {
                        if (s == 1 && sq == 0x74) {
                            if ((cas and 1) != 0 && b[0x75] == 0 && b[0x76] == 0 && b[0x77] == 4 &&
                                !isAttacked(0x74, -1) && !isAttacked(0x75, -1) && !isAttacked(0x76, -1))
                                mb[base + n++] = enc(0x74, 0x76, 0, FCAS)
                            if ((cas and 2) != 0 && b[0x73] == 0 && b[0x72] == 0 && b[0x71] == 0 && b[0x70] == 4 &&
                                !isAttacked(0x74, -1) && !isAttacked(0x73, -1) && !isAttacked(0x72, -1))
                                mb[base + n++] = enc(0x74, 0x72, 0, FCAS)
                        } else if (s == -1 && sq == 0x04) {
                            if ((cas and 4) != 0 && b[0x05] == 0 && b[0x06] == 0 && b[0x07] == -4 &&
                                !isAttacked(0x04, 1) && !isAttacked(0x05, 1) && !isAttacked(0x06, 1))
                                mb[base + n++] = enc(0x04, 0x06, 0, FCAS)
                            if ((cas and 8) != 0 && b[0x03] == 0 && b[0x02] == 0 && b[0x01] == 0 && b[0x00] == -4 &&
                                !isAttacked(0x04, 1) && !isAttacked(0x03, 1) && !isAttacked(0x02, 1))
                                mb[base + n++] = enc(0x04, 0x02, 0, FCAS)
                        }
                    }
                } else {
                    val ds = when (tp) { 3 -> BD; 4 -> RD; else -> KD }
                    for (d in ds) {
                        var t = sq + d
                        while ((t and 0x88) == 0) {
                            val tg = b[t]
                            if (tg == 0) { if (!capsOnly) mb[base + n++] = enc(sq, t, 0, 0) }
                            else {
                                if ((if (tg > 0) 1 else -1) != s) mb[base + n++] = enc(sq, t, 0, FCAP)
                                break
                            }
                            t += d
                        }
                    }
                }
            }
        }
        return n
    }

    private fun makeMove(m: Int): Boolean {
        if (uTop >= uCap.size || repPly >= repHash.size) return false
        val f = m and 127
        val t = (m shr 7) and 127
        val pr = (m shr 14) and 15
        val ie = (m and FEP) != 0
        val ic = (m and FCAS) != 0

        uCap[uTop] = b[t]; uCas[uTop] = cas; uEp[uTop] = ep
        uWk[uTop] = wk; uBk[uTop] = bk; uHash[uTop] = curHash
        uTop++

        var h = curHash xor zCastle[cas] xor zEp[if (ep == -1) 64 else s64(ep)]

        val p = b[f]
        val cap = b[t]
        h = h xor zPiece[(p + 6) * 64 + s64(f)]
        b[f] = 0

        if (cap != 0) h = h xor zPiece[(cap + 6) * 64 + s64(t)]
        if (ie) {
            val es = if (turn == 1) t + 16 else t - 16
            h = h xor zPiece[(b[es] + 6) * 64 + s64(es)]
            b[es] = 0
        }

        val pl = if (pr != 0) (if (turn == 1) pr else -pr) else p
        b[t] = pl
        h = h xor zPiece[(pl + 6) * 64 + s64(t)]

        if (p == 6) wk = t else if (p == -6) bk = t

        if (ic) {
            val rf: Int; val rt: Int; val rp: Int
            when (t) {
                0x76 -> { rf = 0x77; rt = 0x75; rp = 4 }
                0x72 -> { rf = 0x70; rt = 0x73; rp = 4 }
                0x06 -> { rf = 0x07; rt = 0x05; rp = -4 }
                else -> { rf = 0x00; rt = 0x03; rp = -4 }
            }
            b[rf] = 0; b[rt] = rp
            val rb = (rp + 6) * 64
            h = h xor zPiece[rb + s64(rf)] xor zPiece[rb + s64(rt)]
        }

        cas = cas and cm[f] and cm[t]
        val df = t - f
        ep = if ((p == 1 || p == -1) && (df == 32 || df == -32)) ((f + t) shr 1) else -1
        turn = -turn

        h = h xor zSide xor zCastle[cas] xor zEp[if (ep == -1) 64 else s64(ep)]
        curHash = h

        if (isAttacked(if (turn == -1) wk else bk, turn)) {
            unmakeMove(m)
            return false
        }
        repHash[repPly++] = curHash
        return true
    }

    private fun unmakeMove(m: Int) {
        uTop--
        val f = m and 127
        val t = (m shr 7) and 127
        val pr = (m shr 14) and 15
        val ie = (m and FEP) != 0
        val ic = (m and FCAS) != 0

        turn = -turn
        cas = uCas[uTop]; ep = uEp[uTop]
        wk = uWk[uTop]; bk = uBk[uTop]
        curHash = uHash[uTop]

        b[f] = if (pr != 0) (if (turn == 1) 1 else -1) else b[t]
        b[t] = uCap[uTop]

        if (ie) b[if (turn == 1) t + 16 else t - 16] = if (turn == 1) -1 else 1
        else if (ic) {
            when (t) {
                0x76 -> { b[0x77] = 4; b[0x75] = 0 }
                0x72 -> { b[0x70] = 4; b[0x73] = 0 }
                0x06 -> { b[0x07] = -4; b[0x05] = 0 }
                else -> { b[0x00] = -4; b[0x03] = 0 }
            }
        }
    }

    private fun unmakePop(m: Int) {
        repPly--
        unmakeMove(m)
    }

    private fun isRepetition(): Boolean {
        var i = repPly - 3
        while (i >= 0) {
            if (repHash[i] == curHash) return true
            i -= 2
        }
        return false
    }

    private fun hasNonPawn(s: Int): Boolean {
        for (r in 0 until 8) {
            val ro = r shl 4
            for (c in 0 until 8) {
                val p = b[ro or c]
                if (if (s == 1) (p in 2..5) else (p in -5..-2)) return true
            }
        }
        return false
    }

    private fun evalPos(): Int {
        var mw = 0; var ew = 0; var mb = 0; var eb = 0; var ph = 0
        var wb = 0; var bb = 0; var wrf = 0; var brf = 0
        var wAtt = 0; var bAtt = 0
        wpr.fill(8); bpr.fill(-1); wpc.fill(0); bpc.fill(0)

        val wkr = wk shr 4; val wkc = wk and 7
        val bkr = bk shr 4; val bkc = bk and 7
        val agg = mateFocus

        for (r in 0 until 8) {
            val ro = r shl 4; val r6 = r shl 3
            for (c in 0 until 8) {
                val sq = ro or c
                val p = b[sq]
                if (p == 0) continue
                val q = r6 or c
                if (p > 0) {
                    mw += MV[p] + MP[p][q]
                    ew += EV[p] + EP[p][q]
                    ph += PW[p]
                    when (p) {
                        1 -> { wpc[c]++; if (r < wpr[c]) wpr[c] = r }
                        2 -> {
                            if (r in 2..4 && c in 1..6 && (b[sq + 15] == 1 || b[sq + 17] == 1)) {
                                mw += 28; ew += 16
                            }
                        }
                        3 -> wb++
                        4 -> wrf = wrf or (1 shl c)
                    }
                    if (agg && p in 2..5) {
                        wAtt += (14 - (abs(r - bkr) + abs(c - bkc))) * TROPISM[p]
                    }
                } else {
                    val a = -p; val fq = q xor 56
                    mb += MV[a] + MP[a][fq]
                    eb += EV[a] + EP[a][fq]
                    ph += PW[a]
                    when (a) {
                        1 -> { bpc[c]++; if (r > bpr[c]) bpr[c] = r }
                        2 -> {
                            if (r in 3..5 && c in 1..6 && (b[sq - 15] == -1 || b[sq - 17] == -1)) {
                                mb += 28; eb += 16
                            }
                        }
                        3 -> bb++
                        4 -> brf = brf or (1 shl c)
                    }
                    if (agg && a in 2..5) {
                        bAtt += (14 - (abs(r - wkr) + abs(c - wkc))) * TROPISM[a]
                    }
                }
            }
        }

        if (agg) {
            mw += (wAtt * 5) / 4; mb += (bAtt * 5) / 4
            ew += wAtt / 2; eb += bAtt / 2
        }

        if (wb >= 2) { mw += 40; ew += 58 }
        if (bb >= 2) { mb += 40; eb += 58 }

        for (c in 0 until 8) {
            if (wpc[c] > 1) { mw -= 14 * (wpc[c] - 1); ew -= 20 * (wpc[c] - 1) }
            if (bpc[c] > 1) { mb -= 14 * (bpc[c] - 1); eb -= 20 * (bpc[c] - 1) }

            if (wpc[c] > 0) {
                val l = if (c > 0) wpc[c - 1] else 0
                val ri = if (c < 7) wpc[c + 1] else 0
                if (l == 0 && ri == 0) { mw -= 15; ew -= 18 }
            }
            if (bpc[c] > 0) {
                val l = if (c > 0) bpc[c - 1] else 0
                val ri = if (c < 7) bpc[c + 1] else 0
                if (l == 0 && ri == 0) { mb -= 15; eb -= 18 }
            }

            if ((wrf and (1 shl c)) != 0) {
                if (wpc[c] == 0) { mw += if (bpc[c] == 0) 30 else 15; ew += 16 }
                if (b[0x10 or c] == 4) { mw += 30; ew += 42 }
                if (agg && abs(c - bkc) <= 1 && wpc[c] == 0) mw += 28
            }
            if ((brf and (1 shl c)) != 0) {
                if (bpc[c] == 0) { mb += if (wpc[c] == 0) 30 else 15; eb += 16 }
                if (b[0x60 or c] == -4) { mb += 30; eb += 42 }
                if (agg && abs(c - wkc) <= 1 && bpc[c] == 0) mb += 28
            }

            val wr = wpr[c]
            if (wr < 7) {
                val bl = if (c > 0) bpr[c - 1] else -1
                val bc = bpr[c]
                val br = if (c < 7) bpr[c + 1] else -1
                if (bl < wr && bc < wr && br < wr) {
                    mw += PMG[7 - wr]; ew += PEG[7 - wr]
                    if ((c > 0 && wpr[c - 1] in (wr - 1)..(wr + 1)) || (c < 7 && wpr[c + 1] in (wr - 1)..(wr + 1))) {
                        mw += 16; ew += 28
                    }
                }
            }
            val brk = bpr[c]
            if (brk > 0) {
                val wl = if (c > 0) wpr[c - 1] else 8
                val wc = wpr[c]
                val wr2 = if (c < 7) wpr[c + 1] else 8
                if (wl > brk && wc > brk && wr2 > brk) {
                    mb += PMG[brk]; eb += PEG[brk]
                    if ((c > 0 && bpr[c - 1] in (brk - 1)..(brk + 1)) || (c < 7 && bpr[c + 1] in (brk - 1)..(brk + 1))) {
                        mb += 16; eb += 28
                    }
                }
            }
        }

        if (wkr == 7 && (wkc >= 5 || wkc <= 2)) {
            val sc = if (wkc >= 5) 5 else 0
            for (c in sc until sc + 3) {
                if (b[0x60 or c] == 1) mw += 18
                else if (b[0x50 or c] == 1) mw += 9
                else mw -= 16
            }
        }
        if (bkr == 0 && (bkc >= 5 || bkc <= 2)) {
            val sc = if (bkc >= 5) 5 else 0
            for (c in sc until sc + 3) {
                if (b[0x10 or c] == -1) mb += 18
                else if (b[0x20 or c] == -1) mb += 9
                else mb -= 16
            }
        }

        if (ph <= 6) {
            val md = abs(wkr - bkr) + abs(wkc - bkc)
            if (ew > eb + 180) {
                val cd = abs(bkr - 3.5) + abs(bkc - 3.5)
                ew += (cd * 18).toInt() + (14 - md) * 8
            } else if (eb > ew + 180) {
                val cd = abs(wkr - 3.5) + abs(wkc - 3.5)
                eb += (cd * 18).toInt() + (14 - md) * 8
            }
        }

        val mp = if (ph > 24) 24 else ph
        val sc = ((mw - mb) * mp + (ew - eb) * (24 - mp)) / 24
        return (if (turn == 1) sc else -sc) + 12
    }

    private fun scoreMoves(base: Int, n: Int, tm: Int, cmv: Int, ply: Int) {
        val so = if (turn == 1) 0 else 4096
        val a1 = k1[ply]; val a2 = k2[ply]
        for (i in 0 until n) {
            val m = mb[base + i]
            sb[base + i] = when {
                m == tm -> 2000000
                (m and FCAP) != 0 -> {
                    val v = abs(b[(m shr 7) and 127]).let { if (it == 0) 1 else it }
                    val a = abs(b[m and 127])
                    1000000 + CAPV[v] * 10 - a
                }
                ((m shr 14) and 15) != 0 -> 950000 + ((m shr 14) and 15) * 10
                m == a1 -> 900000
                m == a2 -> 850000
                m == cmv -> 820000
                else -> history[so + (s64(m and 127) shl 6) + s64((m shr 7) and 127)]
            }
        }
    }

    private fun pickMove(base: Int, i: Int, n: Int): Int {
        var bi = i; var bs = sb[base + i]
        for (j in i + 1 until n) {
            val s = sb[base + j]
            if (s > bs) { bs = s; bi = j }
        }
        if (bi != i) {
            val tm = mb[base + i]
            mb[base + i] = mb[base + bi]
            mb[base + bi] = tm
            sb[base + bi] = sb[base + i]
            sb[base + i] = bs
        }
        return mb[base + i]
    }

    private fun checkTime() {
        if (stopFlag.get() || System.currentTimeMillis() >= endTimeMs) stopped = true
    }

    private fun qs(alphaIn: Int, beta: Int, ply: Int): Int {
        nodes++
        if ((nodes and 2047L) == 0L) checkTime()
        if (stopped) return 0

        var a = alphaIn
        val sp = evalPos()
        if (sp >= beta) return beta
        if (sp + 1050 < a) return a
        if (sp > a) a = sp
        if (ply >= MAX_PLY - 1) return sp

        val base = ply shl 8
        val n = genMoves(ply, true)
        for (i in 0 until n) {
            val m = mb[base + i]
            val v = abs(b[(m shr 7) and 127]).let { if (it == 0) 1 else it }
            val at = abs(b[m and 127])
            sb[base + i] = CAPV[v] * 10 - at
        }

        for (i in 0 until n) {
            val m = pickMove(base, i, n)
            val v = abs(b[(m shr 7) and 127]).let { if (it == 0) 1 else it }
            if (((m shr 14) and 15) == 0 && sp + CAPV[v] + 170 < a) continue
            if (!makeMove(m)) continue
            val sc = -qs(-beta, -a, ply + 1)
            unmakePop(m)
            if (stopped) return 0
            if (sc >= beta) return beta
            if (sc > a) a = sc
        }
        return a
    }

    private fun pvs(depthIn: Int, alphaIn: Int, betaIn: Int, ply: Int, allowNull: Boolean, prevMove: Int): Int {
        nodes++
        if ((nodes and 2047L) == 0L) checkTime()
        if (stopped) return 0

        if (ply > 0 && isRepetition()) {
            return if (mateFocus) (if (turn == rootTurn) -40 else 40) else 0
        }

        var a = max(alphaIn, -MATE + ply)
        val beta = min(betaIn, MATE - ply - 1)
        if (a >= beta) return a

        val inCheck = isAttacked(if (turn == 1) wk else bk, -turn)
        var d = depthIn
        if (inCheck && ply < 46) d++
        if (d <= 0 || ply >= MAX_PLY - 1) return qs(a, beta, ply)

        val pv = (beta - a) > 1
        val idx = (curHash and TT_MASK.toLong()).toInt()
        var tm = 0
        if (ttKey[idx] == curHash) {
            tm = ttMove[idx]
            if (!pv && ttDepth[idx].toInt() >= d) {
                var sc = ttScore[idx].toInt()
                if (sc > MATE - 100) sc -= ply
                else if (sc < -MATE + 100) sc += ply
                when (ttFlag[idx].toInt()) {
                    1 -> return sc
                    2 -> if (sc >= beta) return sc
                    3 -> if (sc <= a) return sc
                }
            }
        }

        var sev = -INF
        if (!inCheck && !pv) {
            sev = evalPos()
            if (d <= 3 && sev - 78 * d >= beta) return sev
            if (d <= 2 && sev + 230 * d <= a) {
                val rq = qs(a, beta, ply)
                if (rq <= a) return rq
            }
            if (allowNull && d >= 3 && sev >= beta && hasNonPawn(turn)) {
                val oe = ep; val oh = curHash
                ep = -1; turn = -turn
                curHash = oh xor zSide xor zEp[if (oe == -1) 64 else s64(oe)] xor zEp[64]
                val r = if (d >= 6) 3 else 2
                val ns = -pvs(d - 1 - r, -beta, -beta + 1, ply + 1, false, 0)
                turn = -turn; ep = oe; curHash = oh
                if (stopped) return 0
                if (ns >= beta) return beta
            }
        }

        val cmv = if (prevMove != 0) counterMove[(s64(prevMove and 127) shl 6) or s64((prevMove shr 7) and 127)] else 0
        val base = ply shl 8
        val n = genMoves(ply, false)
        scoreMoves(base, n, tm, cmv, ply)

        var leg = 0; var bsc = -INF; var bm = 0; val oa = a
        for (i in 0 until n) {
            val m = pickMove(base, i, n)
            val quiet = (m and FCAP) == 0 && ((m shr 14) and 15) == 0

            if (!pv && !inCheck && leg > 0 && quiet && d <= 2) {
                if (d == 1 && sev + 150 <= a) continue
                if (d == 2 && leg >= 6 && sev + 300 <= a) continue
            }

            if (!makeMove(m)) continue
            leg++

            var ext = 0
            val toSq = (m shr 7) and 127
            val movedP = abs(b[toSq])
            if (movedP == 1 && ply < 40) {
                val r = toSq shr 4
                if (r == 1 || r == 6) ext = 1
            }

            var sc: Int
            if (leg == 1) {
                sc = -pvs(d - 1 + ext, -beta, -a, ply + 1, true, m)
            } else {
                var red = 0
                if (d >= 3 && leg >= 3 && quiet && !inCheck && ext == 0) {
                    red = LMR[min(d, 63)][min(leg, 63)]
                    if (pv && red > 0) red--
                }
                sc = -pvs(d - 1 - red + ext, -a - 1, -a, ply + 1, true, m)
                if (!stopped && sc > a && red > 0) sc = -pvs(d - 1 + ext, -a - 1, -a, ply + 1, true, m)
                if (!stopped && sc > a && sc < beta) sc = -pvs(d - 1 + ext, -beta, -a, ply + 1, true, m)
            }
            unmakePop(m)
            if (stopped) return 0

            if (sc > bsc) { bsc = sc; bm = m }
            if (sc > a) {
                a = sc
                if (a >= beta) {
                    if (quiet) {
                        if (k1[ply] != m) { k2[ply] = k1[ply]; k1[ply] = m }
                        if (prevMove != 0) {
                            counterMove[(s64(prevMove and 127) shl 6) or s64((prevMove shr 7) and 127)] = m
                        }
                        val so = if (turn == 1) 0 else 4096
                        val hi = so + (s64(m and 127) shl 6) + s64((m shr 7) and 127)
                        history[hi] = min(150000, history[hi] + d * d)
                    }
                    break
                }
            }
        }

        if (leg == 0) return if (inCheck) (-MATE + ply) else 0

        var st = bsc
        if (st > MATE - 100) st += ply
        else if (st < -MATE + 100) st -= ply

        if (ttKey[idx] != curHash || d >= ttDepth[idx].toInt() || ttFlag[idx].toInt() != 1) {
            ttKey[idx] = curHash
            ttDepth[idx] = d.toByte()
            ttScore[idx] = st.toShort()
            ttMove[idx] = bm
            ttFlag[idx] = (if (bsc <= oa) 3 else if (bsc >= beta) 2 else 1).toByte()
        }
        return bsc
    }

    private fun applyUci(u: String) {
        val n = genMoves(0, false)
        for (i in 0 until n) {
            val m = mb[i]
            if (toUci(m) == u && makeMove(m)) return
        }
    }

    private fun runSearch(
        baseFen: String?,
        movesUci: List<String>,
        timeMs: Long,
        onInfo: (Int, String, Int, String, Long, Long, Long) -> Unit,
        onEnd: (Int, String, Int, String?, Long, Long, Long) -> Unit
    ) {
        if (!baseFen.isNullOrBlank()) loadFen(baseFen) else initBoard()
        for (u in movesUci) if (u.isNotBlank()) applyUci(u.trim())
        rootTurn = turn

        val pn = genMoves(0, false)
        val rootMoves = IntArray(pn)
        var rn = 0
        for (i in 0 until pn) {
            val m = mb[i]
            if (makeMove(m)) {
                unmakePop(m)
                rootMoves[rn++] = m
            }
        }

        if (rn == 0) {
            onEnd(0, "cp", 0, null, 0L, 0L, 0L)
            return
        }
        if (rn == 1) {
            val ev = evalPos()
            onEnd(1, "cp", if (turn == 1) ev else -ev, toUci(rootMoves[0]), 1L, 1L, 1000L)
            return
        }

        k1.fill(0); k2.fill(0)
        for (i in history.indices) history[i] = history[i] shr 2

        nodes = 0L
        stopped = false
        val t0 = System.currentTimeMillis()
        endTimeMs = t0 + max(80L, timeMs)

        var bm = rootMoves[0]
        var bsc = 0
        var dp = 0

        for (dep in 1..48) {
            for (i in 1 until rn) {
                if (rootMoves[i] == bm) {
                    rootMoves[i] = rootMoves[0]
                    rootMoves[0] = bm
                    break
                }
            }

            var win = if (dep >= 4) 30 else INF
            var a = if (dep >= 4) bsc - win else -INF
            var bt = if (dep >= 4) bsc + win else INF
            var cm = rootMoves[0]
            var csc = -INF

            while (true) {
                var ta = a
                cm = rootMoves[0]; csc = -INF
                for (i in 0 until rn) {
                    val m = rootMoves[i]
                    makeMove(m)
                    var sc: Int
                    if (i == 0) {
                        sc = -pvs(dep - 1, -bt, -ta, 1, true, m)
                    } else {
                        sc = -pvs(dep - 1, -ta - 1, -ta, 1, true, m)
                        if (!stopped && sc > ta) sc = -pvs(dep - 1, -bt, -ta, 1, true, m)
                    }
                    unmakePop(m)
                    if (stopped) break
                    if (sc > csc) { csc = sc; cm = m }
                    if (sc > ta) ta = sc
                }
                if (stopped) break
                if (csc <= a) { a = max(-INF, a - win * 3); win *= 3; continue }
                if (csc >= bt) { bt = min(INF, bt + win * 3); win *= 3; continue }
                break
            }

            if (stopped && dep > 1) break

            bm = cm; bsc = csc; dp = dep
            val el = max(1L, System.currentTimeMillis() - t0)
            val nps = (nodes * 1000L) / el
            val isMate = abs(bsc) > MATE - 100
            val st = if (isMate) "mate" else "cp"
            val sv = if (isMate) {
                val mv = max(1, ceil((MATE - abs(bsc)) / 2.0).toInt())
                (if (bsc > 0) mv else -mv) * (if (turn == 1) 1 else -1)
            } else {
                if (turn == 1) bsc else -bsc
            }

            onInfo(dep, st, sv, toUci(bm), nodes, el, nps)
            if (isMate || System.currentTimeMillis() - t0 >= (timeMs * 0.88).toLong()) break
        }

        val el = max(1L, System.currentTimeMillis() - t0)
        val nps = (nodes * 1000L) / el
        val isMate = abs(bsc) > MATE - 100
        val st = if (isMate) "mate" else "cp"
        val sv = if (isMate) {
            val mv = max(1, ceil((MATE - abs(bsc)) / 2.0).toInt())
            (if (bsc > 0) mv else -mv) * (if (turn == 1) 1 else -1)
        } else {
            if (turn == 1) bsc else -bsc
        }

        onEnd(max(1, dp), st, sv, toUci(bm), nodes, el, nps)
    }
}
