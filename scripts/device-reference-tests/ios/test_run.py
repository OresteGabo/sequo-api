"""Pure filesystem/configuration regressions; no Xcode, simulator, login or API calls."""
import hashlib,importlib.util,json,plistlib,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('reference_ui_runner',Path(__file__).with_name('run.py'))
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

class FixtureLauncherTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
  self.sheet=self.root/'sheet.json'
  self.sheet.write_text(json.dumps(dict(environment='LOCAL_FIXTURE',baseUrl=runner.BASE,checks=[{'pass':True}],
   sharedJourneys=[dict(order=dict(id='SQ-local-ops-v1-merchant-preparing',publicReference='C96-BBG-0DY'),vendorParts=[dict(publicReference='MNS-BX2-A3H')],riderMissions=[dict(publicReference='DA3-KTY-93W')],ledger=[dict(publicReference='WWK-H3T-QGD')])],
   roles=dict(ama=dict(orders=[dict(id='SQ-local-ops-v1-return-refund',publicReference='C2J-8SP-V0R')]),esso=dict(parcels=[dict(id='local-ops-v1-parcel-fresh',publicReference='PC4-AWP-0WK'),dict(id='local-ops-v1-parcel-after-eight-days',publicReference='PP3-Z4S-J9N'),dict(id='local-ops-v1-parcel-after-thirty-days',publicReference='PRM-E2K-MZ8')])))))
  for family,bundle in runner.BUNDLES.items():
   folder=self.root/(family+'-ios');app=folder/'Fixture.app';app.mkdir(parents=True)
   (app/'Info.plist').write_bytes(plistlib.dumps({'CFBundleIdentifier':bundle}))
   (app/'Fixture.debug.dylib').write_bytes(b'synthetic-payload-not-executable')
   (folder/'build-target.json').write_text(json.dumps(dict(artifact=str(app),platform='ios',environment='LOCAL_FIXTURE',apiBaseUrl=runner.BASE,releaseId='test-release',applicationId=bundle,debugDylibSha256={'Fixture.debug.dylib':hashlib.sha256((app/'Fixture.debug.dylib').read_bytes()).hexdigest()})))
  self.args=SimpleNamespace(simulator=runner.APPROVED_SIMULATOR,artifact_root=self.root,reference_sheet=self.sheet,release_id='test-release',include_hub_confirmation=False,hub_return_reference=None,hub_typo_reference=None)
 def mutate_metadata(self,family,**values):
  path=self.root/(family+'-ios')/'build-target.json';data=json.loads(path.read_text());data.update(values);path.write_text(json.dumps(data))
 def test_valid_plan_is_pure_and_retains_server_references(self):
  with patch.object(runner.subprocess,'check_output',side_effect=AssertionError('No device command allowed during preparation')):
   config,metadata=runner.prepare(self.args)
  self.assertEqual(config['customerOrderReference'],'C96-BBG-0DY');self.assertEqual(config['missionReference'],'DA3-KTY-93W');self.assertEqual(len(metadata),4)
 def test_foreign_simulator_is_rejected(self):
  self.args.simulator='unapproved-simulator'
  with self.assertRaisesRegex(ValueError,'approved task simulator'):runner.prepare(self.args)
 def test_staging_or_nonlocal_api_artifact_is_rejected(self):
  self.mutate_metadata('vendor',environment='STAGING',apiBaseUrl='https://staging.example.invalid')
  with self.assertRaisesRegex(ValueError,'configuration mismatch'):runner.prepare(self.args)
 def test_production_bundle_is_rejected(self):
  self.mutate_metadata('hub',applicationId='dev.orestegabo.sequohub.SequoHub')
  with self.assertRaisesRegex(ValueError,'Unexpected product bundle'):runner.prepare(self.args)
 def test_changed_identified_payload_is_rejected(self):
  (self.root/'rider-ios'/'Fixture.app'/'Fixture.debug.dylib').write_bytes(b'changed')
  with self.assertRaisesRegex(ValueError,'payload changed'):runner.prepare(self.args)
 def test_failed_reference_sheet_is_rejected(self):
  data=json.loads(self.sheet.read_text());data['checks'][0]['pass']=False;self.sheet.write_text(json.dumps(data))
  with self.assertRaisesRegex(ValueError,'failed sheet checks'):runner.prepare(self.args)
 def test_opt_in_hub_case_requires_valid_reference_and_invalid_typo(self):
  self.args.include_hub_confirmation=True;self.args.hub_return_reference='R2D-W22-815';self.args.hub_typo_reference='R2D-W22-814'
  with self.assertRaisesRegex(ValueError,'valid local return'):runner.prepare(self.args)
  self.args.hub_return_reference='R2D-W22-814';self.args.hub_typo_reference='R2D-W22-815'
  config,_=runner.prepare(self.args);self.assertTrue(config['includeHubConfirmation'])
 def test_payload_path_cannot_escape_artifact(self):
  self.mutate_metadata('customer',debugDylibSha256={'../../outside.dylib':'a'*64})
  with self.assertRaisesRegex(ValueError,'payload-relative path'):runner.prepare(self.args)

class XcTestRunConfigurationTests(unittest.TestCase):
 def check_format(self,document):
  with tempfile.TemporaryDirectory() as directory:
   original=Path(directory)/'Generated.xctestrun';original.write_bytes(plistlib.dumps(document))
   configured=runner.configure_xctestrun(original,'synthetic-public-configuration')
   self.assertEqual(configured.parent,original.parent)
   self.assertEqual(plistlib.loads(original.read_bytes()),document)
   patched=plistlib.loads(configured.read_bytes())
   target=patched.get('ReferenceUITests') or patched['TestConfigurations'][0]['TestTargets'][0]
   self.assertEqual(target['EnvironmentVariables']['SEQUO_REFERENCE_CONFIG_BASE64'],'synthetic-public-configuration')
   self.assertEqual(target['TestingEnvironmentVariables']['DYLD_LIBRARY_PATH'],'__TESTROOT__/Frameworks')
   self.assertEqual(target['TestingEnvironmentVariables']['SEQUO_REFERENCE_CONFIG_BASE64'],'synthetic-public-configuration')
 def test_generated_version_one_is_configured_without_replacing_loader_environment(self):
  self.check_format({'ReferenceUITests':dict(TestBundlePath='__TESTROOT__/Runner.app/PlugIns/ReferenceUITests.xctest',TestingEnvironmentVariables={'DYLD_LIBRARY_PATH':'__TESTROOT__/Frameworks'})})
 def test_generated_version_two_is_configured_without_moving_testroot(self):
  self.check_format({'__xctestrun_metadata__':{'FormatVersion':2},'TestConfigurations':[{'TestTargets':[dict(BlueprintName='ReferenceUITests',TestBundlePath='__TESTROOT__/Runner.app/PlugIns/ReferenceUITests.xctest',TestingEnvironmentVariables={'DYLD_LIBRARY_PATH':'__TESTROOT__/Frameworks'})]}]})

class XcresultSummaryTests(unittest.TestCase):
 def test_exact_pass_is_accepted(self):
  runner.verify_summary(dict(totalTestCount=4,passedTests=4,failedTests=0,skippedTests=0),4)
 def test_empty_suite_is_rejected(self):
  with self.assertRaises(ValueError):runner.verify_summary(dict(totalTestCount=0,passedTests=0,failedTests=0,skippedTests=0),4)
 def test_skipped_test_is_rejected(self):
  with self.assertRaises(ValueError):runner.verify_summary(dict(totalTestCount=4,passedTests=3,failedTests=0,skippedTests=1),4)
 def test_unrecognized_summary_fails_closed(self):
  with self.assertRaises(ValueError):runner.verify_summary(dict(total=4),4)

if __name__=='__main__':unittest.main()
