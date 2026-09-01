package com.moatazvid.editor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moatazvid.core.*
import com.moatazvid.editor.*
import kotlinx.coroutines.launch

class EditorViewModel(private val controller: EditorController) : ViewModel() {
    val state = controller.state
    fun open(projectId: ProjectId) = viewModelScope.launch { controller.open(projectId) }
    fun playPause() = viewModelScope.launch { controller.togglePlayback() }
    fun seek(time: TimeUs) = viewModelScope.launch { controller.seek(time) }
    fun select(clipId: ClipId?) = controller.selectClip(clipId)
    fun split() = viewModelScope.launch { controller.splitSelected() }
    fun delete() = viewModelScope.launch { controller.deleteSelected() }
    fun undo() = viewModelScope.launch { controller.undo() }
    fun redo() = viewModelScope.launch { controller.redo() }
    fun send(message: String) = controller.sendAiMessage(message)
    fun cancelAi() = controller.cancelAiRequest()
    fun previewPending(enabled: Boolean) = controller.previewPending(enabled)
    fun applyPending() = viewModelScope.launch { controller.applyPending() }
    fun rejectPending() = viewModelScope.launch { controller.rejectPending() }
    fun revise(feedback: String) = controller.revisePending(feedback)
    fun searchTranscript(query: String) = controller.searchTranscript(query)
    fun seekTranscript(hit: com.moatazvid.speech.TranscriptSearchHit) = viewModelScope.launch { controller.seekTranscript(hit) }
    fun zoom(factor: Double, focal: Double) = controller.zoom(factor, focal)
    fun beginTrim(clipId: ClipId, edge: TrimEdge) = controller.beginTrim(clipId, edge)
    fun updateTrim(sourceTime: TimeUs) = controller.updateTrim(sourceTime)
    fun commitTrim() = viewModelScope.launch { controller.commitTrim() }
    fun move(clipId: ClipId, trackId: TrackId, index: Int) { controller.selectClip(clipId); viewModelScope.launch { controller.moveSelected(trackId, index) } }
    override fun onCleared() { viewModelScope.launch { controller.close() } }
}
