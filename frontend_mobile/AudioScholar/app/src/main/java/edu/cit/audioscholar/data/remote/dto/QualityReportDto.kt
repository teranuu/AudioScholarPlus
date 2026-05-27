package edu.cit.audioscholar.data.remote.dto

data class QualityIssueDto(
    val issueId: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val issueType: String? = null,
    val severity: String? = null,
    val recommendedAction: String? = null
)

data class QualityReportDto(
    val reportId: String? = null,
    val recordingId: String? = null,
    val status: String? = null,
    val issues: List<QualityIssueDto>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val message: String? = null
)
