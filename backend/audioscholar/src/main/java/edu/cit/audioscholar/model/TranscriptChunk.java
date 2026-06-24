package edu.cit.audioscholar.model;

import java.util.HashMap;
import java.util.Map;

import com.google.cloud.Timestamp;

public class TranscriptChunk {
	private int index;
	private long startMs;
	private long endMs;
	private boolean overlapsPrevious;
	private String status;
	private int attempts;
	private String text;
	private String provider;
	private String error;
	private Timestamp updatedAt;

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("index", index);
		map.put("startMs", startMs);
		map.put("endMs", endMs);
		map.put("overlapsPrevious", overlapsPrevious);
		map.put("status", status);
		map.put("attempts", attempts);
		map.put("text", text);
		map.put("provider", provider);
		map.put("error", error);
		map.put("updatedAt", updatedAt != null ? updatedAt : Timestamp.now());
		return map;
	}

	public static TranscriptChunk fromMap(Map<String, Object> map) {
		TranscriptChunk chunk = new TranscriptChunk();
		chunk.index = ((Number) map.getOrDefault("index", 0)).intValue();
		chunk.startMs = ((Number) map.getOrDefault("startMs", 0L)).longValue();
		chunk.endMs = ((Number) map.getOrDefault("endMs", 0L)).longValue();
		chunk.overlapsPrevious = Boolean.TRUE.equals(map.get("overlapsPrevious"));
		chunk.status = (String) map.get("status");
		chunk.attempts = ((Number) map.getOrDefault("attempts", 0)).intValue();
		chunk.text = (String) map.get("text");
		chunk.provider = (String) map.get("provider");
		chunk.error = (String) map.get("error");
		chunk.updatedAt = (Timestamp) map.get("updatedAt");
		return chunk;
	}

	public int getIndex() {
		return index;
	}
	public void setIndex(int index) {
		this.index = index;
	}
	public long getStartMs() {
		return startMs;
	}
	public void setStartMs(long startMs) {
		this.startMs = startMs;
	}
	public long getEndMs() {
		return endMs;
	}
	public void setEndMs(long endMs) {
		this.endMs = endMs;
	}
	public boolean isOverlapsPrevious() {
		return overlapsPrevious;
	}
	public void setOverlapsPrevious(boolean overlapsPrevious) {
		this.overlapsPrevious = overlapsPrevious;
	}
	public String getStatus() {
		return status;
	}
	public void setStatus(String status) {
		this.status = status;
	}
	public int getAttempts() {
		return attempts;
	}
	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}
	public String getText() {
		return text;
	}
	public void setText(String text) {
		this.text = text;
	}
	public String getProvider() {
		return provider;
	}
	public void setProvider(String provider) {
		this.provider = provider;
	}
	public String getError() {
		return error;
	}
	public void setError(String error) {
		this.error = error;
	}
	public Timestamp getUpdatedAt() {
		return updatedAt;
	}
	public void setUpdatedAt(Timestamp updatedAt) {
		this.updatedAt = updatedAt;
	}
}
