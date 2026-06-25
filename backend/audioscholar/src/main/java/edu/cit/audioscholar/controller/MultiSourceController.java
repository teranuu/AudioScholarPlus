package edu.cit.audioscholar.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.service.MultiSourceJobService;

@RestController
@RequestMapping("/api/audio/multi-source")
public class MultiSourceController {
	private final MultiSourceJobService multiSourceJobService;

	public MultiSourceController(MultiSourceJobService multiSourceJobService) {
		this.multiSourceJobService = multiSourceJobService;
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> createJob(@RequestParam("mediaFiles") List<MultipartFile> mediaFiles,
			@RequestParam(value = "documentFiles", required = false) List<MultipartFile> documentFiles,
			@RequestParam(value = "title", required = false) String title,
			@RequestParam(value = "description", required = false) String description,
			@RequestParam(value = "outputType", required = false) String outputType, Authentication authentication) {
		try {
			MultiSourceJob job = multiSourceJobService.createAndProcess(authentication.getName(), mediaFiles,
					documentFiles, title, description, outputType);
			return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.toMap());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", e.getMessage()));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("status", "FAILED", "message", "Multi-source processing failed: " + e.getMessage()));
		}
	}

	@GetMapping("/{jobId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> getJob(@PathVariable String jobId, Authentication authentication) {
		Map<String, Object> job = multiSourceJobService.getJobMap(jobId);
		if (job == null) {
			return ResponseEntity.notFound().build();
		}
		if (!authentication.getName().equals(job.get("userId"))) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		return ResponseEntity.ok(job);
	}

	@GetMapping("/{jobId}/summary")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> getMergedSummary(@PathVariable String jobId, Authentication authentication) {
		Map<String, Object> job = multiSourceJobService.getJobMap(jobId);
		if (job == null) {
			return ResponseEntity.notFound().build();
		}
		if (!authentication.getName().equals(job.get("userId"))) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		Object summary = job.get("mergedSummary");
		if (summary == null) {
			return ResponseEntity.status(HttpStatus.ACCEPTED)
					.body(Map.of("status", job.getOrDefault("status", "PROCESSING")));
		}
		return ResponseEntity.ok(summary);
	}
}
