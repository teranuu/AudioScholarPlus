package edu.cit.audioscholar.service;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.MultiSourceJob;

@Service
public class MultiSourceJobRepository {
	private static final String COLLECTION_NAME = "multiSourceJobs";

	private final FirebaseService firebaseService;

	public MultiSourceJobRepository(FirebaseService firebaseService) {
		this.firebaseService = firebaseService;
	}

	public MultiSourceJob save(MultiSourceJob job) {
		firebaseService.saveData(COLLECTION_NAME, job.getJobId(), job.toMap());
		return job;
	}

	public Map<String, Object> findById(String jobId) {
		return firebaseService.getData(COLLECTION_NAME, jobId);
	}
	public boolean claim(String jobId) {
		return firebaseService.claimQueuedJob(COLLECTION_NAME, jobId);
	}

	public boolean saveIfNonterminal(MultiSourceJob job) {
		return firebaseService.saveJobIfNonterminal(COLLECTION_NAME, job.getJobId(), job.toMap());
	}

	public boolean transitionTerminal(MultiSourceJob job, String eventType, String occurrenceKey,
			List<Integer> objectives, Map<String, Object> payload) {
		return firebaseService.transitionJobToTerminal(COLLECTION_NAME, job.getJobId(), job.toMap(), eventType,
				occurrenceKey, objectives, payload);
	}

	public void markTerminalEventPublished(String jobId) {
		firebaseService.updateDataWithMap(COLLECTION_NAME, jobId, Map.of("terminalEventPending", false));
	}

	public List<Map<String, Object>> findStaleNonterminal(List<String> activeStatuses, Date cutoff, int limit) {
		return firebaseService.queryCollectionWhereInBefore(COLLECTION_NAME, "status", activeStatuses, "updatedAt",
				cutoff, limit);
	}

	public List<Map<String, Object>> findPendingTerminalEvents(int limit) {
		return firebaseService.queryCollectionLimited(COLLECTION_NAME, "terminalEventPending", true, limit);
	}

	public boolean tryAcquireReconciliationLease(String owner, Date leaseUntil) {
		return firebaseService.tryAcquireLease("systemMaintenanceLocks", "multiSourceReconciliation", owner,
				leaseUntil);
	}

	public boolean failIfNonterminalAndStale(String jobId, Date cutoff) {
		return firebaseService.failJobIfNonterminalAndStale(COLLECTION_NAME, jobId, cutoff);
	}
}
