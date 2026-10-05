#!/usr/bin/env python3
"""NAV-020: validate an offline pack manifest against OfflinePackManifest in docs/architecture/api/openapi.yaml.

    python3 scripts/validate_manifest.py [--spec ../docs/architecture/api/openapi.yaml] MANIFEST.json|-

Needs jsonschema + PyYAML (scripts/requirements-dev.txt; the pack step runs it with CONTRACT_PYTHON /
backend/.venv/bin/python). Exit 0 = valid; 1 = invalid (one line per problem on stdout); 2 = usage/dependency.
"""
import argparse
import json
import os
import sys

try:
    import yaml
    from jsonschema import Draft202012Validator, FormatChecker
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012
except ImportError as e:  # pragma: no cover
    print(f"validate_manifest: missing dependency ({e}); pip install -r scripts/requirements-dev.txt")
    sys.exit(2)

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SPEC = os.path.normpath(os.path.join(HERE, "..", "..", "docs", "architecture", "api", "openapi.yaml"))
URI = "urn:nav:openapi"


def problems(spec, manifest):
    reg = Registry().with_resource(URI, Resource.from_contents(spec, default_specification=DRAFT202012))
    v = Draft202012Validator({"$ref": f"{URI}#/components/schemas/OfflinePackManifest"}, registry=reg,
                             format_checker=FormatChecker())
    return [f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message[:200]}" for e in v.iter_errors(manifest)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spec", default=DEFAULT_SPEC)
    ap.add_argument("manifest")
    a = ap.parse_args()
    with open(a.spec, encoding="utf-8") as f:
        spec = yaml.safe_load(f)
    raw = sys.stdin.read() if a.manifest == "-" else open(a.manifest, encoding="utf-8").read()
    try:
        manifest = json.loads(raw)
    except ValueError as e:
        print(f"not JSON: {e}")
        return 1
    out = problems(spec, manifest)
    for p in out:
        print(p)
    return 1 if out else 0


if __name__ == "__main__":
    sys.exit(main())
