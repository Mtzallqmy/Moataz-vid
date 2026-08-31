package com.moatazvid.ai.editor

import com.moatazvid.ai.provider.*

enum class AiIntent {
    ANALYZE_PROJECT, EDIT_PROJECT, FIND_CONTENT, CAPTION_EDIT, AUDIO_EDIT, VISUAL_EDIT, STRUCTURE_EDIT,
    EXPORT_REQUEST, PROJECT_CONSTRAINT, UNDO_REQUEST, REDO_REQUEST, EXPLAIN_EDIT, CLARIFICATION_REQUIRED,
}
data class IntentResult(val intent: AiIntent, val confidence: Double, val entities: Map<String, String> = emptyMap(), val deterministic: Boolean)

class ArabicIntentClassifier {
    private val whitespace = Regex("\\s+")
    fun classify(message: String, hasPendingPlan: Boolean = false): IntentResult {
        val text = message.trim().lowercase().replace(whitespace, " ")
        fun result(intent: AiIntent, confidence: Double = 0.96, vararg entities: Pair<String, String>) = IntentResult(intent, confidence, mapOf(*entities), true)
        if (text.matches(Regex("^(تراجع|ارجع|undo)( عن آخر تعديل( الذكاء)?| آخر تعديل)?[.!؟]?$"))) return result(AiIntent.UNDO_REQUEST)
        if (text.matches(Regex("^(أعد|اعادة|إعادة|redo|رجع التعديل)[.!؟]?$"))) return result(AiIntent.REDO_REQUEST)
        if (text.contains("وش عدلت") || text.contains("ماذا عدلت") || text.contains("اشرح التعديل") || text.contains("ما الذي ستغيره")) return result(AiIntent.EXPLAIN_EDIT)
        if ((text.contains("لا تحذف") || text.contains("حافظ على") || text.contains("بدون موسيقى")) && !hasPendingPlan)
            return result(AiIntent.PROJECT_CONSTRAINT, entities = arrayOf("constraintText" to message))
        if (hasPendingPlan && (text.contains("لكن") || text.contains("لا تحذف") || text.contains("رجع") || text.contains("عدّل الخطة"))) return result(AiIntent.EDIT_PROJECT, 0.94, "revisePending" to "true")
        if (text.startsWith("أين") || text.startsWith("وين") || text.contains("ابحث عن") || text.contains("where did")) return result(AiIntent.FIND_CONTENT, entities = arrayOf("query" to extractSearchTerm(text)))
        if (text.contains("caption") || text.contains("كابشن") || text.contains("ترجمة")) return result(AiIntent.CAPTION_EDIT)
        if (text.contains("موسيقى") || text.contains("الصوت") || text.contains("ضوضاء") || text.contains("audio") || text.contains("volume")) return result(AiIntent.AUDIO_EDIT)
        if (text.contains("9:16") || text.contains("عمودي") || text.contains("زوم") || text.contains("zoom") || text.contains("سطوع")) return result(AiIntent.VISUAL_EDIT)
        if (text.contains("للبداية") || text.contains("في البداية") || text.contains("hook") || text.contains("هوك") || text.contains("ريل") || text.contains("reel")) return result(AiIntent.STRUCTURE_EDIT)
        if (text.contains("صدر") || text.contains("تصدير") || text.contains("1080") || text.contains("fps")) return result(AiIntent.EXPORT_REQUEST)
        if (text.contains("احذف") || text.contains("اختصر") || text.contains("خليه") || text.contains("خذ أفضل") || text.contains("اسرع") || text.contains("أسرع") || text.contains("split") || text.contains("trim")) {
            val duration = Regex("(\\d+)\\s*(ثانية|ثوان|دقيقة|دقائق|s|sec|min)").find(text)?.value
            return result(AiIntent.EDIT_PROJECT, entities = duration?.let { arrayOf("targetDuration" to it) } ?: emptyArray())
        }
        if (text.startsWith("ما ") || text.startsWith("ماذا") || text.contains("رأيك") || text.endsWith("؟")) return result(AiIntent.ANALYZE_PROJECT, 0.85)
        return result(AiIntent.CLARIFICATION_REQUIRED, 0.45)
    }
    private fun extractSearchTerm(text: String): String = text.replace(Regex("^(أين|وين|ابحث عن|where did)\\s*"), "").replace(Regex("(قلت|ذكرت|تكلمت عن)"), "").trim(' ', '؟', '?')
}

enum class AiConversationRole { USER, ASSISTANT, TOOL, SYSTEM_SUMMARY }
enum class AiMessageStatus { PENDING, STREAMING, COMPLETED, FAILED, CANCELLED }
data class AiMessage(
    val id: AiMessageId, val role: AiConversationRole, val text: String, val timestampEpochMs: Long,
    val providerId: ProviderId? = null, val model: String? = null, val relatedProjectRevision: Long,
    val relatedTransactionId: String? = null, val status: AiMessageStatus, val usage: LlmUsage? = null,
    val visibleReasoningSummary: String? = null, val toolSummaries: List<String> = emptyList(),
)
data class AiConversation(val id: ConversationId, val projectId: com.moatazvid.core.ProjectId, val messages: List<AiMessage>, val createdAtEpochMs: Long, val updatedAtEpochMs: Long)
data class ConversationSession(val conversation: AiConversation, val activeRequestId: RequestId?, val pendingEditId: PendingEditId?, val compactMemory: List<String>)
data class ConversationContext(val recentMessages: List<AiMessage>, val memorySummary: String?, val pendingPlan: EditPlan?)
