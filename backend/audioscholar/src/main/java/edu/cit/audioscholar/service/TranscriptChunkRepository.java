package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;

import edu.cit.audioscholar.exception.FirestoreInteractionException;
import edu.cit.audioscholar.model.TranscriptChunk;

@Repository
public class TranscriptChunkRepository {
	private static final String SUBCOLLECTION = "transcript_chunks";

	private final Firestore firestore;
	private final String metadataCollection;

	public TranscriptChunkRepository(Firestore firestore,
			@Value("${firebase.firestore.collection.audiometadata}") String metadataCollection) {
		this.firestore = firestore;
		this.metadataCollection = metadataCollection;
	}

	public void save(String metadataId, TranscriptChunk chunk) {
		try {
			chunk.setUpdatedAt(com.google.cloud.Timestamp.now());
			chunkCollection(metadataId).document(documentId(chunk.getIndex())).set(chunk.toMap()).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FirestoreInteractionException("Interrupted while saving transcript chunk", e);
		} catch (ExecutionException e) {
			throw new FirestoreInteractionException("Failed to save transcript chunk", e);
		}
	}

	public List<TranscriptChunk> findAll(String metadataId) {
		try {
			QuerySnapshot snapshot = chunkCollection(metadataId).orderBy("index").get().get();
			List<TranscriptChunk> chunks = new ArrayList<>();
			for (DocumentSnapshot document : snapshot.getDocuments()) {
				chunks.add(TranscriptChunk.fromMap(document.getData()));
			}
			return chunks;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FirestoreInteractionException("Interrupted while loading transcript chunks", e);
		} catch (ExecutionException e) {
			throw new FirestoreInteractionException("Failed to load transcript chunks", e);
		}
	}

	private com.google.cloud.firestore.CollectionReference chunkCollection(String metadataId) {
		return firestore.collection(metadataCollection).document(metadataId).collection(SUBCOLLECTION);
	}

	private String documentId(int index) {
		return String.format("%04d", index);
	}
}
