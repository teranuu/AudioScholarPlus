package edu.cit.audioscholar.service;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class WarningTemplateRegistry {
	public static final String TEMPLATE_VERSION = "1";
	public static final String CONFORMANCE_VERSION = "warning-conformance-v1";
	private static final Map<String, String> TEMPLATES = Map.of("AUDIO_CLIPPING",
			"Audio distortion in this section may make the generated content uncertain. Cross-check the recording.",
			"INAUDIBLE_SPEECH",
			"Speech may be quiet or absent in this section, so the generated content may be uncertain. Cross-check the recording.",
			"UNCLEAR_AUDIO",
			"Recording quality in this section may make the generated content uncertain. Cross-check the recording.",
			"HIGH_BACKGROUND_NOISE",
			"Background noise in this section may make the generated content uncertain. Cross-check the recording.",
			"MISSING_AUDIO",
			"Audio was unavailable in this section, so the generated content may be incomplete. Cross-check the source.");
	private static final Set<String> PROHIBITED = Set.of("definitely", "certainly", "is false", "is incorrect",
			"proves", "guaranteed");

	public TemplateResult render(String issueType) {
		String text = TEMPLATES.get(issueType);
		if (text == null)
			return new TemplateResult("quality-" + issueType, TEMPLATE_VERSION, null, false);
		String templateId = "quality-" + issueType.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
		TemplateResult result = new TemplateResult(templateId, TEMPLATE_VERSION, text, false);
		return new TemplateResult(templateId, TEMPLATE_VERSION, text, conforms(result, text));
	}

	public boolean conforms(TemplateResult template, String renderedText) {
		if (template == null || renderedText == null || !TEMPLATE_VERSION.equals(template.templateVersion()))
			return false;
		String registered = TEMPLATES.entrySet().stream()
				.filter(entry -> ("quality-" + entry.getKey().toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
						.equals(template.templateId()))
				.map(Map.Entry::getValue).findFirst().orElse(null);
		if (registered == null
				|| !ValidationEventService.digest(registered).equals(ValidationEventService.digest(renderedText)))
			return false;
		String lower = renderedText.toLowerCase(java.util.Locale.ROOT);
		return PROHIBITED.stream().noneMatch(lower::contains)
				&& (lower.contains("may") || lower.contains("unavailable"));
	}

	public record TemplateResult(String templateId, String templateVersion, String text, boolean conforms) {
	}
}
