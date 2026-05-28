package edu.cit.audioscholar.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.cit.audioscholar.dto.OutputTypeRequestDTO;
import edu.cit.audioscholar.dto.OutputTypeResponseDTO;
import edu.cit.audioscholar.model.Recording;
import edu.cit.audioscholar.service.OutputTypeService;
import edu.cit.audioscholar.service.RecordingService;

@RestController
@RequestMapping("/api/output-type")
public class OutputTypeController {
	private final OutputTypeService outputTypeService;
	private final RecordingService recordingService;

	public OutputTypeController(OutputTypeService outputTypeService, RecordingService recordingService) {
		this.outputTypeService = outputTypeService;
		this.recordingService = recordingService;
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> saveOutputType(@RequestBody OutputTypeRequestDTO request, Authentication authentication) {
		try {
			Recording recording = recordingService.getRecordingById(request.getRecordingId());
			if (recording == null) {
				return ResponseEntity.notFound().build();
			}
			if (!authentication.getName().equals(recording.getUserId())) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
			}
			OutputTypeResponseDTO response = outputTypeService.saveOutputType(request.getRecordingId(),
					request.getOutputType());
			return ResponseEntity.ok(response);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", e.getMessage()));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("status", "FAILED", "message", "Output type could not be saved."));
		}
	}
}
