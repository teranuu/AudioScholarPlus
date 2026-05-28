package edu.cit.audioscholar.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import edu.cit.audioscholar.model.KeyPoint;
import edu.cit.audioscholar.model.SourceAttribution;

@Service
public class SourceAttributionService {
	private static final String COLLECTION_NAME = "sourceAttributions";

	private final FirebaseService firebaseService;

	public SourceAttributionService(FirebaseService firebaseService) {
		this.firebaseService = firebaseService;
	}

	public SourceAttribution assignAttribution(KeyPoint keyPoint) {
		SourceAttribution attribution = new SourceAttribution();
		attribution.setKeyPointId(keyPoint.getKeyPointId());
		attribution.setSourceLabel(
				StringUtils.hasText(keyPoint.getSourceLabel()) ? keyPoint.getSourceLabel() : "Multiple Sources");
		attribution.setAttributionType(
				StringUtils.hasText(keyPoint.getSourceLabel()) ? "SINGLE_SOURCE" : "MULTI_SOURCE_CONSENSUS");
		return attribution;
	}

	public SourceAttribution save(String mergedSummaryId, SourceAttribution attribution) {
		attribution.setMergedSummaryId(mergedSummaryId);
		firebaseService.saveData(COLLECTION_NAME, attribution.getAttributionId(), attribution.toMap());
		return attribution;
	}
}
