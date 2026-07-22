package com.weike.ime.workflow

/** The task selected for one "Vertick Knows" voice command. */
sealed interface WorkflowIntent {
    data class Answer(val question: String) : WorkflowIntent
    data class ExactReplace(val source: String, val replacement: String) : WorkflowIntent
    data class TargetedReplace(val target: WorkflowReplaceTarget, val replacement: String) : WorkflowIntent
    data class Continue(val instruction: String) : WorkflowIntent
    data class Summarize(val instruction: String) : WorkflowIntent
    data class Expand(val instruction: String) : WorkflowIntent
    data class Translate(val instruction: String) : WorkflowIntent
    data class Proofread(val instruction: String) : WorkflowIntent
    data class Format(val instruction: String) : WorkflowIntent
    data class Extract(val instruction: String) : WorkflowIntent
    data class Reply(val instruction: String) : WorkflowIntent
    data class Rewrite(val instruction: String) : WorkflowIntent
    data class Polish(val instruction: String) : WorkflowIntent
    data class Ambiguous(val command: String) : WorkflowIntent
}

/** A text range named by the user instead of quoted literally in a command. */
enum class WorkflowReplaceTarget {
    PARENTHESES_CONTENT,
    QUOTED_CONTENT,
    SELECTED_TEXT
}

enum class WorkflowTaskType {
    ANSWER,
    REWRITE,
    POLISH;

    companion object {
        fun fromModel(value: String): WorkflowTaskType? = entries.firstOrNull {
            it.name.equals(value.trim().uppercase(), ignoreCase = true)
        }
    }
}

/**
 * Keeps obvious commands local so a simple edit never waits for a model call.
 * Ambiguous editing language is classified remotely by the configured text model.
 */
object WorkflowRouter {
    private val exactReplace = Regex(
        "^\\s*(?:请|帮我)?\\s*(?:把|将)\\s*(.+?)\\s*(?:改成|替换成|换成)\\s*(.+?)\\s*[。！？!?]?$"
    )
    private val polishHints = listOf(
        "润色", "专业一点", "正式一点", "简洁一点", "友好一点", "自然一点", "通顺一点", "优化表达", "优化一下语气"
    )
    private val continuationHints = listOf("续写", "接着写", "继续写", "往下写", "继续往下")
    private val replyHints = listOf("生成回复", "帮我回复", "回复一下", "回一条", "礼貌回复", "强硬回复")
    private val extractionHints = listOf("提取", "抽取", "找出", "提炼关键词", "列出时间", "列出任务")
    private val formatHints = listOf("整理格式", "格式化", "转成列表", "转为列表", "待办", "表格", "markdown", "标题层级")
    private val translationHints = listOf("翻译", "译成", "翻成")
    private val summaryHints = listOf("总结", "概括", "摘要", "提炼", "一句话总结")
    private val expansionHints = listOf("扩写", "展开写", "详细一点", "丰富一点", "补充细节")
    private val proofreadingHints = listOf("改错", "校对", "纠错", "错别字", "语病")
    private val rewriteHints = listOf(
        "改写", "重写", "删除", "删掉", "补充", "添加", "扩写", "缩短", "精简", "调整", "修改", "改为"
    )
    private val ambiguousHints = listOf("处理一下", "优化一下", "帮我弄", "帮我改", "整理一下")

    fun route(command: String): WorkflowIntent {
        val normalized = command.trim()
        if (normalized.isBlank()) return WorkflowIntent.Answer("")
        exactReplace.matchEntire(normalized)?.let { match ->
            val source = stripQuotes(match.groupValues[1])
            val replacement = stripQuotes(match.groupValues[2])
            if (source.isNotBlank() && replacement.isNotBlank()) {
                replacementTarget(source)?.let { target ->
                    return WorkflowIntent.TargetedReplace(target, replacement)
                }
                return WorkflowIntent.ExactReplace(source, replacement)
            }
        }
        if (continuationHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Continue(normalized)
        }
        if (replyHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Reply(normalized)
        }
        if (extractionHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Extract(normalized)
        }
        if (formatHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Format(normalized)
        }
        if (translationHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Translate(normalized)
        }
        if (summaryHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Summarize(normalized)
        }
        if (expansionHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Expand(normalized)
        }
        if (proofreadingHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Proofread(normalized)
        }
        if (polishHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Polish(normalized)
        }
        if (rewriteHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Rewrite(normalized)
        }
        if (ambiguousHints.any { normalized.contains(it, ignoreCase = true) }) {
            return WorkflowIntent.Ambiguous(normalized)
        }
        return WorkflowIntent.Answer(normalized)
    }

    fun fromClassification(command: String, type: WorkflowTaskType): WorkflowIntent = when (type) {
        WorkflowTaskType.ANSWER -> WorkflowIntent.Answer(command)
        WorkflowTaskType.REWRITE -> WorkflowIntent.Rewrite(command)
        WorkflowTaskType.POLISH -> WorkflowIntent.Polish(command)
    }

    private fun stripQuotes(value: String): String = value.trim()
        .removePrefix("\"").removeSuffix("\"")
        .removePrefix("“").removeSuffix("”")
        .removePrefix("'").removeSuffix("'")
        .trim()

    private fun replacementTarget(source: String): WorkflowReplaceTarget? {
        val normalized = source.filterNot(Char::isWhitespace)
        return when {
            normalized in setOf("括号里的内容", "括号内的内容", "括号内容", "圆括号里的内容", "小括号里的内容") ||
                (normalized.contains("括号") && (normalized.contains("里面") || normalized.contains("内容"))) ->
                WorkflowReplaceTarget.PARENTHESES_CONTENT
            normalized in setOf("引号里的内容", "引号内的内容", "引号内容", "双引号里的内容", "单引号里的内容") ||
                (normalized.contains("引号") && (normalized.contains("里面") || normalized.contains("内容"))) ->
                WorkflowReplaceTarget.QUOTED_CONTENT
            normalized in setOf("选中的内容", "选中文本", "选中内容") ->
                WorkflowReplaceTarget.SELECTED_TEXT
            else -> null
        }
    }
}
