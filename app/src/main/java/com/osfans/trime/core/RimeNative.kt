package com.osfans.trime.core

/**
 * Minimal ABI-compatible bridge for Trime's librime_jni build. The native
 * library resolves these class names during JNI_OnLoad, so keep them stable.
 */
object Rime {
    init {
        System.loadLibrary("rime_jni")
    }

    @JvmStatic external fun startupRime(sharedDir: String, userDir: String, versionName: String, fullCheck: Boolean)
    @JvmStatic external fun exitRime()
    @JvmStatic external fun deployRimeSchemaFile(schemaFile: String): Boolean
    @JvmStatic external fun syncRimeUserData(): Boolean
    @JvmStatic external fun processRimeKey(keycode: Int, mask: Int): Boolean
    @JvmStatic external fun clearRimeComposition()
    @JvmStatic external fun getRimeCommit(): CommitProto
    @JvmStatic external fun getRimeContext(): ContextProto
    @JvmStatic external fun getRimeStatus(): StatusProto
    @JvmStatic external fun getCurrentRimeSchema(): String
    @JvmStatic external fun selectRimeSchema(schemaId: String): Boolean
    @JvmStatic external fun getRimeCandidates(startIndex: Int, limit: Int): Array<CandidateProto>
    @JvmStatic external fun selectRimeCandidate(index: Int, global: Boolean): Boolean

    // Required by the native notification callback. Deployment state is polled by the Kotlin adapter.
    @JvmStatic fun handleRimeMessage(type: Int, params: Array<Any>) = Unit
}

data class CommitProto(val text: String?)

data class CandidateProto(val text: String, val comment: String, val label: String)

data class CompositionProto(
    val length: Int = 0,
    val cursorPos: Int = 0,
    val selStart: Int = 0,
    val selEnd: Int = 0,
    val preedit: String? = null,
    val commitTextPreview: String? = null
)

data class MenuProto(
    val pageSize: Int = 0,
    val pageNumber: Int = 0,
    val isLastPage: Boolean = false,
    val highlightedCandidateIndex: Int = 0,
    val candidates: Array<CandidateProto> = emptyArray(),
    val selectKeys: String? = null,
    val selectLabels: Array<String> = emptyArray()
)

data class ContextProto(
    val composition: CompositionProto = CompositionProto(),
    val menu: MenuProto = MenuProto(),
    val input: String = "",
    val caretPos: Int = 0
)

data class StatusProto(
    val schemaId: String = "",
    val schemaName: String = "",
    val isDisabled: Boolean = true,
    val isComposing: Boolean = false,
    val isAsciiMode: Boolean = true,
    val isFullShape: Boolean = false,
    val isSimplified: Boolean = false,
    val isTraditional: Boolean = false,
    val isAsciiPunct: Boolean = true
)

data class SchemaItem(val id: String, val name: String = "")

data class RimeKeyEvent(val value: Int, val modifiers: Int, val repr: String)
