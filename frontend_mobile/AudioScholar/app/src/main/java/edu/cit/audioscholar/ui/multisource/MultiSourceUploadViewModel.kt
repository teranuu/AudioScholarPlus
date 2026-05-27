package edu.cit.audioscholar.ui.multisource

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.cit.audioscholar.data.remote.dto.MultiSourceJobDto
import edu.cit.audioscholar.domain.repository.RemoteAudioRepository
import edu.cit.audioscholar.ui.components.OutputTypeOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SelectedSourceFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?
)

data class MultiSourceUploadUiState(
    val title: String = "",
    val description: String = "",
    val selectedOutputType: String? = null,
    val files: List<SelectedSourceFile> = emptyList(),
    val isUploading: Boolean = false,
    val error: String? = null,
    val job: MultiSourceJobDto? = null
) {
    val canUpload: Boolean
        get() = !isUploading &&
                title.isNotBlank() &&
                selectedOutputType != null &&
                files.size in 2..5
}

@HiltViewModel
class MultiSourceUploadViewModel @Inject constructor(
    private val application: Application,
    private val remoteAudioRepository: RemoteAudioRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MultiSourceUploadUiState())
    val uiState: StateFlow<MultiSourceUploadUiState> = _uiState.asStateFlow()

    fun onTitleChanged(value: String) {
        _uiState.update { it.copy(title = value, error = null) }
    }

    fun onDescriptionChanged(value: String) {
        _uiState.update { it.copy(description = value, error = null) }
    }

    fun onOutputTypeSelected(value: String) {
        _uiState.update { it.copy(selectedOutputType = value, error = null) }
    }

    fun onFilesSelected(uris: List<Uri>) {
        viewModelScope.launch {
            val selected = uris.take(5).mapNotNull { uri -> inspectUri(uri) }
            val invalid = selected.firstOrNull { file ->
                val type = file.mimeType.orEmpty()
                !type.startsWith("audio/") && !type.startsWith("video/")
            }
            _uiState.update {
                when {
                    uris.size !in 2..5 -> it.copy(error = "Select 2 to 5 audio or video files.", files = selected, job = null)
                    invalid != null -> it.copy(error = "${invalid.displayName} is not an audio or video file.", files = selected, job = null)
                    else -> it.copy(files = selected, error = null, job = null)
                }
            }
        }
    }

    fun removeFile(uri: Uri) {
        _uiState.update { it.copy(files = it.files.filterNot { file -> file.uri == uri }, job = null) }
    }

    fun upload() {
        val state = uiState.value
        if (!state.canUpload) {
            _uiState.update { it.copy(error = "Add a title, choose an output type, and select 2 to 5 files.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, error = null, job = null) }
            val tempFiles = withContext(Dispatchers.IO) {
                state.files.map { copyToCache(it) }
            }
            remoteAudioRepository.uploadMultiSourceFiles(
                files = tempFiles,
                title = state.title.trim(),
                description = state.description.trim().takeIf { it.isNotBlank() },
                outputType = state.selectedOutputType ?: OutputTypeOption.NOTES.apiValue
            ).collect { result ->
                result.onSuccess { job ->
                    _uiState.update { it.copy(isUploading = false, job = job, error = null) }
                }.onFailure { error ->
                    _uiState.update { it.copy(isUploading = false, error = error.message ?: "Multi-source upload failed.") }
                }
            }
        }
    }

    private suspend fun inspectUri(uri: Uri): SelectedSourceFile? = withContext(Dispatchers.IO) {
        val resolver = application.contentResolver
        val mimeType = resolver.getType(uri)
        var name = uri.lastPathSegment ?: "Selected file"
        var size: Long? = null
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        SelectedSourceFile(uri, name, mimeType, size)
    }

    private fun copyToCache(source: SelectedSourceFile): File {
        val safeName = source.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(application.cacheDir, "multi_source_${System.currentTimeMillis()}_$safeName")
        application.contentResolver.openInputStream(source.uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read ${source.displayName}")
        return target
    }
}
