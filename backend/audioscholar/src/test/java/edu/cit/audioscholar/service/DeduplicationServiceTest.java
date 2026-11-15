package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import edu.cit.audioscholar.model.Flashcard;
import edu.cit.audioscholar.model.KeyPoint;

class DeduplicationServiceTest {
	private final DeduplicationService service = new DeduplicationService();

	@Test
	void retainsDifferentAnswersAndSourceEvidence() {
		Flashcard first = new Flashcard("What is X?", "Answer one");
		Flashcard differentAnswer = new Flashcard("What is X?", "Answer two");
		Flashcard differentSource = new Flashcard("What is X?", "Answer one");
		differentSource.setSourceFileId("source-b");
		Flashcard duplicate = new Flashcard(" What is X? ", "Answer  one");

		assertEquals(List.of(first, differentAnswer, differentSource),
				service.removeDuplicateFlashcards(List.of(first, differentAnswer, differentSource, duplicate)));
	}

	@Test
	void preservesCaseSensitiveUnitsAndDifferentIntervals() {
		assertEquals(2, service.removeDuplicateText(List.of("Use 1 mM.", "Use 1 mm.")).size());
		Flashcard first = new Flashcard("Question", "Answer");
		Flashcard later = new Flashcard("Question", "Answer");
		first.setSourceStartTime("00:10");
		later.setSourceStartTime("00:20");
		assertEquals(2, service.removeDuplicateFlashcards(List.of(first, later)).size());
	}

	@Test
	void retainsPunctuationAndSourceSpecificClaims() {
		KeyPoint decimal = new KeyPoint("The rate is 1.5%.", "Source A");
		KeyPoint whole = new KeyPoint("The rate is 15%.", "Source A");
		KeyPoint otherSource = new KeyPoint("The rate is 1.5%.", "Source B");
		KeyPoint duplicate = new KeyPoint(" The rate is 1.5%. ", "Source A");

		assertEquals(List.of(decimal, whole, otherSource),
				service.removeDuplicateKeyPoints(List.of(decimal, whole, otherSource, duplicate)));
	}
}
