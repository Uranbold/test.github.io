"""manifest.json with the exact byte layout of pack-format §3 (task B7)."""

from __future__ import annotations

import json

SCHEMA_VERSION = 1


def build(
    *,
    pack_version: int,
    voice: str,
    vendor: str,
    api_version: str,
    source_rate: int,
    clip_rate: int,
    bitrate_kbps: int,
    encoder_delay: int,
    encoder: str,
    take_method: str,
    join_pause_ms: int,
    then_pause_ms: int,
    processing: dict,
    glossary_revision: str,
    inventory_sha256: str,
    entries: list[dict],
) -> dict:
    """Members in the order of the schema's `required` lists (dicts keep insertion order)."""
    return {
        "schemaVersion": SCHEMA_VERSION,
        "packVersion": pack_version,
        "voiceId": voice,
        "source": {"vendor": vendor, "apiVersion": api_version, "voice": voice},
        "sourceSampleRateHz": source_rate,
        "codec": {
            "name": "aac-lc",
            "container": "adts",
            "fileExtension": ".aac",
            "sampleRateHz": clip_rate,
            "channels": 1,
            "bitrateKbps": bitrate_kbps,
            "encoderDelayFrames": encoder_delay,
            "encoder": encoder,
        },
        "takeMethod": take_method,
        "joinPauseMs": join_pause_ms,
        "thenPauseMs": then_pause_ms,
        "processing": {k: processing[k] for k in ("silenceThresholdDbfs", "edgeMarginMs", "fadeMs", "targetRmsDbfs", "peakCeilingDbfs")},
        "glossaryRevision": glossary_revision,
        "inventorySha256": inventory_sha256,
        "entries": [
            {
                "key": e["key"],
                "take": e["take"],
                "spokenText": e["spokenText"],
                "file": e["file"],
                "frames": e["frames"],
                "encodedFrames": e["encodedFrames"],
                "durationMs": e["durationMs"],
                "sha256": e["sha256"],
            }
            for e in entries
        ],
    }


def dumps(manifest: dict) -> bytes:
    """UTF-8 without BOM, Cyrillic as is, 2-space indent, LF, one final LF."""
    return (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
