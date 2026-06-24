package edu.cit.audioscholar.persistence;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class GenericDocumentId implements Serializable {

	@Column(name = "collection_name", nullable = false, length = 100)
	private String collectionName;

	@Column(name = "document_id", nullable = false, length = 160)
	private String documentId;

	protected GenericDocumentId() {
	}

	public GenericDocumentId(String collectionName, String documentId) {
		this.collectionName = collectionName;
		this.documentId = documentId;
	}

	public String getCollectionName() {
		return collectionName;
	}

	public String getDocumentId() {
		return documentId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof GenericDocumentId that))
			return false;
		return Objects.equals(collectionName, that.collectionName) && Objects.equals(documentId, that.documentId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(collectionName, documentId);
	}
}
