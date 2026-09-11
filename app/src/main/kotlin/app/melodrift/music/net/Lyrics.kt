package app.melodrift.music.net

import androidx.compose.runtime.Immutable
import kotlin.math.abs

/**
 * 歌词：LRC 解析 + 原文/译文配对，全部收口在这里。
 *
 * 解析与配对都在 IO 线程完成（`NcmApi.lyric()` 内部调用），直接产出 UI 可渲染的
 * [LyricRow]；UI 侧不再碰解析/配对 —— 之前配对逻辑写在 PlayerScreen 里，
 * "歌词行"这个概念同时散落在 Models / PlayerController / PlayerScreen 三处。
 */

/** 一条 LRC 时间戳行（解析中间结果） */
@Immutable
data class LyricLine(val timeMs: Long, val text: String)

/** 渲染用歌词行：原文 + 对应译文（该行没翻到 / 整首无译文 → null） */
@Immutable
data class LyricRow(
    val timeMs: Long,
    val text: String,
    val translation: String?
)

/**
 * 制作信息行（作词 / 作曲 / 编曲 / 制作人 / OP / SP / Lyrics / Written by…）。
 *
 * 网易云把这些塞在歌词开头（`[00:00.00] 作词 : …`、`[00:00.719] 作曲 : …`），
 * 它们**不是歌词正文**：既不该参与译文配对（会把真正那句的译文抢走），
 * 也不该在歌词页里被当成"当前行"高亮。
 */
/** 制作信息行的前缀关键词（正则片段，`?` / `\s` 等按正则解释） */
private val CREDIT_KEYS = listOf(
    "作词", "作曲", "编曲", "制作人", "出品", "发行", "制作", "录音", "混音", "母带",
    "和声", "和音", "配唱", "吉他", "钢琴", "弦乐", "合成器", "编程", "人声",
    "OP", "SP",
    "Lyrics?", "Words?", "Written(?:\\s+by)?", "Composed(?:\\s+by)?", "Music",
    "Composing", "Produced(?:\\s+by)?", "Producer", "Arrang\\w*", "Vocal\\w*",
    "Guitar", "Piano", "Strings?", "Mixed", "Mixing", "Mastering", "Engineer", "Recorded"
)

private val CREDIT_REGEX = Regex(
    "^\\s*(?:" + CREDIT_KEYS.joinToString("|") + ")\\s*[:：]",
    RegexOption.IGNORE_CASE
)

fun isCreditLine(text: String): Boolean = CREDIT_REGEX.containsMatchIn(text)

object Lyrics {

    /**
     * 原文与译文允许的最大时间偏差。
     *
     * 实测：绝大多数歌译文时间戳与原文**完全相同**（Yesterday Once More / Lemon /
     * Shape of You 偏差 0ms），只有少数（玫瑰少年）偏到 580ms。取 700ms 既能覆盖
     * 这种作者手抖，又不会让 2.157s 的「制作人」抢走 2.879s 那句的译文
     * （那对偏差是 722ms，必须落在容差之外）。
     */
    private const val TOLERANCE_MS = 700L

    private val TIME_REGEX = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 解析 + 配对一步到位；原文为空返回空表（UI 显示「暂无歌词」） */
    fun build(lrcText: String?, translatedText: String?): List<LyricRow> {
        val lines = parse(lrcText)
        if (lines.isEmpty()) return emptyList()
        val trans = parse(translatedText)
        if (trans.isEmpty()) return lines.map { LyricRow(it.timeMs, it.text, null) }
        val aligned = align(lines, trans)
        return lines.mapIndexed { i, l -> LyricRow(l.timeMs, l.text, aligned[i]) }
    }

    /**
     * 解析 LRC。
     *
     * - 支持一行多个时间戳（`[00:01.00][00:02.00]词`）→ 展开成多条
     * - 无时间戳的行（`[by:xx]`、纯注释）与空文本行丢弃
     * - 按时间升序（`sortedBy` 稳定，多时间戳展开顺序保持原样）
     * - `(time, text)` 完全相同的条目去重（网易云偶有重复下发）
     */
    fun parse(lrc: String?): List<LyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        val out = ArrayList<LyricLine>()
        for (raw in lrc.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val stamps = TIME_REGEX.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val text = line.replace(TIME_REGEX, "").trim()
            if (text.isEmpty()) continue
            for (m in stamps) {
                val min = m.groupValues[1].toIntOrNull() ?: continue
                val sec = m.groupValues[2].toIntOrNull() ?: continue
                val frac = m.groupValues[3].ifEmpty { "0" }.padEnd(3, '0').take(3)
                val ms = frac.toIntOrNull() ?: continue
                out.add(LyricLine(min * 60_000L + sec * 1000L + ms, text))
            }
        }
        return out.sortedBy { it.timeMs }.distinct()
    }

    /**
     * 原文 ↔ 译文**一对一**配对，返回与 [lines] 等长的数组（无译文处为 null）。
     *
     * 为什么不能用"最后一条 `timeMs <= t` 的译文"：网易云经常只翻副歌
     * （玫瑰少年 72 行原文 / 5 行译文），那样最后一条译文会一路漏到歌尾，
     * 后半段每行都显示同一句译文。
     *
     * 三步：
     * 1. 制作信息行（原文与译文两侧）一律不参与配对
     * 2. 先按时间戳**精确**配对 —— 绝大多数歌到这里就全配完了
     * 3. 剩下的按时间差从小到大做一对一贪心，超过 [TOLERANCE_MS] 的直接不配
     */
    fun align(lines: List<LyricLine>, trans: List<LyricLine>): List<String?> {
        val out = arrayOfNulls<String>(lines.size)
        val candidates = ArrayList<LyricLine>(trans.size)
        for (t in trans) if (t.text.isNotBlank() && !isCreditLine(t.text)) candidates.add(t)
        if (candidates.isEmpty()) return List(lines.size) { null }

        val used = BooleanArray(candidates.size)

        // ① 精确时间戳配对
        val byTime = HashMap<Long, ArrayList<Int>>(candidates.size * 2)
        candidates.forEachIndexed { idx, t ->
            byTime.getOrPut(t.timeMs) { ArrayList(1) }.add(idx)
        }
        lines.forEachIndexed { i, l ->
            if (isCreditLine(l.text)) return@forEachIndexed
            val bucket = byTime[l.timeMs] ?: return@forEachIndexed
            val idx = bucket.firstOrNull { !used[it] } ?: return@forEachIndexed
            used[idx] = true
            out[i] = candidates[idx].text.trim()
        }

        // ② 剩余按时间差升序做一对一贪心（覆盖玫瑰少年那种时间戳整体偏移的歌）
        data class Candidate(val delta: Long, val lineIndex: Int, val transIndex: Int)
        val pairs = ArrayList<Candidate>()
        lines.forEachIndexed { i, l ->
            if (out[i] != null || isCreditLine(l.text)) return@forEachIndexed
            candidates.forEachIndexed { j, t ->
                if (used[j]) return@forEachIndexed
                val d = abs(t.timeMs - l.timeMs)
                if (d <= TOLERANCE_MS) pairs.add(Candidate(d, i, j))
            }
        }
        pairs.sortBy { it.delta }
        for (p in pairs) {
            if (out[p.lineIndex] == null && !used[p.transIndex]) {
                used[p.transIndex] = true
                out[p.lineIndex] = candidates[p.transIndex].text.trim()
            }
        }
        return out.toList()
    }
}
