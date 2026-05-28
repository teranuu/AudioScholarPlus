package edu.cit.audioscholar.service;

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
}
