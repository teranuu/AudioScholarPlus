package edu.cit.audioscholar.service;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;

import edu.cit.audioscholar.config.RabbitMQConfig;
import edu.cit.audioscholar.dto.AudioProcessingMessage;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;

@Service
public class PptxConversionListenerService {

	private static final Logger logger = LoggerFactory.getLogger(PptxConversionListenerService.class);

	private final FirebaseService firebaseService;
	private final NhostStorageService nhostStorageService;
	private final PptxConversionProvider pptxConversionProvider;
	private final ConfirmedRabbitPublisher confirmedRabbitPublisher;
	private final ProcessingStageClaimService stageClaimService;
	@SuppressWarnings("unused")
	private final ObjectMapper objectMapper;
	private final Map<String, Lock> metadataLocks = new ConcurrentHashMap<>();

	public PptxConversionListenerService(FirebaseService firebaseService, NhostStorageService nhostStorageService,
			PptxConversionProvider pptxConversionProvider, ConfirmedRabbitPublisher confirmedRabbitPublisher,
			ObjectMapper objectMapper, ProcessingStageClaimService stageClaimService) {
		this.firebaseService = firebaseService;
		this.nhostStorageService = nhostStorageService;
		this.pptxConversionProvider = pptxConversionProvider;
		this.confirmedRabbitPublisher = confirmedRabbitPublisher;
		this.objectMapper = objectMapper;
		this.stageClaimService = stageClaimService;
	}

	@RabbitListener(queues = RabbitMQConfig.PPTX_CONVERSION_QUEUE_NAME, containerFactory = "pptxContainerFactory")
	public void handlePptxConversion(AudioProcessingMessage messageDto) {
		String metadataId = messageDto.getMetadataId();
		logger.info("Processing PPTX conversion for metadata ID: {}", metadataId);

		Lock lock = metadataLocks.computeIfAbsent(metadataId, k -> new ReentrantLock());
		lock.lock();

		try {
			Map<String, Object> metadataMap = firebaseService.getData(firebaseService.getAudioMetadataCollectionName(),
					metadataId);

			if (metadataMap == null) {
				logger.error("Cannot find metadata for ID: {}. Abandoning PPTX conversion.", metadataId);
				return;
			}

			AudioMetadata metadata = AudioMetadata.fromMap(metadataMap);
			ProcessingStatus currentStatus = metadata.getStatus();

			boolean pdfAlreadyConverted = metadata.isPdfConversionComplete()
					|| (metadata.getGeneratedPdfUrl() != null && !metadata.getGeneratedPdfUrl().isBlank())
					|| (metadata.getConvertApiPdfUrl() != null && !metadata.getConvertApiPdfUrl().isBlank());
			if (currentStatus == ProcessingStatus.SUMMARY_COMPLETE || currentStatus == ProcessingStatus.SUMMARIZING
					|| (currentStatus == ProcessingStatus.SUMMARIZATION_QUEUED && pdfAlreadyConverted)) {
				logger.info("Skipping PDF conversion as summarization is already in progress or complete (status: {})",
						currentStatus);
				return;
			}

			ProcessingStageClaimService.ClaimResult claim = stageClaimService.claim(metadataId,
					ProcessingStatus.PDF_CONVERTING, "PDF_CONVERTING",
					EnumSet.of(ProcessingStatus.UPLOAD_IN_PROGRESS, ProcessingStatus.PROCESSING_QUEUED),
					EnumSet.of(ProcessingStatus.SUMMARIZATION_QUEUED, ProcessingStatus.SUMMARIZING,
							ProcessingStatus.SUMMARY_COMPLETE, ProcessingStatus.RECOMMENDATIONS_QUEUED,
							ProcessingStatus.GENERATING_RECOMMENDATIONS, ProcessingStatus.COMPLETE,
							ProcessingStatus.COMPLETED_WITH_WARNINGS, ProcessingStatus.FAILED));
			if (!claim.claimed()) {
				logger.info("Skipping PPTX conversion for {} because claim was not acquired: {}", metadataId,
						claim.reason());
				return;
			}

			metadataMap = firebaseService.getData(firebaseService.getAudioMetadataCollectionName(), metadataId);
			metadata = AudioMetadata.fromMap(metadataMap);

			String nhostPptxFileId = metadata.getNhostPptxFileId();
			if (nhostPptxFileId == null || nhostPptxFileId.isBlank()) {
				logger.error("No PPTX file ID found in metadata. Cannot proceed with conversion.");
				updateStatus(metadataId, ProcessingStatus.FAILED, "No PPTX file ID available");
				return;
			}

			String pptxUrl = nhostStorageService.getPublicUrl(nhostPptxFileId);
			logger.info("Retrieved public URL for PPTX: {}", pptxUrl);

			Map<String, Object> pptxUrlUpdate = new HashMap<>();
			pptxUrlUpdate.put("pptxNhostUrl", pptxUrl);
			pptxUrlUpdate.put("lastUpdated", Timestamp.now());
			firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(), metadataId,
					pptxUrlUpdate);
			logger.info("Updated AudioMetadata with PPTX URL for ID: {}", metadataId);

			logger.info("Starting PPTX to PDF conversion using configured provider for file: {}", pptxUrl);
			PptxConversionResult conversionResult = pptxConversionProvider.convert(metadata);
			logger.info("PPTX to PDF conversion successful using {}. PDF URL: {}", conversionResult.providerName(),
					conversionResult.pdfUrl());

			Map<String, Object> updates = new HashMap<>();
			updates.put("generatedPdfNhostFileId", conversionResult.pdfFileId());
			updates.put("generatedPdfUrl", conversionResult.pdfUrl());
			updates.put("pdfConversionComplete", true);
			updates.put("status", ProcessingStatus.PDF_CONVERSION_COMPLETE.name());
			updates.put("lastUpdated", Timestamp.now());

			firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(), metadataId, updates);
			logger.info("AudioMetadata updated with PDF details and status PDF_CONVERSION_COMPLETE for ID: {}",
					metadataId);

			metadataMap = firebaseService.getData(firebaseService.getAudioMetadataCollectionName(), metadataId);
			metadata = AudioMetadata.fromMap(metadataMap);

			logger.info(
					"PDF conversion complete for ID: {}. Waiting for transcription (Current status: {}, Transcription complete flag: {}).",
					metadataId, metadata.getStatus(), metadata.isTranscriptionComplete());

			boolean transcriptionDone = metadata.isTranscriptionComplete();
			boolean pdfDone = metadata.isPdfConversionComplete();
			boolean isAudioOnly = metadata.isAudioOnly();

			logger.debug("Completion status check for {}: TranscriptionDone={}, PdfConversionDone={}, AudioOnly={}",
					metadataId, transcriptionDone, pdfDone, isAudioOnly);

			boolean readyForSummarization = transcriptionDone && (pdfDone || isAudioOnly);

			if (readyForSummarization && metadata.getStatus() != ProcessingStatus.SUMMARIZATION_QUEUED
					&& metadata.getStatus() != ProcessingStatus.SUMMARIZING
					&& metadata.getStatus() != ProcessingStatus.SUMMARY_COMPLETE) {

				updates = new HashMap<>();
				updates.put("status", ProcessingStatus.SUMMARIZATION_QUEUED.name());
				updates.put("lastUpdated", Timestamp.now());
				firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(), metadataId,
						updates);
				logger.info("Updated status to SUMMARIZATION_QUEUED for metadata ID: {}", metadataId);

				Map<String, String> message = new HashMap<>();
				message.put("metadataId", metadataId);
				message.put("messageId", UUID.randomUUID().toString());

				confirmedRabbitPublisher.publishToProcessingExchange(RabbitMQConfig.SUMMARIZATION_ROUTING_KEY, message);
				logger.info("Sent message to summarization queue for metadata ID: {}", metadataId);
			} else {
				logger.info(
						"Conditions not yet met for summarization or already in progress (Transcription: {}, PDF: {}, AudioOnly: {}, Status: {}). Waiting for other processes.",
						transcriptionDone, pdfDone, isAudioOnly, metadata.getStatus());

				if (pdfDone && !transcriptionDone && !isAudioOnly
						&& metadata.getStatus() != ProcessingStatus.TRANSCRIBING) {
					logger.info(
							"PDF conversion is complete but transcription is not yet started or may be stalled. Attempting to trigger/retry transcription process for ID: {}",
							metadataId);
					AudioProcessingMessage transcriptionMessage = new AudioProcessingMessage();
					transcriptionMessage.setMetadataId(metadataId);
					transcriptionMessage.setUserId(metadata.getUserId());

					confirmedRabbitPublisher.publishToProcessingExchange(RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY,
							transcriptionMessage);
					logger.info("Sent retry message to transcription queue for metadata ID: {}", metadataId);
				}
			}
		} catch (Exception e) {
			logger.error("Error during PPTX to PDF conversion: {}", e.getMessage(), e);
			updateStatus(metadataId, ProcessingStatus.FAILED, "Error converting PPTX to PDF: " + e.getMessage());
		} finally {
			lock.unlock();
			// Optional: Remove lock from map if needed, but keeping it simple for now to
			// avoid concurrency issues with removal
		}
	}

	private void updateStatus(String metadataId, ProcessingStatus status, String failureReason) {
		try {
			Map<String, Object> updates = new HashMap<>();
			updates.put("status", status.name());
			updates.put("lastUpdated", Timestamp.now());

			if (failureReason != null) {
				updates.put("failureReason", failureReason);
			} else if (status != ProcessingStatus.FAILED) {
				updates.put("failureReason", null);
			}

			firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(), metadataId, updates);
			logger.info("Updated status to {} for metadata ID: {}", status, metadataId);
		} catch (Exception e) {
			logger.error("Failed to update status for metadata ID: {}", metadataId, e);
		}
	}
}
