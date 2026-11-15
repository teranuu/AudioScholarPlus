package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.Flashcard;
import edu.cit.audioscholar.model.KeyPoint;

@Service
public class DeduplicationService {
	public List<KeyPoint> removeDuplicateKeyPoints(List<KeyPoint> keyPoints) {
		Set<String> seen = new HashSet<>();
		List<KeyPoint> deduped = new ArrayList<>();
		for (KeyPoint keyPoint : keyPoints) {
			String text = keyPoint.getText();
			// Punctuation and numbers can change a claim's meaning. Source identity is
			// also part of a point until provenance has been merged explicitly.
			String key = normalize(text) + "\0" + normalize(keyPoint.getSourceLabel());
			if (text != null && !text.isBlank() && seen.add(key)) {
				deduped.add(keyPoint);
			}
		}
		return deduped;
	}

	public List<String> removeDuplicateText(List<String> values) {
		return removeDuplicateKeyPoints(values.stream().map(value -> new KeyPoint(value, null)).toList()).stream()
				.map(KeyPoint::getText).toList();
	}

	public List<Flashcard> removeDuplicateFlashcards(List<Flashcard> flashcards) {
		Set<String> seen = new HashSet<>();
		List<Flashcard> deduped = new ArrayList<>();
		if (flashcards == null) {
			return deduped;
		}
		for (Flashcard flashcard : flashcards) {
			String front = flashcard != null ? flashcard.getFront() : null;
			String back = flashcard != null ? flashcard.getBack() : null;
			String key = normalize(front) + "\0" + normalize(back) + "\0"
					+ normalize(flashcard == null ? null : flashcard.getSourceFileId()) + "\0"
					+ normalize(flashcard == null ? null : flashcard.getSourceSegmentId()) + "\0"
					+ normalize(flashcard == null ? null : flashcard.getSourceStartTime()) + "\0"
					+ normalize(flashcard == null ? null : flashcard.getSourceEndTime());
			if (front != null && !front.isBlank() && back != null && !back.isBlank() && seen.add(key)) {
				deduped.add(flashcard);
			}
		}
		return deduped;
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().replaceAll("\\s+", " ");
	}
}
