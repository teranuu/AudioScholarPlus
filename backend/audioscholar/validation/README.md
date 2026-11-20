# Validation export and evaluation

Grounded GO3 merging is a controlled-study option (`MULTI_SOURCE_GROUNDED_MERGE_ENABLED=true`).
Jobs record `pipelineVersion`; retries retain that version. New exports include
`multiSourceGroupCoverage`, `multiSourceStageTimings`, claim quotations in `sourceKeyPoints`, and claim IDs on
summary points and flashcards. A group records claim coverage, not a judgment for
every possible pair. Retained outcome R9 still requires independent duplicate review.
For grounded groups, reviewers provide `duplicatePairs` with `leftKeyPointId`,
`rightKeyPointId`, and `duplicate` for each predicted cross-source pair.
Merge latency is a descriptive performance record (DP). Media and document-only
jobs use separate clocks and have no pass/fail time threshold.

This tooling exports raw evidence for R1–R11, UAT1–UAT5, and DP for Web or Android
workflows. Numeric event codes 1–14 are historical instrument tags. Code 12 supplies
DP evidence, and code 14 supplies source evidence only. Module 2 uses attempt-scoped
reports, generated warning evidence, viewport receipts, and ground-truth schema v2.
Set both `VALIDATION_TELEMETRY_ENABLED=true` and `VALIDATION_EXPORT_ENABLED=true`
in the controlled-study environment. Events are retained for 180 days by default.

Export as an administrator:

```text
GET /api/admin/validation/export?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z&clientSource=WEB&format=jsonl
```

For manual reviewer assessment of generated output accuracy (R1) or merged-summary
completeness (R8), request reviewer text explicitly. The query parameter still uses
historical numeric event tags:

```text
GET /api/admin/validation/export?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z&clientSource=WEB&format=zip&includeText=true&objectiveCodes=1,10
```

The manifest contains the SRS v1.1 validation plan, current outcome and UAT evidence
statuses, legacy event-code crosswalk, and raw evidence collections. Text remains
redacted by default. Filtered exports use event tags to retain associated Firestore
evidence. R2, R6, R7, and R10 require participant evidence outside this evaluator.

Reviewers inspect each complete recording before seeing system flags, then fill in `reviewed-recordings.csv`, `problem-segments.csv`, and `warning-template-reviews.csv`. Convert and validate them:

```text
python validation/convert_ground_truth_v2.py export.jsonl validation/reviewed-recordings.csv validation/problem-segments.csv validation/warning-template-reviews.csv ground-truth.json
```

The converter rejects unknown reports/templates, duplicates, incomplete reviews, mismatched attempts, invalid or out-of-duration boundaries, contradictory issue-free declarations, and mixed corpus versions. Its result is bound to the export manifest.

The published synthetic fixture set in `controlled-corpus/` is versioned as `module2-controlled-corpus-v1`; use that exact value in `reviewed-recordings.csv` when those fixtures are reviewed. Regenerate the deterministic WAV fixtures with `python validation/controlled-corpus/generate_corpus.py`.

Run the evaluator from `backend/audioscholar` after compiling:

```text
java -cp target/classes;<runtime-dependencies> edu.cit.audioscholar.validation.ValidationEvaluator export.jsonl ground-truth.json evaluation-output
```

The output contains `metrics.json`, `metrics.csv`, `descriptive-performance.json`,
`quality-matches.json`, and `quality-matches.csv`. Metric keys remain historical event
codes for compatibility: 2, 6, 7, 8, and 9 are UAT checks; 1, 3, 4, 5, 11, and 13
support retained research outcomes. Codes 12 and 14 are absent from pass/fail metrics.
Code 9 scores admission and queueing of a valid batch; full processing success is
reported separately in diagnostics. Code 2 checks confirmation before processing.
Code 6 covers eligible media report delivery. Codes 3–5 use one-to-one matching at
IoU ≥ 0.50. Code 7 requires a visible receipt and counts unmappable issues as
uncovered. Code 8 checks automated and expert warning wording conformance. R8
completeness and participant outcomes require independent review; the export does
not imply that those outcomes passed.

Automated checks used by the readiness suite:

```text
mvnw.cmd -Dtest=Module2ProcessingLoggingSmokeTest test
mvnw.cmd test
python -m unittest discover -s validation -p test_convert_ground_truth_v2.py
cd ../../frontend_web/audioscholar-app
npm run test:warning-exposure
npm run build
```

`Module2ProcessingLoggingSmokeTest` runs every controlled-corpus case and then exercises the production detector,
report/event persistence, warning mapping and candidate generation, viewport receipt, terminal event, export, and
T3–T8 evaluator path using an in-memory Firestore boundary.
