package edu.cit.audioscholar.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.model.SummaryKeyPoint;

class SummaryServiceTest {

	@Test
	void createSummaryBatchesKeyPointsOnce() throws Exception {
		SummaryRepository repository = mock(SummaryRepository.class);
		RecordingService recordingService = mock(RecordingService.class);
		SummaryService service = new SummaryService(repository, recordingService);
		SummaryKeyPoint keyPoint = new SummaryKeyPoint();
		keyPoint.setText("Key point");
		Summary summary = new Summary();
		summary.setSummaryId("summary-1");
		summary.setRecordingId("recording-1");
		summary.setSummaryKeyPoints(List.of(keyPoint));
		when(recordingService.getRecordingById("recording-1")).thenReturn(null);

		service.createSummary(summary);

		ArgumentCaptor<List<SummaryKeyPoint>> points = ArgumentCaptor.forClass(List.class);
		verify(repository).saveKeyPoints(points.capture());
		verify(repository).save(summary);
		org.junit.jupiter.api.Assertions.assertEquals("summary-1", points.getValue().get(0).getSummaryId());
	}
}
