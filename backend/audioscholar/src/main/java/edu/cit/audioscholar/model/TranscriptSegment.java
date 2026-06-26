package edu.cit.audioscholar.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TranscriptSegment {
	private String startTime;
	private String endTime;
	private String text;
	private String clarityLabel;
	private List<String> qualityIssueTypes = new ArrayList<>();

	public String getStartTime() {
		return startTime;
	}

	public void setStartTime(String startTime) {
		this.startTime = startTime;
	}

	public String getEndTime() {
		return endTime;
	}

	public void setEndTime(String endTime) {
		this.endTime = endTime;
	}

	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text;
	}

	public String getClarityLabel() {
		return clarityLabel;
	}

	public void setClarityLabel(String clarityLabel) {
		this.clarityLabel = clarityLabel;
	}

	public List<String> getQualityIssueTypes() {
		return qualityIssueTypes;
	}

	public void setQualityIssueTypes(List<String> qualityIssueTypes) {
		this.qualityIssueTypes = qualityIssueTypes != null ? new ArrayList<>(qualityIssueTypes) : new ArrayList<>();
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("startTime", startTime);
		map.put("endTime", endTime);
		map.put("text", text);
		map.put("clarityLabel", clarityLabel);
		map.put("qualityIssueTypes", qualityIssueTypes);
		return map;
	}

	public static TranscriptSegment fromMap(Map<String, Object> map) {
		if (map == null) {
			return null;
		}
		TranscriptSegment segment = new TranscriptSegment();
		segment.startTime = (String) map.get("startTime");
		segment.endTime = (String) map.get("endTime");
		segment.text = (String) map.get("text");
		segment.clarityLabel = (String) map.get("clarityLabel");
		Object issueTypes = map.get("qualityIssueTypes");
		if (issueTypes instanceof List<?> rawTypes) {
			List<String> parsed = new ArrayList<>();
			for (Object rawType : rawTypes) {
				if (rawType != null) {
					parsed.add(rawType.toString());
				}
			}
			segment.qualityIssueTypes = parsed;
		}
		return segment;
	}
}
