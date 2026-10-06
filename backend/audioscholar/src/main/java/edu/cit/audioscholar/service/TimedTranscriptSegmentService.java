package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.SourceFile;

@Service
public class TimedTranscriptSegmentService {
	private final FirebaseService firebase;
	public TimedTranscriptSegmentService(FirebaseService firebase) {
		this.firebase = firebase;
	}

	public List<Map<String, Object>> persist(SourceFile source) {
		String transcript = source.getTranscriptText() == null ? "" : source.getTranscriptText().trim();
		if (transcript.isBlank())
			return List.of();
		String[] sentences = transcript.split("(?<=[.!?])\\s+|\\R+");
		List<String> chunks = new ArrayList<>();
		for (String sentence : sentences)
			if (!sentence.isBlank())
				chunks.add(sentence.trim());
		if (chunks.isEmpty())
			chunks.add(transcript);
		long durationMs = source.getDurationSeconds() != null && source.getDurationSeconds() > 0
				? source.getDurationSeconds() * 1000
				: Math.max(1000, transcript.split("\\s+").length * 400L);
		List<Map<String, Object>> result = new ArrayList<>();
		for (int index = 0; index < chunks.size(); index++) {
			long start = durationMs * index / chunks.size();
			long end = durationMs * (index + 1) / chunks.size();
			String text = chunks.get(index);
			String id = ValidationEventService.digest(source.getSourceFileId() + ":" + index + ":" + text);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("segmentId", id);
			row.put("jobId", source.getJobId());
			row.put("sourceFileId", source.getSourceFileId());
			row.put("startMs", start);
			row.put("endMs", end);
			row.put("contentHash", ValidationEventService.digest(text));
			row.put("textArtifactRef", "transcriptSegments/" + id);
			row.put("text", text);
			row.put("algorithmVersion", "proportional-sentence-timing-v1");
			firebase.saveData("transcriptSegments", id, row);
			result.add(row);
		}
		return result;
	}
}
