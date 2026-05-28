package edu.cit.audioscholar.model;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.cloud.Timestamp;

public class SummaryKeyPoint {
	private String keyPointId = UUID.randomUUID().toString();
	private String summaryId;
	private String text;
	private String sourceStartTime;
	private String sourceEndTime;
	private Date createdAt = new Date();

	public String getKeyPointId() {
		return keyPointId;
	}

	public void setKeyPointId(String keyPointId) {
		this.keyPointId = keyPointId;
	}

	public String getSummaryId() {
		return summaryId;
	}

	public void setSummaryId(String summaryId) {
		this.summaryId = summaryId;
	}

	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text;
	}

	public String getSourceStartTime() {
		return sourceStartTime;
	}

	public void setSourceStartTime(String sourceStartTime) {
		this.sourceStartTime = sourceStartTime;
	}

	public String getSourceEndTime() {
		return sourceEndTime;
	}

	public void setSourceEndTime(String sourceEndTime) {
		this.sourceEndTime = sourceEndTime;
	}

	public Date getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("keyPointId", keyPointId);
		map.put("summaryId", summaryId);
		map.put("text", text);
		map.put("sourceStartTime", sourceStartTime);
		map.put("sourceEndTime", sourceEndTime);
		map.put("createdAt", createdAt);
		return map;
	}

	public static SummaryKeyPoint fromMap(Map<String, Object> map) {
		if (map == null) {
			return null;
		}
		SummaryKeyPoint keyPoint = new SummaryKeyPoint();
		keyPoint.keyPointId = (String) map.get("keyPointId");
		keyPoint.summaryId = (String) map.get("summaryId");
		keyPoint.text = (String) map.get("text");
		keyPoint.sourceStartTime = (String) map.get("sourceStartTime");
		keyPoint.sourceEndTime = (String) map.get("sourceEndTime");
		Object created = map.get("createdAt");
		if (created instanceof Timestamp timestamp) {
			keyPoint.createdAt = timestamp.toDate();
		} else if (created instanceof Date date) {
			keyPoint.createdAt = date;
		}
		return keyPoint;
	}
}
