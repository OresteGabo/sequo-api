#!/usr/bin/env python3
"""Detect reference-codec drift between the API, four mobile apps and admin."""
import argparse
import hashlib
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    api = args.workspace / "sequo-api"
    relative = Path("dev/orestegabo/sequo/reference/HumanReference.kt")
    canonical = api / "src/main/kotlin" / relative
    typescript = api / "contracts/HumanReference.ts"
    pairs = [(name, canonical, args.workspace / name / "shared/src/commonMain/kotlin" / relative)
             for name in ("Sequo", "SequoVendor", "SequoHub", "SequoRider")]
    pairs.append(("sequo-admin", typescript, args.workspace / "sequo-admin/src/humanReference.ts"))
    pairs.append(("sequo-admin vectors", api / "contracts/human-reference-v1.json",
                  args.workspace / "sequo-admin/tests/fixtures/human-reference-v1.json"))
    failed = False
    for name, source, target in pairs:
        if source.is_symlink() or target.is_symlink() or not source.is_file() or not target.is_file() or source.read_bytes() != target.read_bytes():
            print(f"FAIL {name}: reference codec missing or differs from the API contract")
            failed = True
        else:
            print(f"PASS {name}: {hashlib.sha256(source.read_bytes()).hexdigest()}")
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
