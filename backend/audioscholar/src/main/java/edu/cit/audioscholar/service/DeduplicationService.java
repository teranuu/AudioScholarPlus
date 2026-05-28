package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.KeyPoint;

@Service
public class DeduplicationService {
	public List<KeyPoint> removeDuplicateKeyPoints(List<KeyPoint> keyPoints) {
		Set<String> seen = new HashSet<>();
		List<KeyPoint> deduped = new ArrayList<>();
		for (KeyPoint keyPoint : keyPoints) {
			String text = keyPoint.getText();
			String key = text == null ? "" : text.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
			if (!key.isBlank() && seen.add(key)) {
				deduped.add(keyPoint);
			}
		}
		return deduped;
	}

	public List<String> removeDuplicateText(List<String> values) {
		return removeDuplicateKeyPoints(values.stream().map(value -> new KeyPoint(value, null)).toList()).stream()
				.map(KeyPoint::getText).toList();
	}
}
