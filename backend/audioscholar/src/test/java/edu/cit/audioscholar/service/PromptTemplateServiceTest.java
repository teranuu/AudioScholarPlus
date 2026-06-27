package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class PromptTemplateServiceTest {

	@Test
	void getTemplateFallsBackToShortenedNotesTemplate() {
		FirebaseService firebaseService = mock(FirebaseService.class);
		when(firebaseService.queryCollection(anyString(), eq("outputType"), eq("NOTES"))).thenReturn(List.of());

		PromptTemplateService service = new PromptTemplateService(firebaseService);

		String template = service.getTemplate("NOTES");

		assertTrue(template.contains("shortened, personal lecture notes"));
		assertTrue(template.contains("less granular than Study Material"));
		assertTrue(template.contains("quick review bullets"));
	}

	@Test
	void getTemplateFallsBackToFlashcardReviewMaterialTemplate() {
		FirebaseService firebaseService = mock(FirebaseService.class);
		when(firebaseService.queryCollection(anyString(), eq("outputType"), eq("REVIEW_MATERIAL")))
				.thenReturn(List.of());

		PromptTemplateService service = new PromptTemplateService(firebaseService);

		String template = service.getTemplate("REVIEW_MATERIAL");

		assertTrue(template.contains("flashcards array"));
		assertTrue(template.contains("front/back study cards"));
	}
}
