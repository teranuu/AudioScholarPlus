package edu.cit.audioscholar.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import edu.cit.audioscholar.model.KeyPoint;
import edu.cit.audioscholar.model.SourceAttribution;

@Service
public class SourceAttributionService {
	public SourceAttribution assignAttribution(KeyPoint keyPoint) {
		SourceAttribution attribution = new SourceAttribution();
		attribution.setKeyPointId(keyPoint.getKeyPointId());
		attribution
				.setSourceLabel(StringUtils.hasText(keyPoint.getSourceLabel()) ? keyPoint.getSourceLabel() : "Merged");
		attribution.setAttributionType(StringUtils.hasText(keyPoint.getSourceLabel()) ? "UNIQUE_SOURCE" : "MERGED");
		return attribution;
	}
}
