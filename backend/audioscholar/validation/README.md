# Validation export and evaluation

This tooling measures T2–T14 for web workflows. Module 2 uses attempt-scoped reports, generated warning evidence, viewport receipts, and ground-truth schema v2. Set `VALIDATION_TELEMETRY_ENABLED=true` in the controlled-study environment. Events are retained for 180 days by default.

Export as an administrator:

```text
GET /api/admin/validation/export?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z&clientSource=WEB&format=jsonl
```

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

The output contains `metrics.json`, `metrics.csv`, `quality-matches.json`, and `quality-matches.csv`. T3–T5 use deterministic one-to-one matching at IoU ≥ 0.50. T7 requires an `INDICATOR_VISIBLE` receipt. T8 requires automated and expert conformance. Missing evidence is objective-scoped.

Automated checks used by the readiness suite:

```text
mvnw.cmd test
python -m unittest discover -s validation -p test_convert_ground_truth_v2.py
cd ../../frontend_web/audioscholar-app
npm run test:warning-exposure
npm run build
```
