package edu.cit.audioscholar.ui.multisource

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import edu.cit.audioscholar.data.remote.dto.MultiSourceJobDto
import edu.cit.audioscholar.data.remote.service.ApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class SelectedSource(val uri: Uri, val name: String, val bytes: Long, val isMedia: Boolean)

data class MultiSourceUiState(
    val sources: List<SelectedSource> = emptyList(),
    val outputType: String? = null,
    val title: String = "",
    val submitting: Boolean = false,
    val job: MultiSourceJobDto? = null,
    val recentJobs: List<MultiSourceJobDto> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class MultiSourceViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val mediaExtensions = setOf("mp3", "wav", "aac", "ogg", "flac", "aiff", "m4a", "mp4", "webm", "mov")
    private val documentExtensions = setOf("pdf", "ppt", "pptx", "doc", "docx")
    private val preferences = context.getSharedPreferences("multi_source_jobs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(MultiSourceUiState())
    val state = _state.asStateFlow()
    private var polling: Job? = null
    private var submissionKey: String? = null

    init {
        refreshJobs()
        preferences.getString("active_job_id", null)?.let(::openJob)
    }

    fun selectSources(uris: List<Uri>) {
        val current = _state.value.sources.toMutableList()
        var error: String? = null
        uris.forEach { uri ->
            if (current.size >= 5) { error = "Select no more than five sources."; return@forEach }
            if (current.any { it.uri == uri }) return@forEach
            val nameAndSize = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val name = cursor.getString(0) ?: "source"
                        name to if (cursor.isNull(1)) -1L else cursor.getLong(1)
                    }
                } ?: ((uri.lastPathSegment ?: "source") to -1L)
            val (name, size) = nameAndSize
            val extension = name.substringAfterLast('.', "").lowercase()
            val isMedia = extension in mediaExtensions
            if (!isMedia && extension !in documentExtensions) {
                error = "$name has an unsupported format."
            } else if (size == 0L) {
                error = "$name is empty."
            } else {
                current += SelectedSource(uri, name, size, isMedia)
            }
        }
        val ordered = current.filter { it.isMedia } + current.filterNot { it.isMedia }
        _state.value = _state.value.copy(sources = ordered, error = error)
        submissionKey = null
    }

    fun removeSource(uri: Uri) {
        _state.value = _state.value.copy(sources = _state.value.sources.filterNot { it.uri == uri }, error = null)
        submissionKey = null
    }

    fun setOutputType(value: String) {
        if (_state.value.outputType != value) submissionKey = null
        _state.value = _state.value.copy(outputType = value, error = null)
    }
    fun setTitle(value: String) {
        if (_state.value.title != value) submissionKey = null
        _state.value = _state.value.copy(title = value)
    }

    fun submit() {
        val snapshot = _state.value
        if (snapshot.sources.size !in 2..5) { fail("Select two to five sources."); return }
        val outputType = snapshot.outputType ?: run { fail("Choose one output type."); return }
        if (snapshot.sources.any { it.bytes > 100L * 1024 * 1024 } ||
            snapshot.sources.sumOf { it.bytes.coerceAtLeast(0) } > 100L * 1024 * 1024) {
            fail("The combined upload must be 100 MB or less."); return
        }
        if (_state.value.submitting) return
        val key = submissionKey ?: UUID.randomUUID().toString().also { submissionKey = it }
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null)
            val temporary = mutableListOf<File>()
            try {
                val parts = withContext(Dispatchers.IO) {
                    var totalBytes = 0L
                    snapshot.sources.map { source ->
                        val suffix = source.name.substringAfterLast('.', "bin")
                        val file = File.createTempFile("multi-source-", ".$suffix", context.cacheDir)
                        temporary += file
                        context.contentResolver.openInputStream(source.uri)?.use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    totalBytes += count
                                    require(totalBytes <= 100L * 1024 * 1024) { "The combined upload must be 100 MB or less." }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                            ?: error("Could not read ${source.name}.")
                        val mime = context.contentResolver.getType(source.uri)
                            ?: if (source.isMedia) "application/octet-stream" else if (suffix == "pdf") "application/pdf" else "application/octet-stream"
                        MultipartBody.Part.createFormData(
                            if (source.isMedia) "mediaFiles" else "documentFiles", source.name,
                            file.asRequestBody(mime.toMediaTypeOrNull())
                        )
                    }
                }
                val response = api.createMultiSourceJob(parts,
                    snapshot.title.ifBlank { "Merged Lecture Summary" }.toRequestBody(), outputType.toRequestBody(),
                    "ANDROID", key)
                val accepted = response.body()
                if (!response.isSuccessful || accepted?.jobId.isNullOrBlank()) error("Upload failed (${response.code()}). Check the files and try again.")
                submissionKey = null
                _state.value = _state.value.copy(submitting = false, job = accepted, sources = emptyList())
                preferences.edit().putString("active_job_id", accepted!!.jobId).apply()
                poll(accepted.jobId!!)
                refreshJobs()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(failure.message ?: "Upload failed. Retry with the same sources.")
                _state.value = _state.value.copy(submitting = false)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { temporary.forEach(File::delete) }
            }
        }
    }

    fun refreshJobs() = viewModelScope.launch {
        try {
            val response = api.listMultiSourceJobs()
            if (response.isSuccessful) _state.value = _state.value.copy(recentJobs = response.body().orEmpty())
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    }

    fun openJob(jobId: String) { poll(jobId) }

    private fun poll(jobId: String) {
        polling?.cancel()
        polling = viewModelScope.launch {
            repeat(360) {
                try {
                    val response = api.getMultiSourceJob(jobId)
                    if (response.code() == 403 || response.code() == 404) {
                        preferences.edit().remove("active_job_id").apply()
                        fail("This job is unavailable for this account.")
                        return@launch
                    }
                    if (!response.isSuccessful) error("Could not load job status (${response.code()}).")
                    val job = response.body() ?: error("Job status is unavailable.")
                    _state.value = _state.value.copy(job = job, error = null)
                    if (job.status == "COMPLETE" || job.status == "INCOMPLETE" || job.status == "FAILED") {
                        preferences.edit().remove("active_job_id").apply()
                        refreshJobs()
                        return@launch
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { fail(failure.message ?: "Could not load job status.") }
                delay(5000)
            }
            fail("Status updates paused. Open this job again to refresh it.")
        }
    }

    private fun fail(message: String) { _state.value = _state.value.copy(error = message) }
}
