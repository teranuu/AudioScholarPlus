# AudioScholar+ alignment audit

Reference documents: the user-supplied SRS_AudioScholarPlus.pdf (44 pages) and SDD_AudioScholarPlus.pdf (61 pages). The older repository SRS/SDD describe AudioScholar and are not substitutes for these references.

Status: **partial implementation; not acceptance-ready**. This is a code inspection, not a certification of the empirical targets in the documents.

| Requirement group | Evidence and outstanding work |
| --- | --- |
| FR-1.1.1–1.1.7: confirmed output type | Web selection and backend validation exist. Android multi-source screen and single-recording processing dialog require one of three types before submission. |
| FR-1.2.1–1.2.8: structured output and publication | Web and Android review cards flip and navigate for merged and single-recording results. Comprehensive structure validation, bounded repair and source coverage are incomplete. Failed semantic processing uses checkpoint/retry handling instead of publishing COMPLETE. |
| FR-1.2.9–1.2.10: output evaluation | Requires prespecified cases and independent relevance/structure judgments. Not demonstrated by unit tests. |
| FR-2.1.1–2.1.8: quality report | Backend and web report code exist; Android recording details now show all-clear, unavailable and flagged states from the summary report. Offline report persistence and complete media-analysis coverage require testing. |
| FR-2.1.9: precision/recall | Requires labeled media evaluation; not established by this audit. |
| FR-2.2.1–2.2.9: warnings and visibility receipts | Web has warning exposure logic and tests. Android now requests linked warnings, renders them beside single-recording points/cards and records half-visible and expansion receipts. Receipts distinguish client platforms and the validation export accepts Android. This still needs device verification; merged output needs warnings beside every affected point/card. |
| FR-2.2.10–2.2.11: exposure and usefulness | Requires recorded cohort evidence and receipt accounting. Not established. |
| FR-3.1.1–3.1.7: admission and upload | Backend, web and Android accept 2–5 sources. PDF extractability is checked server-side before storing that source. Android shows stable labels and status and retains active job ID for reopening. Per-source byte progress and client-side media duration validation remain missing. |
| FR-3.1.8–3.1.9: coverage and retry | Checkpoints exist. Removed the silent 12-key-point response truncation and schema cap. Full section batching/coverage, atomic submission idempotency and interruption recovery still require work and tests. |
| FR-3.2.1–3.2.8: merging and incomplete state | Duplicate groups require every pair to be confirmed; differing flashcard answers and numeric punctuation are preserved. Failed semantic processing no longer completes successfully. Android merged screen shows status, cards and partial content when present. Explicit missing-stage accounting and manual retry remain incomplete. |
| FR-3.2.9–3.2.11: evidence and provenance | Web and Android merged screens show stored contributor labels and filenames. Exact evidence navigation, PDF page references, source-specific timing bases and consistent lineage across overview/sections/cards remain incomplete. Passage matching now requires exact case-preserving text; unverified paraphrases remain unresolved. A validated semantic evidence path is still needed. |
| FR-3.2.12–3.2.16: success, completeness and attribution evaluation | Requires fixed eligible batches, independent reviewers and 12-person platform cohorts. No passing result inferred from implementation. |
| NFR-PER-1–10 | Device performance, per-source progress, offline opening, separate document/media clocks, and verified boundary accuracy need measured acceptance evidence. |
| NFR-SEC-1–6 | Authentication/ownership checks exist in controllers. Source access, private storage, consent handling and cross-account isolation require end-to-end verification. |
| NFR-REL-1–9 | Existing retries and failure states cover some paths. Android device verification, offline reports and explicit incomplete-result rendering remain outstanding. |
| NFR-REL-10–19 | Validation infrastructure exists; frozen eligibility, cohort selection, consent, independent judgments and versioned calculations require an evidence audit and actual study data. |
| NFR-REL-20–22 | Compatible versioned records, staged rollout, recoverable checkpoints, bounded request budgets and the complete adversarial/regression matrix are not fully verified. |

SDD component names are design responsibilities, not a requirement to rename functioning classes. Major missing responsibilities include passage/page evidence navigation, exhaustive claim coverage and publication completeness enforcement across all paths.

Changes in this work are local and do not constitute a deployment or a successful live-service/device acceptance run.

Latest regression adjustments: preserve case-sensitive scientific units and distinct recording intervals during exact deduplication; reject weak vocabulary overlap as provenance; allow terminal sentence punctuation differences in literal evidence matches; enforce Android aggregate byte limits while reading sources with unknown sizes; reset submission identity after configuration changes; clean temporary files on cancellation. These corrections do not close the outstanding requirements above.

Final local smoke run (2026-11-16): backend `mvnw.cmd -q test` passed 293 tests with 0 failures, 0 errors and 1 skipped, including the controlled multi-source instrument smoke, Android receipt authorization and platform-specific validation export; Python converter validation passed 3 tests; web warning exposure passed 2 tests; web production build and lint passed (lint reports one nonblocking Fast Refresh warning); Android `:app:assembleDebug --offline` produced a debug APK. The APK was not exercised on a device because `adb devices` reported none attached. These checks use local and mocked paths and do not establish live Firebase/Gemini behavior or the SRS/SDD cohort, accuracy and performance thresholds.
