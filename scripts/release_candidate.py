#!/usr/bin/env python3
"""Prepare a local/staging evidence bundle. Never deploy or start contractual acceptance implicitly."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import zipfile
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

COMPONENTS = ("sequo-api", "Sequo", "SequoVendor", "SequoRider", "sequo-admin")


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else _digest(stream)


def _digest(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
    return digest.hexdigest()


def write_json(path, data):
    Path(path).write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def provenance(repo):
    # Only names and hashes of tracked differences; never collect environment files or credentials.
    names = git(repo, "diff", "--name-only", "HEAD").splitlines()
    return {"revision": git(repo, "rev-parse", "HEAD"), "trackedChanges": [
        {"path": name, "sha256": sha256(repo / name) if (repo / name).is_file() else None}
        for name in names
    ]}


def artifact_identity(path, component, report):
    if component == "sequo-api" and path.suffix == ".jar":
        with zipfile.ZipFile(path) as archive:
            props = dict(line.split("=", 1) for line in archive.read("BOOT-INF/classes/META-INF/build-info.properties").decode().splitlines()
                         if line and not line.startswith("#") and "=" in line)
        return {"verification": "EMBEDDED_BUILD_INFO", "releaseId": props.get("build.releaseId"),
                "revision": props.get("build.revision")}
    if component == "sequo-admin" and path.name.endswith(".tar.gz"):
        with tarfile.open(path) as archive:
            matches = [entry for entry in archive.getmembers() if entry.isfile() and entry.name in ("release.json", "./release.json", "dist/release.json")]
            if len(matches) != 1:
                raise ValueError("Admin archive must contain one release.json")
            data = json.load(archive.extractfile(matches[0]))
        return {"verification": "EMBEDDED_RELEASE_MANIFEST", "releaseId": data.get("releaseId"),
                "revision": data.get("sourceCommit"), "environment": data.get("environment")}
    if report:
        data = json.loads(report.read_text())
        if data.get("app") != component or data.get("sha256") != sha256(path):
            raise ValueError("Build report does not bind this component and artifact hash")
        return {"verification": "HASH_BOUND_BUILD_REPORT", "releaseId": data.get("releaseId"),
                "revision": data.get("sourceHead"), "environment": data.get("environment"),
                "reportSha256": sha256(report)}
    return {"verification": "UNKNOWN", "note": "Bytes hashed; no source/build identity is claimed for this artifact."}


def prepare(workspace, output, release_id, environment, artifacts, allowed_dirty=(), evidence=(), build_reports=()):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}", release_id):
        raise ValueError("Invalid release identifier")
    if environment not in ("LOCAL_FIXTURE", "STAGING"):
        raise ValueError("This tool does not prepare paid production releases")
    workspace, output = Path(workspace).resolve(), Path(output).resolve()
    if output.exists():
        raise ValueError("Use a new output directory; existing release bundles are not overwritten")
    sources = {name: provenance(workspace / name) for name in COMPONENTS}
    for name, source in sources.items():
        if source["trackedChanges"] and name not in allowed_dirty:
            raise ValueError(f"Tracked changes in {name}; review and explicitly acknowledge them before packaging")
    reports = {label: Path(path).resolve(strict=True) for label, path in (item.split("=", 1) for item in build_reports)}
    inputs = []
    for item in artifacts:
        label, path = item.split("=", 1)
        component, platform = label.split(".", 1)
        if component not in COMPONENTS or not re.fullmatch(r"[a-z0-9_-]+", platform):
            raise ValueError("Artifact must be component.platform=/absolute/path")
        path = Path(path).resolve(strict=True)
        if not path.is_file() or path.suffix.lower() not in (".jar", ".apk", ".zip", ".gz", ".json"):
            raise ValueError("Only explicit build artifact files are accepted")
        if any(entry[0] == label for entry in inputs):
            raise ValueError("Duplicate artifact label")
        identity = artifact_identity(path, component, reports.get(label))
        if identity["verification"] != "UNKNOWN" and (identity.get("releaseId") != release_id or
                identity.get("revision") != sources[component]["revision"] or
                identity.get("environment", environment) != environment):
            raise ValueError("Artifact build identity does not match this release, source revision and environment")
        inputs.append((label, component, platform, path, sha256(path), identity))
    if not inputs:
        raise ValueError("At least one actual build artifact is required")
    # Validate all inputs before creating the immutable output tree.
    evidence_paths = [Path(path).resolve(strict=True) for path in evidence]
    if any(not path.is_file() or path.suffix.lower() not in (".md", ".csv", ".json", ".xml") for path in evidence_paths):
        raise ValueError("Evidence must be an explicit report, matrix or test-result file")
    output.mkdir(parents=True)
    records = []
    for label, component, platform, path, checksum, identity in inputs:
        destination = output / "artifacts" / component / f"{platform}-{path.name}"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        if sha256(destination) != checksum:
            raise ValueError("Artifact changed while packaging; discard the incomplete output and rebuild")
        records.append({"id": label, "path": str(destination.relative_to(output)), "sha256": checksum,
                        "sizeBytes": destination.stat().st_size, "buildIdentity": identity})
        if label in reports:
            report_target = output / "build-reports" / f"{label}.json"
            report_target.parent.mkdir(exist_ok=True)
            shutil.copyfile(reports[label], report_target)
            records.append({"id": f"build-report.{label}", "path": str(report_target.relative_to(output)),
                            "sha256": sha256(report_target), "sizeBytes": report_target.stat().st_size})
    for path in evidence_paths:
        destination = output / "evidence" / path.name
        if destination.exists():
            raise ValueError("Duplicate evidence filename")
        destination.parent.mkdir(exist_ok=True)
        shutil.copyfile(path, destination)
        records.append({"id": f"evidence.{path.name}", "path": str(destination.relative_to(output)), "sha256": sha256(destination), "sizeBytes": destination.stat().st_size})
    migrations = [{"name": path.name, "sha256": sha256(path)} for path in sorted(
        (workspace / "sequo-api/src/main/resources/db/migration").glob("V*__*.sql"))]
    manifest = {"schemaVersion": 1, "releaseId": release_id, "environment": environment,
        "preparedAt": datetime.now(timezone.utc).isoformat(), "sources": sources, "files": records,
        "migrations": migrations, "relaySurface": "API-served relay-operator.html",
        "acceptance": {"durationCalendarDays": 50, "state": "NOT_DELIVERED", "deliveredAt": None},
        "paidLaunch": {"state": "BLOCKED", "providerEvidence": "Yas and Flooz NOT_CONNECTED",
            "note": "This manifest is evidence, not a runtime permission or provider integration."}}
    write_json(output / "release-manifest.json", manifest)
    (output / "SHA256SUMS").write_text("".join(f"{entry['sha256']}  {entry['path']}\n" for entry in records) +
        f"{sha256(output / 'release-manifest.json')}  release-manifest.json\n")
    return manifest


def verify(directory):
    directory = Path(directory).resolve()
    manifest = json.loads((directory / "release-manifest.json").read_text())
    if manifest.get("schemaVersion") != 1 or manifest.get("environment") not in ("LOCAL_FIXTURE", "STAGING") or manifest.get("paidLaunch", {}).get("state") != "BLOCKED":
        raise ValueError("Unsupported release manifest or paid-production claim")
    for record in manifest["files"]:
        path = (directory / record["path"]).resolve()
        if not path.is_relative_to(directory) or sha256(path) != record["sha256"] or path.stat().st_size != record["sizeBytes"]:
            raise ValueError("Release artifact/evidence integrity failure")
        if "buildIdentity" in record:
            component = record["id"].split(".", 1)[0]
            report = next((entry for entry in manifest["files"] if entry["id"] == f"build-report.{record['id']}"), None)
            report_path = (directory / report["path"]).resolve() if report else None
            if report_path and not report_path.is_relative_to(directory):
                raise ValueError("Build report must be inside the release bundle")
            identity = artifact_identity(path, component, report_path)
            if identity != record["buildIdentity"]:
                raise ValueError("Release build identity/report mismatch")
            if identity["verification"] != "UNKNOWN" and (identity.get("releaseId") != manifest["releaseId"] or
                    identity.get("revision") != manifest["sources"][component]["revision"] or
                    identity.get("environment", manifest["environment"]) != manifest["environment"]):
                raise ValueError("Release build identity is not bound to its declared sources")
    expected = {record["path"]: record["sha256"] for record in manifest["files"]}
    if len(expected) != len(manifest["files"]) or not expected:
        raise ValueError("Invalid release file inventory")
    expected["release-manifest.json"] = sha256(directory / "release-manifest.json")
    inventory = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        checksum, relative = line.split("  ", 1)
        if relative in inventory:
            raise ValueError("Duplicate release checksum entry")
        inventory[relative] = checksum
        path = (directory / relative).resolve()
        if not path.is_relative_to(directory) or sha256(path) != checksum:
            raise ValueError("Release checksum inventory failure")
    if inventory != expected:
        raise ValueError("Release checksum inventory must include every file and the manifest")
    receipt_path = directory / "delivery-receipt.json"
    receipt_hash = directory / "delivery-receipt.sha256"
    if receipt_path.exists() or receipt_hash.exists():
        if not receipt_path.exists() or not receipt_hash.exists() or receipt_hash.read_text().strip() != sha256(receipt_path):
            raise ValueError("Delivery receipt integrity failure")
        receipt = json.loads(receipt_path.read_text())
        canonical = delivery_receipt(directory / "release-manifest.json", receipt["actualDeliveredAt"],
                                     receipt["calendarTimezone"], receipt["writtenDeliveryReference"])
        if any(receipt.get(key) != value for key, value in canonical.items() if key != "recordedAt"):
            raise ValueError("Delivery receipt does not match this release and its calendar window")
    return manifest


def delivery_receipt(manifest_path, delivered_at, zone, reference, now=None):
    delivered = datetime.fromisoformat(delivered_at.replace("Z", "+00:00"))
    if delivered.tzinfo is None or delivered.utcoffset() is None:
        raise ValueError("Actual delivery timestamp must include its UTC offset")
    if delivered > (now or datetime.now(timezone.utc)):
        raise ValueError("A future planned delivery cannot start acceptance")
    if not reference.strip() or len(reference) > 1000:
        raise ValueError("A written actual-delivery reference is required")
    manifest = json.loads(Path(manifest_path).read_text())
    delivered_date = delivered.astimezone(ZoneInfo(zone)).date()
    return {"releaseId": manifest["releaseId"], "manifestSha256": sha256(manifest_path),
        "recordedAt": datetime.now(timezone.utc).isoformat(), "actualDeliveredAt": delivered.isoformat(),
        "calendarTimezone": zone, "writtenDeliveryReference": reference.strip(), "durationCalendarDays": 50,
        "deliveryLocalDate": delivered_date.isoformat(), "deadlineLocalDate": (delivered_date + timedelta(days=50)).isoformat(),
        "state": "DELIVERY_RECORDED", "note": "No automatic acceptance or legal conclusion; retain written anomaly reports."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("prepare")
    build.add_argument("--workspace", required=True); build.add_argument("--output", required=True)
    build.add_argument("--release-id", required=True); build.add_argument("--environment", choices=("LOCAL_FIXTURE", "STAGING"), required=True)
    build.add_argument("--artifact", action="append", default=[]); build.add_argument("--allow-dirty-component", action="append", choices=COMPONENTS, default=[])
    build.add_argument("--evidence", action="append", default=[])
    build.add_argument("--build-report", action="append", default=[])
    check = sub.add_parser("verify"); check.add_argument("directory")
    deliver = sub.add_parser("record-delivery")
    deliver.add_argument("directory"); deliver.add_argument("--delivered-at", required=True)
    deliver.add_argument("--timezone", required=True); deliver.add_argument("--written-reference", required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.workspace, args.output, args.release_id, args.environment, args.artifact, args.allow_dirty_component, args.evidence, args.build_report)
        elif args.command == "verify":
            result = verify(args.directory)
        else:
            verify(args.directory)
            destination = Path(args.directory) / "delivery-receipt.json"
            if destination.exists():
                raise ValueError("A delivery is already recorded; preserve it rather than resetting the clock")
            result = delivery_receipt(Path(args.directory) / "release-manifest.json", args.delivered_at, args.timezone, args.written_reference)
            with destination.open("x") as stream:
                stream.write(json.dumps(result, indent=2) + "\n")
            with destination.with_suffix(".sha256").open("x") as stream:
                stream.write(sha256(destination) + "\n")
        summary = {"releaseId": result["releaseId"], "result": "OK", "command": args.command}
        receipt_path = Path(args.directory) / "delivery-receipt.json" if args.command != "prepare" else None
        if receipt_path and receipt_path.exists():
            receipt = json.loads(receipt_path.read_text())
            summary["acceptance"] = {key: receipt[key] for key in ("actualDeliveredAt", "calendarTimezone", "deadlineLocalDate", "state")}
        else:
            summary["acceptanceState"] = "NOT_DELIVERED"
        print(json.dumps(summary))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, tarfile.TarError, ZoneInfoNotFoundError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release preparation refused: {error}\n")


if __name__ == "__main__":
    main()
