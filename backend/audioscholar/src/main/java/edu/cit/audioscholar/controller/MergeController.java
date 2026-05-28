package edu.cit.audioscholar.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.cit.audioscholar.dto.MergedSummaryResponseDTO;
import edu.cit.audioscholar.service.MergeService;
import edu.cit.audioscholar.service.MultiSourceJobService;

@RestController
@RequestMapping("/api/merge")
public class MergeController {
	private final MergeService mergeService;
	private final MultiSourceJobService multiSourceJobService;

	public MergeController(MergeService mergeService, MultiSourceJobService multiSourceJobService) {
		this.mergeService = mergeService;
		this.multiSourceJobService = multiSourceJobService;
	}

	@PostMapping("/{jobId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> startMerge(@PathVariable String jobId, Authentication authentication) {
		if (!ownsJob(jobId, authentication)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(mergeService.startMerge(jobId));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.notFound().build();
		}
	}

	@GetMapping("/{jobId}/summary")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> getMergedSummary(@PathVariable String jobId, Authentication authentication) {
		if (!ownsJob(jobId, authentication)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(MergedSummaryResponseDTO.fromModel(mergeService.getMergedSummary(jobId)));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.notFound().build();
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "PROCESSING"));
		}
	}

	private boolean ownsJob(String jobId, Authentication authentication) {
		Map<String, Object> job = multiSourceJobService.getJobMap(jobId);
		return job != null && authentication != null && authentication.getName().equals(job.get("userId"));
	}
}
