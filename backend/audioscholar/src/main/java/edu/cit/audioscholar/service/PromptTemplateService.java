package edu.cit.audioscholar.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.OutputType;
import edu.cit.audioscholar.model.PromptTemplate;

@Service
public class PromptTemplateService {
	private static final String COLLECTION_NAME = "promptTemplates";

	private final FirebaseService firebaseService;

	public PromptTemplateService(FirebaseService firebaseService) {
		this.firebaseService = firebaseService;
	}

	public String getTemplate(String outputType) {
		OutputType selected = OutputType.fromValue(outputType);
		try {
			List<Map<String, Object>> templates = firebaseService.queryCollection(COLLECTION_NAME, "outputType",
					selected.name());
			if (templates != null) {
				return templates.stream().map(PromptTemplate::fromMap)
						.filter(template -> template != null && template.isActive()
								&& template.getTemplateContent() != null)
						.map(PromptTemplate::getTemplateContent).findFirst().orElseGet(() -> defaultTemplate(selected));
			}
		} catch (RuntimeException ignored) {
			// A missing prompt template collection should never block summary generation.
		}
		return defaultTemplate(selected);
	}

	public PromptTemplate saveTemplate(PromptTemplate template) {
		if (template == null || template.getTemplateId() == null) {
			throw new IllegalArgumentException("Prompt template and templateId are required.");
		}
		OutputType.fromValue(template.getOutputType());
		firebaseService.saveData(COLLECTION_NAME, template.getTemplateId(), template.toMap());
		return template;
	}

	private String defaultTemplate(OutputType outputType) {
		return switch (outputType) {
			case STUDY_MATERIAL ->
				"Format the generated material as Study Material: include organized lesson sections, clear explanations, important terms, examples where present in the source, and learner-friendly review structure.";
			case REVIEW_MATERIAL ->
				"Format the generated material as Review Material: populate the flashcards array with front/back study cards for quick recall. Each card front must be a term, question, or prompt, and each back must be the matching definition or answer. Keep summaryText concise as a short deck overview.";
			case NOTES ->
				"Format the generated material as Notes: create shortened, personal lecture notes in Markdown. Keep the notes less granular than Study Material: include a brief overview, 3-6 key ideas, compact topic notes, and quick review bullets. Preserve the lecture flow where useful, but avoid long lesson-guide explanations, exhaustive examples, or module-style study guide sections.";
		};
	}
}
