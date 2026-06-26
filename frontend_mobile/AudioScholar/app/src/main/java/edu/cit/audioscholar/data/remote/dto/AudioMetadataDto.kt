package edu.cit.audioscholar.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TimestampDto(
    val seconds: Long? = null,
    val nanos: Long? = null
)

data class AudioMetadataDto(
    val id: String? = null,
    val userId: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val contentType: String? = null,
    val title: String? = null,
    val description: String? = null,
    val nhostFileId: String? = null,
    val storageUrl: String? = null,
    val audioUrl: String? = null,
    val generatedPdfUrl: String? = null,
    val uploadTimestamp: TimestampDto? = null,
    val status: String? = null,
    val recordingId: String? = null,
    val summaryId: String? = null,
    val gptSummary: String? = null,
    val transcriptText: String? = null,
    val transcriptSegments: List<TranscriptSegmentDto>? = null,
    val qualityReport: QualityReportDto? = null,
    val durationSeconds: Int? = null,
    @SerializedName("favorite")
    val isFavorite: Boolean? = null
)
