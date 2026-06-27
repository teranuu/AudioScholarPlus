package edu.cit.audioscholar.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import edu.cit.audioscholar.dto.SummaryDto;

class SummaryFlashcardTest {

	@Test
	void summaryMapRoundTripPreservesFlashcards() {
		Summary summary = new Summary();
		summary.setSummaryId("summary-1");
		summary.setRecordingId("recording-1");
		summary.setFlashcards(List.of(new Flashcard("What is encapsulation?", "Bundling data with behavior.")));

		Summary parsed = Summary.fromMap(summary.toMap());

		assertEquals(1, parsed.getFlashcards().size());
		assertEquals("What is encapsulation?", parsed.getFlashcards().get(0).getFront());
		assertEquals("Bundling data with behavior.", parsed.getFlashcards().get(0).getBack());
	}

	@Test
	void summaryFromLegacyMapUsesEmptyFlashcards() {
		Summary parsed = Summary.fromMap(Map.of("summaryId", "summary-1", "recordingId", "recording-1"));

		assertNotNull(parsed.getFlashcards());
		assertTrue(parsed.getFlashcards().isEmpty());
	}

	@Test
	void summaryDtoExposesFlashcards() {
		Summary summary = new Summary();
		summary.setSummaryId("summary-1");
		summary.setFlashcards(List.of(new Flashcard("Front", "Back")));

		SummaryDto dto = SummaryDto.fromModel(summary);

		assertEquals(1, dto.getFlashcards().size());
		assertEquals("Front", dto.getFlashcards().get(0).getFront());
	}
}
