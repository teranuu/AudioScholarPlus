CREATE TABLE IF NOT EXISTS app_documents (
    collection_name VARCHAR(100) NOT NULL,
    document_id VARCHAR(160) NOT NULL,
    data_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (collection_name, document_id)
);

CREATE INDEX IF NOT EXISTS idx_app_documents_collection ON app_documents (collection_name);
