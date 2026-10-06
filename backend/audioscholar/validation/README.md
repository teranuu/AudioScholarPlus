# Validation export and evaluation

This tooling measures backend-log objectives 2, 3, 4, 5, 6, 7, 9, 11, 12, 13, and 14 for web workflows only. Set `VALIDATION_TELEMETRY_ENABLED=true` in the study environment. Events are retained for 180 days by default (`VALIDATION_RETENTION_DAYS`) and Firestore TTL must be deployed from `firestore.indexes.json`.

Export as an administrator:

```text
GET /api/admin/validation/export?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z&clientSource=WEB&format=jsonl
```

Reviewer ground truth is a JSON object with `schemaVersion: 1` and these arrays:

- `problemSegments`: `recordingId`, `issueType`, `startMs`, `endMs`.
- `duplicatePairs`: `decisionId`, `duplicate`.
- `sourceLabels`: `keyPointId`, `sourceLabel`.
- `overlapBoundaries`: `sourcePairId`, `sourceAStartMs`, `sourceAEndMs`, `sourceBStartMs`, `sourceBEndMs`.

Run the evaluator from `backend/audioscholar` after compiling:

```text
java -cp target/classes;<runtime-dependencies> edu.cit.audioscholar.validation.ValidationEvaluator export.jsonl validation/ground-truth.example.json evaluation-output
```

The output directory contains `metrics.json` (including diagnostics) and `metrics.csv`. Empty denominators, malformed annotations, missing references, unmappable warning evidence, or any `measurementIncomplete` workflow produce `NOT_EVALUABLE`.
