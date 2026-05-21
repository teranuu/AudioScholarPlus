package edu.cit.audioscholar.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GenericDocumentRepository extends JpaRepository<GenericDocument, GenericDocumentId> {

	List<GenericDocument> findByIdCollectionName(String collectionName);

	long countByIdCollectionName(String collectionName);
}
