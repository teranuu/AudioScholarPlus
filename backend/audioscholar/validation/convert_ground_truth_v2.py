#!/usr/bin/env python3
"""Validate reviewer CSV instruments and produce ground-truth schema version 2."""
import argparse, csv, json
from pathlib import Path

ISSUE_TYPES = {"AUDIO_CLIPPING", "INAUDIBLE_SPEECH", "UNCLEAR_AUDIO", "HIGH_BACKGROUND_NOISE", "MISSING_AUDIO"}

def rows(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))

def boolean(value, field):
    normalized = str(value).strip().lower()
    if normalized not in {"true", "false"}:
        raise ValueError(f"{field} must be true or false")
    return normalized == "true"

def require(row, names, source):
    missing = [name for name in names if not str(row.get(name, "")).strip()]
    if missing: raise ValueError(f"{source}: missing {', '.join(missing)}")

def load_export(path):
    datasets = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            item = json.loads(line)
            datasets.setdefault(item["dataset"], []).append(item["data"])
    manifest = datasets.get("manifest", [None])[0]
    if not manifest or not manifest.get("exportBindingHash"):
        raise ValueError("export manifest has no exportBindingHash")
    return datasets, manifest

def convert(args):
    datasets, manifest = load_export(args.export)
    reports = {r.get("reportId"): r for r in datasets.get("qualityReports", [])}
    metadata = {str(r.get("recordingId") or r.get("id")): r
                for r in datasets.get("audio_metadata", [])}
    templates = {(c.get("templateId"), str(c.get("templateVersion"))): c
                 for c in datasets.get("warningCandidates", [])}
    reviewed, seen_reports = [], set()
    corpus_versions = set()
    for index, row in enumerate(rows(args.reviewed), 2):
        require(row, ["studySessionId", "recordingId", "attemptId", "reportId", "reviewerKey",
                      "reviewComplete", "issueFreeConfirmed", "reviewedAt", "corpusVersion", "instrumentVersion"],
                f"reviewed-recordings.csv:{index}")
        report_id = row["reportId"].strip()
        if report_id in seen_reports: raise ValueError(f"duplicate reviewed report {report_id}")
        if report_id not in reports: raise ValueError(f"unknown reportId {report_id}")
        report = reports[report_id]
        if report.get("analysisStatus") != "COMPLETED" or report.get("analysisMode") != "FULL_PCM":
            raise ValueError(f"report {report_id} is not eligible for T3-T5")
        if row["recordingId"].strip() != str(report.get("recordingId")):
            raise ValueError(f"recordingId does not match report {report_id}")
        if row["attemptId"].strip() != str(reports[report_id].get("attemptId")):
            raise ValueError(f"attemptId does not match report {report_id}")
        workflow = metadata.get(row["recordingId"].strip())
        if not workflow or row["studySessionId"].strip() != str(workflow.get("studySessionId")):
            raise ValueError(f"studySessionId does not match recording {row['recordingId']}")
        complete = boolean(row["reviewComplete"], "reviewComplete")
        if not complete: raise ValueError(f"incomplete review for report {report_id}")
        seen_reports.add(report_id); corpus_versions.add(row["corpusVersion"].strip())
        reviewed.append({**row, "reviewComplete": complete,
                         "issueFreeConfirmed": boolean(row["issueFreeConfirmed"], "issueFreeConfirmed")})
    if len(corpus_versions) != 1: raise ValueError("reviews must use exactly one corpusVersion")

    segments, annotations = [], set()
    segment_count = {report_id: 0 for report_id in seen_reports}
    for index, row in enumerate(rows(args.segments), 2):
        require(row, ["annotationId", "reportId", "issueType", "startMs", "endMs"],
                f"problem-segments.csv:{index}")
        annotation = row["annotationId"].strip(); report_id = row["reportId"].strip()
        if annotation in annotations: raise ValueError(f"duplicate annotationId {annotation}")
        if report_id not in seen_reports: raise ValueError(f"unknown or unreviewed reportId {report_id}")
        if row["issueType"].strip() not in ISSUE_TYPES: raise ValueError(f"unknown issueType {row['issueType']}")
        start, end = int(row["startMs"]), int(row["endMs"])
        duration = int(reports[report_id].get("totalDurationMs") or 0)
        if start < 0 or end <= start or duration <= 0 or end > duration:
            raise ValueError(f"annotation {annotation} is outside recording duration")
        annotations.add(annotation); segment_count[report_id] += 1
        segments.append({"annotationId": annotation, "reportId": report_id,
                         "issueType": row["issueType"].strip(), "startMs": start, "endMs": end,
                         "comments": row.get("comments", "")})
    for review in reviewed:
        if review["issueFreeConfirmed"] == (segment_count[review["reportId"]] > 0):
            raise ValueError(f"issueFreeConfirmed contradicts annotations for {review['reportId']}")

    template_reviews, seen_templates = [], set()
    for index, row in enumerate(rows(args.templates), 2):
        require(row, ["templateId", "templateVersion", "reviewerConforms"],
                f"warning-template-reviews.csv:{index}")
        key = (row["templateId"].strip(), row["templateVersion"].strip())
        if key in seen_templates: raise ValueError(f"duplicate template review {key}")
        if key not in templates: raise ValueError(f"unknown template/version {key}")
        seen_templates.add(key)
        template_reviews.append({"templateId": key[0], "templateVersion": key[1],
                                 "reviewerConforms": boolean(row["reviewerConforms"], "reviewerConforms"),
                                 "comments": row.get("comments", "")})

    output = {"schemaVersion": 2, "exportManifestHash": manifest["exportBindingHash"],
              "corpusVersion": next(iter(corpus_versions)), "reviewedRecordings": reviewed,
              "problemSegments": segments, "warningTemplateReviews": template_reviews}
    args.output.write_text(json.dumps(output, indent=2), encoding="utf-8")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("export", type=Path); parser.add_argument("reviewed", type=Path)
    parser.add_argument("segments", type=Path); parser.add_argument("templates", type=Path)
    parser.add_argument("output", type=Path)
    convert(parser.parse_args())
