package edu.cit.audioscholar.data.remote.dto

import com.google.gson.annotations.SerializedName

data class SummaryResponseDto(
    @SerializedName("summaryId")
    val summaryId: String? = null,

    @SerializedName("recordingId")
    val recordingId: String? = null,

    @SerializedName("keyPoints")
    val keyPoints: List<String>? = null,

    @SerializedName("topics")
    val topics: List<String>? = null,

    @SerializedName("glossary")
    val glossary: List<GlossaryItemDto>? = null,

    @SerializedName("formattedSummaryText")
    val formattedSummaryText: String? = null,

    @SerializedName("qualityNotice")
    val qualityNotice: String? = null,

    @SerializedName("hasQualityWarnings")
    val hasQualityWarnings: Boolean? = null,

    @SerializedName("qualityReport")
    val qualityReport: QualityReportDto? = null,

    @SerializedName("transcriptSegments")
    val transcriptSegments: List<TranscriptSegmentDto>? = null,

    @SerializedName("createdAt")
    val createdAt: String? = null
)

data class QualityReportDto(
    @SerializedName("status")
    val status: String? = null,

    @SerializedName("issues")
    val issues: List<QualityIssueDto>? = null
)

data class QualityIssueDto(
    @SerializedName("startTime")
    val startTime: String? = null,

    @SerializedName("endTime")
    val endTime: String? = null,

    @SerializedName("issueType")
    val issueType: String? = null,

    @SerializedName("severity")
    val severity: String? = null,

    @SerializedName("recommendedAction")
    val recommendedAction: String? = null
)

data class TranscriptSegmentDto(
    @SerializedName("startTime")
    val startTime: String? = null,

    @SerializedName("endTime")
    val endTime: String? = null,

    @SerializedName("text")
    val text: String? = null,

    @SerializedName("clarityLabel")
    val clarityLabel: String? = null,

    @SerializedName("qualityIssueTypes")
    val qualityIssueTypes: List<String>? = null
)
