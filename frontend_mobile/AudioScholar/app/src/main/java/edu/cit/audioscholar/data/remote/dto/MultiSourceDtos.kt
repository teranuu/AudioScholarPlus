package edu.cit.audioscholar.data.remote.dto

data class SourceFileDto(
    val sourceFileId: String? = null,
    val sourceLabel: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
    val fileSize: Long? = null,
    val transcriptText: String? = null,
    val qualityReport: QualityReportDto? = null
)

data class MultiSourceJobDto(
    val jobId: String? = null,
    val userId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val outputType: String? = null,
    val status: String? = null,
    val failureReason: String? = null,
    val sourceFiles: List<SourceFileDto>? = null,
    val mergedSummary: SummaryResponseDto? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val message: String? = null
)

data class MultiSourceSummaryStatusDto(
    val status: String? = null,
    val message: String? = null
)
