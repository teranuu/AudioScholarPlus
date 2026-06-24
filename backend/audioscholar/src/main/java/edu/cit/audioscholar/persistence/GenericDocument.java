package edu.cit.audioscholar.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_documents")
public class GenericDocument {

	@EmbeddedId
	private GenericDocumentId id;

	@Column(name = "data_json", nullable = false, columnDefinition = "TEXT")
	private String dataJson;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected GenericDocument() {
	}

	public GenericDocument(String collectionName, String documentId, String dataJson) {
		this.id = new GenericDocumentId(collectionName, documentId);
		this.dataJson = dataJson;
	}

	@PrePersist
	void prePersist() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = Instant.now();
	}

	public GenericDocumentId getId() {
		return id;
	}

	public String getDataJson() {
		return dataJson;
	}

	public void setDataJson(String dataJson) {
		this.dataJson = dataJson;
	}
}
