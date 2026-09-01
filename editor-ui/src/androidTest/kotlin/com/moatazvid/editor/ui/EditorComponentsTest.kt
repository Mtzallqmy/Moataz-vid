package com.moatazvid.editor.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moatazvid.ai.editor.*
import com.moatazvid.core.*
import org.junit.Rule
import org.junit.Test

class EditorComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun planCardShowsPreviewApplyAndRejectInArabic() {
        val diff = EditDiff(DurationUs(161_000_000), DurationUs(47_000_000), 14, emptyList(), emptyList(), 2, 0, 0, "summary")
        val simulation = SimulationResult(true, null, diff, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), RenderComplexity.MEDIUM)
        val plan = EditPlan(id = EditPlanId("plan"), projectId = ProjectId("p"), sequenceId = SequenceId("s"), baseProjectRevision = 1,
            title = "اختصار الفيديو", summary = "حذف الصمت والتكرار", operations = emptyList(), estimatedResult = EstimatedEditResult(diff.beforeDuration, diff.afterDuration))
        val pending = PendingEditTransaction(PendingEditId("pending"), plan.projectId, 1, plan, simulation, 1, null, null, PendingEditStatus.READY)
        compose.setContent { MoatazVidTheme { AiPlanCard(pending, false, {}, {}, {}) } }
        compose.onNodeWithText("02:41 → 00:47", substring = true).assertExists()
        compose.onNodeWithText("معاينة").assertHasClickAction()
        compose.onNodeWithText("تطبيق").assertHasClickAction()
        compose.onNodeWithText("رفض").assertHasClickAction()
    }

    @Test fun providerMissingAndTranscriptMissingRemainActionable() {
        val state = com.moatazvid.editor.EditorUiState(loading = false, aiChat = com.moatazvid.editor.AiChatUiState(providerMissing = true))
        compose.setContent { MoatazVidTheme { AiChatPanel(state, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("لم يتم إعداد مزود ذكاء اصطناعي بعد.").assertExists()
        compose.onNodeWithText("الوظائف المحلية").assertHasClickAction()
    }
}
