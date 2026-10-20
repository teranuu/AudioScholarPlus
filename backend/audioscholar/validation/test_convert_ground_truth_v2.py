import csv
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import convert_ground_truth_v2 as converter


class GroundTruthConverterTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.export = self.root / "export.jsonl"
        self.reviewed = self.root / "reviewed.csv"
        self.segments = self.root / "segments.csv"
        self.templates = self.root / "templates.csv"
        self.output = self.root / "ground-truth.json"
        datasets = [
            ("manifest", {"exportBindingHash": "bound-export"}),
            ("audio_metadata", {"recordingId": "recording-1", "studySessionId": "session-1"}),
            ("qualityReports", {"reportId": "report-1", "recordingId": "recording-1",
                                "attemptId": "recording-1:1", "analysisStatus": "COMPLETED",
                                "analysisMode": "FULL_PCM", "totalDurationMs": 10_000}),
            ("warningCandidates", {"templateId": "quality-noise", "templateVersion": "1"}),
        ]
        self.export.write_text("".join(json.dumps({"dataset": name, "data": data}) + "\n"
                                       for name, data in datasets), encoding="utf-8")
        self.write_csv(self.reviewed,
                       ["studySessionId", "recordingId", "attemptId", "reportId", "reviewerKey",
                        "reviewComplete", "issueFreeConfirmed", "reviewedAt", "corpusVersion",
                        "instrumentVersion"],
                       [["session-1", "recording-1", "recording-1:1", "report-1", "reviewer-a",
                         "true", "false", "2026-10-18T00:00:00Z", "module2-controlled-corpus-v1", "1"]])
        self.write_csv(self.segments, ["annotationId", "reportId", "issueType", "startMs", "endMs", "comments"],
                       [["annotation-1", "report-1", "HIGH_BACKGROUND_NOISE", "1000", "5000", ""]])
        self.write_csv(self.templates, ["templateId", "templateVersion", "reviewerConforms", "comments"],
                       [["quality-noise", "1", "true", ""]])

    def tearDown(self):
        self.temporary.cleanup()

    def test_binds_valid_ground_truth_to_export_and_corpus(self):
        converter.convert(self.arguments())

        result = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertEqual(2, result["schemaVersion"])
        self.assertEqual("bound-export", result["exportManifestHash"])
        self.assertEqual("module2-controlled-corpus-v1", result["corpusVersion"])
        self.assertEqual("annotation-1", result["problemSegments"][0]["annotationId"])

    def test_rejects_annotation_outside_recording_duration(self):
        self.write_csv(self.segments, ["annotationId", "reportId", "issueType", "startMs", "endMs", "comments"],
                       [["annotation-1", "report-1", "HIGH_BACKGROUND_NOISE", "1000", "11000", ""]])

        with self.assertRaisesRegex(ValueError, "outside recording duration"):
            converter.convert(self.arguments())

    def test_rejects_mismatched_attempt_identity(self):
        self.write_csv(self.reviewed,
                       ["studySessionId", "recordingId", "attemptId", "reportId", "reviewerKey",
                        "reviewComplete", "issueFreeConfirmed", "reviewedAt", "corpusVersion",
                        "instrumentVersion"],
                       [["session-1", "recording-1", "recording-1:2", "report-1", "reviewer-a",
                         "true", "false", "2026-10-18T00:00:00Z", "module2-controlled-corpus-v1", "1"]])

        with self.assertRaisesRegex(ValueError, "attemptId does not match"):
            converter.convert(self.arguments())

    def arguments(self):
        return SimpleNamespace(export=self.export, reviewed=self.reviewed, segments=self.segments,
                               templates=self.templates, output=self.output)

    @staticmethod
    def write_csv(path, headers, rows):
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(headers)
            writer.writerows(rows)


if __name__ == "__main__":
    unittest.main()
