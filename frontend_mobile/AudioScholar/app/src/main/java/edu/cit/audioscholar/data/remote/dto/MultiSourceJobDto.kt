package edu.cit.audioscholar.data.remote.dto

data class MultiSourceJobDto(
    val jobId: String? = null,
    val title: String? = null,
    val outputType: String? = null,
    val status: String? = null,
    val failureReason: String? = null,
    val failureStage: String? = null,
    val quotaRetryAt: String? = null,
    val semanticEnrichmentStatus: String? = null,
    val sourceFiles: List<MultiSourceFileDto>? = null,
    val sourceAttributions: List<MultiSourceAttributionDto>? = null,
    val mergedSummary: MultiSourceSummaryDto? = null
)

data class MultiSourceFileDto(
    val sourceFileId: String? = null,
    val sourceLabel: String? = null,
    val fileName: String? = null,
    val sourceKind: String? = null,
    val uploadStatus: String? = null
)

data class MultiSourceAttributionDto(
    val keyPointId: String? = null,
    val sourceFileId: String? = null,
    val attributionStatus: String? = null
)

data class MultiSourceSummaryDto(
    val formattedSummaryText: String? = null,
    val summaryKeyPoints: List<MultiSourceKeyPointDto>? = null,
    val flashcards: List<MultiSourceFlashcardDto>? = null
)

data class MultiSourceKeyPointDto(
    val keyPointId: String? = null,
    val text: String? = null,
    val sourceFileId: String? = null,
    val sourceStartTime: String? = null,
    val sourceEndTime: String? = null
)

data class MultiSourceFlashcardDto(
    val cardId: String? = null,
    val front: String? = null,
    val back: String? = null,
    val sourceFileId: String? = null,
    val sourceStartTime: String? = null,
    val sourceEndTime: String? = null
)
