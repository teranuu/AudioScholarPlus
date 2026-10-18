package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class QualityReportRepositoryTest {
	@Test
	void selectsLatestAttemptNumerically() {
		FirebaseService firebase = mock(FirebaseService.class);
		when(firebase.queryCollection("qualityReports", "recordingId", "r1")).thenReturn(List
				.of(Map.of("reportId", "nine", "attemptId", "r1:9"), Map.of("reportId", "ten", "attemptId", "r1:10")));

		Map<String, Object> latest = new QualityReportRepository(firebase).findByRecordingId("r1");

		assertThat(latest.get("reportId")).isEqualTo("ten");
	}
}
