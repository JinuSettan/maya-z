#!/usr/bin/env python3
"""Validate TESSA JSONL training data without loading any model."""
import argparse
import json
from pathlib import Path

REQUIRED = ("instruction", "response")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Path to JSONL file")
    args = parser.parse_args()
    path = Path(args.input)

    if not path.is_file():
        raise SystemExit(f"File not found: {path}")

    total = 0
    errors = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, raw in enumerate(handle, start=1):
            if not raw.strip():
                continue
            try:
                item = json.loads(raw)
            except json.JSONDecodeError as exc:
                errors.append(f"line {line_no}: invalid JSON ({exc.msg})")
                continue
            if not isinstance(item, dict):
                errors.append(f"line {line_no}: each row must be a JSON object")
                continue
            missing = [key for key in REQUIRED if not isinstance(item.get(key), str) or not item[key].strip()]
            if missing:
                errors.append(f"line {line_no}: missing/empty string field(s): {', '.join(missing)}")
                continue
            if "context" in item and not isinstance(item["context"], str):
                errors.append(f"line {line_no}: context must be a string when supplied")
                continue
            total += 1

    if errors:
        print(f"Found {len(errors)} issue(s):")
        for error in errors[:50]:
            print(" -", error)
        raise SystemExit(1)

    if total == 0:
        raise SystemExit("No valid examples found.")
    print(f"OK: {total} valid training example(s) in {path}")

if __name__ == "__main__":
    main()
