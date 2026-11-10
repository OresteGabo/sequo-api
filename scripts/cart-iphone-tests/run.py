"""Run reviewed actions on an explicitly authorized physical iPhone; keep artifacts private."""
import argparse
import base64
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--authorize-physical-ui", action="store_true", required=True)
parser.add_argument("--device", required=True)
parser.add_argument("--products", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--actions", type=Path, required=True)
parser.add_argument("--app", choices=["dev.orestegabo.sequo.Sequo", "dev.orestegabo.sequovendor.SequoVendor"], default="dev.orestegabo.sequo.Sequo")
args = parser.parse_args()
os.umask(0o077)
args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
products = args.products.resolve()
base = next(products.glob("CartPhoneTests_iphoneos*.xctestrun"))
config = plistlib.loads(base.read_bytes())
actions = json.loads(args.actions.read_text())
if not isinstance(actions, list):
    parser.error("Actions must be a reviewed JSON array")
targets = list(config.values())
for configuration in config.get("TestConfigurations", []):
    targets.extend(configuration.get("TestTargets", []))
configured = False
for target in targets:
    if isinstance(target, dict) and target.get("IsUITestBundle"):
        environment = target.setdefault("EnvironmentVariables", {})
        environment["SEQUO_PHONE_APP"] = args.app
        environment["SEQUO_CART_PHONE_CONFIG"] = "inspect-authorized-20261110"
        environment["SEQUO_CART_PHONE_ACTIONS"] = base64.b64encode(json.dumps(actions).encode()).decode()
        configured = True
if not configured:
    parser.error("No UI-test target found in built runfile")
# Keep __TESTROOT__ relative paths beside the original build products.
runfile = products / ("private-cart-" + args.output.name + ".xctestrun")
if runfile.exists():
    parser.error("Private runfile already exists; choose a different output name")
runfile.write_bytes(plistlib.dumps(config))
runfile.chmod(0o600)
result_path = args.output.resolve() / "result.xcresult"
try:
    with (args.output / "private.log").open("w") as log:
        result = subprocess.run(["xcodebuild", "-xctestrun", str(runfile), "-destination",
            "platform=iOS,id=" + args.device, "-resultBundlePath", str(result_path),
            "-parallel-testing-enabled", "NO", "-only-testing:CartPhoneUITests/CartPhoneUiTests/testAuthorizedCartActions",
            "test-without-building"], stdout=log, stderr=subprocess.STDOUT)
    summary = subprocess.run(["xcrun", "xcresulttool", "get", "test-results", "summary",
        "--path", str(result_path), "--compact"], capture_output=True, text=True)
    if summary.returncode == 0:
        data = json.loads(summary.stdout)
        (args.output / "private-summary.json").write_text(json.dumps(data, indent=2))
        print(json.dumps({key: data.get(key) for key in ["result", "passedTests", "failedTests", "skippedTests"]}))
    sys.exit(result.returncode)
finally:
    runfile.unlink(missing_ok=True)
