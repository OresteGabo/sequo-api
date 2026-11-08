#!/usr/bin/env python3
"""Check or synchronize the common design source in independently built apps."""

import argparse
import hashlib
from pathlib import Path


REPOSITORIES = ("Sequo", "SequoVendor", "SequoHub", "SequoRider")
RELATIVE_SOURCE = Path(
    "shared/src/commonMain/kotlin/dev/orestegabo/sequo/design/SequoDesignSystem.kt"
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--sync", action="store_true", help="Copy Sequo's canonical source to the other three apps.")
    args = parser.parse_args()
    source = args.workspace / "Sequo" / RELATIVE_SOURCE
    if not source.is_file() or source.is_symlink():
        parser.error(f"Canonical design source is missing or a symlink: {source}")
    canonical = source.read_bytes()
    digest = hashlib.sha256(canonical).hexdigest()
    failed = False
    for repository in REPOSITORIES:
        checkout = args.workspace / repository
        target = checkout / RELATIVE_SOURCE
        if not checkout.is_dir() or target.is_symlink():
            print(f"FAIL {repository}: checkout missing or source is a symlink")
            failed = True
            continue
        if args.sync and repository != "Sequo":
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(canonical)
        if not target.is_file() or target.read_bytes() != canonical:
            print(f"FAIL {repository}: shared design source differs; review then use --sync")
            failed = True
        else:
            print(f"PASS {repository}: {digest}")
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
