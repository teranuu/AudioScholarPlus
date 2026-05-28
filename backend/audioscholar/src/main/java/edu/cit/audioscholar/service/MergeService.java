package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.dto.MergeJobResponseDTO;
import edu.cit.audioscholar.model.MergedSummary;
import edu.cit.audioscholar.model.SourceAttribution;

@Service
public class MergeService {
	private final MultiSourceJobService multiSourceJobService;
	private final MergedSummaryRepository mergedSummaryRepository;

	public MergeService(MultiSourceJobService multiSourceJobService, MergedSummaryRepository mergedSummaryRepository) {
		this.multiSourceJobService = multiSourceJobService;
		this.mergedSummaryRepository = mergedSummaryRepository;
	}

	public MergeJobResponseDTO startMerge(String jobId) {
		Map<String, Object> job = multiSourceJobService.getJobMap(jobId);
		if (job == null) {
			throw new IllegalArgumentException("Multi-source job not found.");
		}
		return new MergeJobResponseDTO(jobId, String.valueOf(job.getOrDefault("status", "PROCESSING")),
				"Merge job is available through the multi-source processing workflow.");
	}

	public MergedSummary getMergedSummary(String jobId) {
		Map<String, Object> saved = mergedSummaryRepository.getByJobId(jobId);
		if (saved != null) {
			return fromMap(saved);
		}
		Map<String, Object> job = multiSourceJobService.getJobMap(jobId);
		if (job == null) {
			throw new IllegalArgumentException("Multi-source job not found.");
		}
		Object rawSummary = job.get("mergedSummary");
		if (!(rawSummary instanceof Map<?, ?> summaryMap)) {
			throw new IllegalStateException("Merged summary is not ready.");
		}
		MergedSummary mergedSummary = new MergedSummary();
		mergedSummary.setJobId(jobId);
		mergedSummary.setUserId((String) job.get("userId"));
		mergedSummary.setStatus(String.valueOf(job.getOrDefault("status", "COMPLETE")));
		Object content = summaryMap.get("formattedSummaryText");
		mergedSummary.setContent(content != null ? content.toString() : summaryMap.toString());
		mergedSummaryRepository.save(mergedSummary);
		return mergedSummary;
	}

	@SuppressWarnings("unchecked")
	private MergedSummary fromMap(Map<String, Object> map) {
		MergedSummary mergedSummary = new MergedSummary();
		mergedSummary.setMergedSummaryId((String) map.get("mergedSummaryId"));
		mergedSummary.setJobId((String) map.get("jobId"));
		mergedSummary.setUserId((String) map.get("userId"));
		mergedSummary.setContent((String) map.get("content"));
		mergedSummary.setStatus((String) map.get("status"));
		Object attributionsObj = map.get("sourceAttributions");
		if (attributionsObj instanceof List<?> rawAttributions) {
			List<SourceAttribution> attributions = new ArrayList<>();
			for (Object raw : rawAttributions) {
				if (raw instanceof Map<?, ?> rawMap) {
					SourceAttribution attribution = new SourceAttribution();
					attribution.setAttributionId((String) ((Map<String, Object>) rawMap).get("attributionId"));
					attribution.setMergedSummaryId((String) ((Map<String, Object>) rawMap).get("mergedSummaryId"));
					attribution.setKeyPointId((String) ((Map<String, Object>) rawMap).get("keyPointId"));
					attribution.setSourceLabel((String) ((Map<String, Object>) rawMap).get("sourceLabel"));
					attribution.setAttributionType((String) ((Map<String, Object>) rawMap).get("attributionType"));
					attributions.add(attribution);
				}
			}
			mergedSummary.setSourceAttributions(attributions);
		}
		return mergedSummary;
	}
}
