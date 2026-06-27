package edu.cit.audioscholar.model;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.cloud.Timestamp;

public class Flashcard {
	private String cardId = UUID.randomUUID().toString();
	private String summaryId;
	private String front;
	private String back;
	private String sourceStartTime;
	private String sourceEndTime;
	private Date createdAt = new Date();

	public Flashcard() {
	}

	public Flashcard(String front, String back) {
		this.front = front;
		this.back = back;
	}

	public String getCardId() {
		return cardId;
	}

	public void setCardId(String cardId) {
		this.cardId = cardId;
	}

	public String getSummaryId() {
		return summaryId;
	}

	public void setSummaryId(String summaryId) {
		this.summaryId = summaryId;
	}

	public String getFront() {
		return front;
	}

	public void setFront(String front) {
		this.front = front;
	}

	public String getBack() {
		return back;
	}

	public void setBack(String back) {
		this.back = back;
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
		map.put("cardId", cardId);
		map.put("summaryId", summaryId);
		map.put("front", front);
		map.put("back", back);
		map.put("sourceStartTime", sourceStartTime);
		map.put("sourceEndTime", sourceEndTime);
		map.put("createdAt", createdAt);
		return map;
	}

	public static Flashcard fromMap(Map<String, Object> map) {
		if (map == null) {
			return null;
		}
		Flashcard flashcard = new Flashcard();
		flashcard.cardId = (String) map.get("cardId");
		flashcard.summaryId = (String) map.get("summaryId");
		flashcard.front = (String) map.get("front");
		flashcard.back = (String) map.get("back");
		flashcard.sourceStartTime = (String) map.get("sourceStartTime");
		flashcard.sourceEndTime = (String) map.get("sourceEndTime");
		Object created = map.get("createdAt");
		if (created instanceof Timestamp timestamp) {
			flashcard.createdAt = timestamp.toDate();
		} else if (created instanceof Date date) {
			flashcard.createdAt = date;
		}
		return flashcard;
	}
}
