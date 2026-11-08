import importlib.util
import json
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("release_candidate", Path(__file__).parents[1] / "release_candidate.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.manifest = self.base / "release-manifest.json"
        self.manifest.write_text(json.dumps({"releaseId": "fixture-rc"}))

    def receipt(self, timestamp, zone="Europe/Paris", reference="Written SEQUO testable-delivery fixture"):
        return release.delivery_receipt(self.manifest, timestamp, zone, reference,
            now=datetime(2027, 1, 1, tzinfo=timezone.utc))

    def test_calendar_window_crosses_dst_by_dates_rather_than_1200_hours(self):
        receipt = self.receipt("2026-10-10T23:30:00Z")
        self.assertEqual("2026-10-11", receipt["deliveryLocalDate"])
        self.assertEqual("2026-11-30", receipt["deadlineLocalDate"])
        self.assertEqual(release.sha256(self.manifest), receipt["manifestSha256"])
        self.assertNotEqual("ACCEPTED", receipt["state"])

    def test_calendar_window_handles_leap_day_and_year_end(self):
        self.assertEqual("2024-03-31", self.receipt("2024-02-10T12:00:00Z")["deadlineLocalDate"])
        self.assertEqual("2027-01-29", self.receipt("2026-12-10T12:00:00Z")["deadlineLocalDate"])

    def test_plans_missing_written_evidence_and_naive_times_cannot_start_window(self):
        for timestamp, reference in [("2028-01-01T00:00:00Z", "future plan"),
                                     ("2026-11-08T00:00:00", "written"),
                                     ("2026-11-08T00:00:00Z", " ")]:
            with self.assertRaises(ValueError):
                self.receipt(timestamp, reference=reference)

    def repositories(self):
        for name in release.COMPONENTS:
            repo = self.base / name
            repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            (repo / "source.txt").write_text("reviewed fixture")
            subprocess.run(["git", "-C", str(repo), "add", "source.txt"], check=True)
            subprocess.run(["git", "-C", str(repo), "-c", "user.name=Release fixture", "-c", "user.email=fixture@invalid", "commit", "-qm", "fixture"], check=True)

    def api_artifact(self, release_id="fixture-rc"):
        artifact = self.base / "api.jar"
        revision = release.git(self.base / "sequo-api", "rev-parse", "HEAD")
        with zipfile.ZipFile(artifact, "w") as archive:
            archive.writestr("BOOT-INF/classes/META-INF/build-info.properties", f"build.releaseId={release_id}\nbuild.revision={revision}\n")
        return artifact

    def test_bundle_hashes_actual_files_and_does_not_start_acceptance(self):
        self.repositories()
        artifact = self.api_artifact()
        output = self.base / "bundle"
        prepared = release.prepare(self.base, output, "fixture-rc", "LOCAL_FIXTURE", [f"sequo-api.jvm={artifact}"])
        self.assertEqual("NOT_DELIVERED", prepared["acceptance"]["state"])
        self.assertIsNone(prepared["acceptance"]["deliveredAt"])
        self.assertEqual("BLOCKED", prepared["paidLaunch"]["state"])
        self.assertEqual(prepared, release.verify(output))
        (output / prepared["files"][0]["path"]).write_bytes(b"changed binary")
        with self.assertRaises(ValueError):
            release.verify(output)

    def test_stale_artifact_cannot_claim_current_release_identity(self):
        self.repositories()
        artifact = self.api_artifact("another-release")
        with self.assertRaises(ValueError):
            release.prepare(self.base, self.base / "bundle", "fixture-rc", "LOCAL_FIXTURE", [f"sequo-api.jvm={artifact}"])
        self.assertFalse((self.base / "bundle").exists())

    def test_manifest_and_receipt_cannot_escape_complete_checksum_and_calendar_validation(self):
        self.repositories()
        artifact = self.api_artifact()
        output = self.base / "bundle"
        release.prepare(self.base, output, "fixture-rc", "LOCAL_FIXTURE", [f"sequo-api.jvm={artifact}"])
        checksums = (output / "SHA256SUMS").read_text()
        for truncated in ("", checksums.splitlines()[0] + "\n"):
            (output / "SHA256SUMS").write_text(truncated)
            with self.assertRaises(ValueError):
                release.verify(output)
        (output / "SHA256SUMS").write_text(checksums)
        receipt = release.delivery_receipt(output / "release-manifest.json", "2024-02-10T12:00:00Z", "Europe/Paris", "Written actual-delivery fixture")
        release.write_json(output / "delivery-receipt.json", receipt)
        (output / "delivery-receipt.sha256").write_text(release.sha256(output / "delivery-receipt.json"))
        release.verify(output)
        for key, value in (("manifestSha256", "wrong-manifest"), ("deadlineLocalDate", "2024-03-30")):
            changed = dict(receipt, **{key: value})
            release.write_json(output / "delivery-receipt.json", changed)
            (output / "delivery-receipt.sha256").write_text(release.sha256(output / "delivery-receipt.json"))
            with self.assertRaises(ValueError):
                release.verify(output)

    def test_mobile_build_report_binds_exact_artifact_hash_and_source_revision(self):
        self.repositories()
        artifact = self.base / "app.apk"
        artifact.write_bytes(b"fixture mobile binary")
        report = self.base / "build-target.json"
        data = {"app": "Sequo", "sha256": release.sha256(artifact), "releaseId": "fixture-rc", "environment": "LOCAL_FIXTURE",
                "sourceHead": release.git(self.base / "Sequo", "rev-parse", "HEAD")}
        release.write_json(report, data)
        output = self.base / "bundle"
        release.prepare(self.base, output, "fixture-rc", "LOCAL_FIXTURE", [f"Sequo.android={artifact}"], build_reports=[f"Sequo.android={report}"])
        self.assertEqual("HASH_BOUND_BUILD_REPORT", release.verify(output)["files"][0]["buildIdentity"]["verification"])
        data["sha256"] = "another-artifact"
        release.write_json(report, data)
        with self.assertRaises(ValueError):
            release.prepare(self.base, self.base / "refused", "fixture-rc", "LOCAL_FIXTURE", [f"Sequo.android={artifact}"], build_reports=[f"Sequo.android={report}"])

    def test_dirty_sources_need_explicit_review_and_paid_production_is_refused(self):
        self.repositories()
        (self.base / "Sequo" / "source.txt").write_text("existing user change")
        artifact = self.base / "app.apk"
        artifact.write_bytes(b"fixture binary")
        with self.assertRaises(ValueError):
            release.prepare(self.base, self.base / "refused", "fixture-rc", "LOCAL_FIXTURE", [f"Sequo.android={artifact}"])
        self.assertFalse((self.base / "refused").exists())
        result = release.prepare(self.base, self.base / "acknowledged", "fixture-rc", "LOCAL_FIXTURE", [f"Sequo.android={artifact}"], ["Sequo"])
        self.assertEqual(release.sha256(self.base / "Sequo/source.txt"), result["sources"]["Sequo"]["trackedChanges"][0]["sha256"])
        with self.assertRaises(ValueError):
            release.prepare(self.base, self.base / "prod", "fixture-rc", "PRODUCTION", [f"Sequo.android={artifact}"], ["Sequo"])


if __name__ == "__main__":
    unittest.main()
