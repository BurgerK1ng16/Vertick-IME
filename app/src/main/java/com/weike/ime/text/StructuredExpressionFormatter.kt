package com.weike.ime.text

/**
 * Deterministic final pass for spoken numbered lists. Models are still used for
 * wording, but a list must not silently collapse into one paragraph when a
 * model omits the requested newlines.
 */
object StructuredExpressionFormatter {
    private val marker = Regex("第[一二三四五六七八九十百千万两0-9]+是|第[一二三四五六七八九十百千万两0-9]+|[一二三四五六七八九十]+是|首先|其次|然后|最后|一方面|另一方面")
    private val introCount = Regex("[一二三四五六七八九十两0-9]+件事|(?:有|包括)(?:以下)?(?:几|[一二三四五六七八九十两0-9]+)点")
    private val delimitedIntro = Regex("(?:包括|包含|分别是|分为|原因有|优点有|缺点有|有以下)(?:[一二三四五六七八九十两0-9]+点)?[:：]")
    private val listLine = Regex("(?m)^\\s*\\d+[.、]")
    private val trimLeading = Regex("^[，,、；;：:\\s]+")
    private val trimTrailing = Regex("[，,、；;：:\\s]+$")

    fun needsStructure(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        val markerCount = marker.findAll(compact).count()
        val delimiterCount = compact.count { it == '、' || it == ',' || it == '，' }
        return markerCount >= 2 ||
            (markerCount >= 1 && introCount.containsMatchIn(compact)) ||
            (delimitedIntro.containsMatchIn(compact) && delimiterCount >= 1) ||
            (compact.contains("一方面") && compact.contains("另一方面"))
    }

    /**
     * Preserves a model result that has already produced a numbered list.
     * Otherwise prefers the model's content when it can be parsed, then falls
     * back to the original ASR transcript so no spoken item is lost.
     */
    fun enforce(source: String, polished: String): String {
        if (!needsStructure(source)) return polished
        if (listLine.containsMatchIn(polished)) return polished
        return parse(polished)?.render() ?: parse(source)?.render() ?: polished
    }

    private fun parse(text: String): ParsedList? {
        val matches = marker.findAll(text).toList()
        if (matches.size < 2) return parseDelimited(text)
        val title = text.substring(0, matches.first().range.first)
            .replace(trimTrailing, "")
            .trim()
        val items = matches.mapIndexedNotNull { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
            text.substring(match.range.last + 1, end)
                .replace(trimLeading, "")
                .replace(trimTrailing, "")
                .trim()
                .takeIf { it.isNotBlank() }
        }
        return items.takeIf { it.size >= 2 }?.let { ParsedList(title, it) }
    }

    private fun parseDelimited(text: String): ParsedList? {
        val intro = delimitedIntro.find(text) ?: return null
        val title = text.substring(0, intro.range.first).replace(trimTrailing, "").trim()
            .ifBlank { intro.value.removeSuffix(":").removeSuffix("：").trim() }
        val body = text.substring(intro.range.last + 1)
            .replace(trimTrailing, "")
            .trim()
        val items = body.split(Regex("[、，,]"))
            .map { it.replace(trimLeading, "").replace(trimTrailing, "").trim() }
            .filter { it.isNotBlank() }
        return items.takeIf { it.size >= 2 }?.let { ParsedList(title, it) }
    }

    private data class ParsedList(val title: String, val items: List<String>) {
        fun render(): String = buildString {
            if (title.isNotBlank()) append(title.trimEnd('：', ':')).append('：').append('\n')
            items.forEachIndexed { index, item -> append(index + 1).append('.').append(item).append('\n') }
        }.trimEnd()
    }
}
