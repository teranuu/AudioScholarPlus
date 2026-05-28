package edu.cit.audioscholar.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.cit.audioscholar.dto.WarningIndicatorDTO;
import edu.cit.audioscholar.service.WarningIndicatorService;

@RestController
@RequestMapping("/api/summaries")
public class WarningIndicatorController {
	private final WarningIndicatorService warningIndicatorService;

	public WarningIndicatorController(WarningIndicatorService warningIndicatorService) {
		this.warningIndicatorService = warningIndicatorService;
	}

	@GetMapping("/{summaryId}/warning-indicators")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> getWarningIndicators(@PathVariable String summaryId) {
		try {
			List<WarningIndicatorDTO> warnings = warningIndicatorService.getWarningIndicators(summaryId).stream()
					.map(WarningIndicatorDTO::fromModel).toList();
			return ResponseEntity.ok(warnings);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", e.getMessage()));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("status", "FAILED", "message", "Warning indicators could not be retrieved."));
		}
	}

	@PostMapping("/{summaryId}/warning-indicators")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> generateWarningIndicators(@PathVariable String summaryId) {
		try {
			List<WarningIndicatorDTO> warnings = warningIndicatorService.generateWarningIndicators(summaryId).stream()
					.map(WarningIndicatorDTO::fromModel).toList();
			return ResponseEntity.ok(warnings);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", e.getMessage()));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("status", "FAILED", "message", "Warning indicators could not be generated."));
		}
	}
}
