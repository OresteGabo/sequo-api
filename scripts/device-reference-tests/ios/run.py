#!/usr/bin/env python3
"""Run real UI reference checks on one already-booted, explicitly approved local simulator.

Never builds, installs, seeds or resets the four product apps. Only the isolated XCTest
host/runner is built by xcodebuild; ordinary fixture login/logout occurs through app UI.
"""
import argparse,base64,hashlib,json,os,plistlib,re,subprocess,sys
from pathlib import Path

APPROVED_SIMULATOR='59D90F12-C368-449B-8752-6BD86241E8DB'
BASE='http://127.0.0.1:55840'
BUNDLES={
 'customer':'dev.orestegabo.sequo.Sequo.fixture',
 'vendor':'dev.orestegabo.sequovendor.SequoVendor.fixture',
 'rider':'dev.orestegabo.sequorider.SequoRider.fixture',
 'hub':'dev.orestegabo.sequohub.SequoHub.fixture',
}
ALPHABET='0123456789ABCDEFGHJKMNPQRSTVWXYZ'
def require(value,message):
 if not value:raise ValueError(message)
def valid(value,kind):
 if not isinstance(value,str) or not re.fullmatch(r'[0-9A-Z]{3}-[0-9A-Z]{3}-[0-9A-Z]{3}',value):return False
 symbols=value.replace('-','')
 if symbols[0]!=kind or any(c not in ALPHABET for c in symbols):return False
 word=0
 for c in symbols:word=word*32+ALPHABET.index(c)
 while word.bit_length()>10:word^=0x633 << (word.bit_length()-11)
 return word==0

def prepare(args):
 require(args.simulator==APPROVED_SIMULATOR,'Use only the explicitly approved task simulator.')
 root=args.artifact_root.resolve()
 sheet=json.loads(args.reference_sheet.read_text())
 require(sheet['environment']=='LOCAL_FIXTURE' and sheet['baseUrl']==BASE,'The sheet is not from the isolated local workspace.')
 require(sheet['checks'] and all(c['pass'] for c in sheet['checks']),'Resolve failed sheet checks before UI verification.')
 target=next(j for j in sheet['sharedJourneys'] if j['order']['id']=='SQ-local-ops-v1-merchant-preparing')
 refund=next(o for o in sheet['roles']['ama']['orders'] if o['id']=='SQ-local-ops-v1-return-refund')
 parcels=sorted(sheet['roles']['esso']['parcels'],key=lambda p:(not p['id'].endswith('-fresh'),p['id']))
 config=dict(enabled=True,simulatorUDID=args.simulator,environment='LOCAL_FIXTURE',apiBaseURL=BASE,releaseID=args.release_id,
  bundles=BUNDLES,customerOrderReference=target['order']['publicReference'],customerRefundOrderReference=refund['publicReference'],
  merchantPartReference=target['vendorParts'][0]['publicReference'],missionReference=target['riderMissions'][0]['publicReference'],
  ledgerReference=target['ledger'][0]['publicReference'],parcelReferences=[p['publicReference'] for p in parcels],
  hubReturnReference=args.hub_return_reference or '',hubTypoReference=args.hub_typo_reference or '',
  includeHubConfirmation=args.include_hub_confirmation)
 for field,kind in [('customerOrderReference','C'),('customerRefundOrderReference','C'),('merchantPartReference','M'),('missionReference','D'),('ledgerReference','W')]:require(valid(config[field],kind),'Invalid expected '+field)
 require(len(config['parcelReferences'])==3 and all(valid(p,'P') for p in config['parcelReferences']),'Expected the three real Esso parcel references.')
 if args.include_hub_confirmation:
  require(valid(args.hub_return_reference,'R'),'An explicitly supplied valid local return reference is required.')
  require(bool(args.hub_typo_reference) and not valid(args.hub_typo_reference,'R'),'Supply the intended invalid checksum example.')
 metadata={}
 for family,bundle in BUNDLES.items():
  data=json.loads((root/(family+'-ios')/'build-target.json').read_text())
  artifact=Path(data['artifact']).resolve()
  require(artifact.is_dir() and artifact.suffix=='.app' and artifact.is_relative_to(root),'App artifact must be within the approved artifact directory.')
  require(data['platform']=='ios' and data['environment']=='LOCAL_FIXTURE' and data['apiBaseUrl']==BASE and data['releaseId']==args.release_id,'Fixture artifact configuration mismatch: '+family)
  require(data['applicationId']==bundle,'Unexpected product bundle: '+family)
  info=plistlib.loads((artifact/'Info.plist').read_bytes())
  require(info['CFBundleIdentifier']==bundle,'Artifact Info.plist bundle mismatch: '+family)
  require(bool(data.get('debugDylibSha256')),'Identified debug payload hashes are required: '+family)
  for relative,digest in data['debugDylibSha256'].items():
   path=(artifact/relative).resolve();require(path.is_relative_to(artifact),'Invalid payload-relative path.')
   require(hashlib.sha256(path.read_bytes()).hexdigest()==digest,'Artifact payload changed: '+family)
  metadata[family]=data
 return config,metadata

def configure_xctestrun(path,encoded):
 """Patch only the generated XCTest runner's environment; preserve Xcode's paths/loader settings."""
 document=plistlib.loads(Path(path).read_bytes())
 targets=[]
 def visit(value):
  if isinstance(value,dict):
   if value.get('BlueprintName')=='ReferenceUITests' or str(value.get('TestBundlePath','')).endswith('/ReferenceUITests.xctest'):
    targets.append(value);return
   for key,child in value.items():
    if key=='ReferenceUITests' and isinstance(child,dict):targets.append(child)
    else:visit(child)
  elif isinstance(value,list):
   for child in value:visit(child)
 visit(document)
 require(len(targets)==1,'Expected exactly one generated ReferenceUITests target.')
 target=targets[0]
 for key in ['EnvironmentVariables','TestingEnvironmentVariables']:
  values=target.setdefault(key,{})
  require(isinstance(values,dict),'Unexpected generated test environment structure.')
  values['SEQUO_REFERENCE_CONFIG_BASE64']=encoded
 # __TESTROOT__ must remain the Build/Products directory: keep the configured plist beside the original.
 configured=Path(path).with_name('ReferenceDeviceTests-configured.xctestrun')
 configured.write_bytes(plistlib.dumps(document,fmt=plistlib.FMT_XML))
 return configured


def verify_summary(summary,expected):
 for key in ['totalTestCount','passedTests','failedTests','skippedTests']:
  require(type(summary.get(key)) is int,'Unrecognized xcresult summary: '+key)
 require(summary['totalTestCount']==expected and summary['passedTests']==expected,
  'The requested UI suite did not execute every expected test.')
 require(summary['failedTests']==0 and summary['skippedTests']==0,
  'UI verification requires zero failures and zero skipped tests.')


def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--simulator',required=True)
 p.add_argument('--artifact-root',type=Path,default=Path('/tmp/sequo-reference-devices'))
 p.add_argument('--reference-sheet',type=Path,default=Path('/tmp/sequo-device-reference-sheet.json'))
 p.add_argument('--release-id',required=True)
 p.add_argument('--output-dir',type=Path,required=True)
 p.add_argument('--only',choices=['customer','vendor','rider','hub'])
 p.add_argument('--include-hub-confirmation',action='store_true')
 p.add_argument('--hub-return-reference');p.add_argument('--hub-typo-reference')
 p.add_argument('--prepare-only',action='store_true',help='Validate local files and emit the plan without running any device or build command.')
 args=p.parse_args()
 if not args.prepare_only and args.only in (None,'hub') and not args.include_hub_confirmation:
  p.error('The Hub test requires --include-hub-confirmation plus explicit valid/typo references; it cannot silently omit that proof.')
 try:config,metadata=prepare(args)
 except (ValueError,KeyError,StopIteration,OSError) as e:p.error(str(e))
 output=args.output_dir.resolve();output.mkdir(parents=True,exist_ok=True)
 result=output/'ReferenceDeviceTests.xcresult'
 require(not result.exists(),'Use a new output directory; preserve existing test evidence.')
 (output/'reference-test-configuration.json').write_text(json.dumps(config,indent=2)+'\n')
 (output/'artifact-test-provenance.json').write_text(json.dumps({k:{f:d[f] for f in ['applicationId','releaseId','sourceHead','payloadSha256','debugDylibSha256']} for k,d in metadata.items()},indent=2)+'\n')
 if args.prepare_only:
  print('Local fixture configuration prepared; no device/build commands executed.');return
 devices=json.loads(subprocess.check_output(['xcrun','simctl','list','devices','available','-j'],text=True))
 require(any(d['udid']==args.simulator and d['state']=='Booted' for group in devices['devices'].values() for d in group),'The approved simulator must already be booted; this launcher never creates or boots a device.')
 for family,bundle in BUNDLES.items():
  installed=Path(subprocess.check_output(['xcrun','simctl','get_app_container',args.simulator,bundle,'app'],text=True).strip()).resolve()
  for relative,digest in metadata[family]['debugDylibSha256'].items():
   path=(installed/relative).resolve();require(path.is_relative_to(installed),'Invalid installed payload path.')
   require(hashlib.sha256(path.read_bytes()).hexdigest()==digest,'Installed app does not match identified payload: '+family)
 env=os.environ.copy()
 for key in list(env):
  if key.startswith(('XCODE_XCCONFIG_FILE','DYLD_','SIMCTL_CHILD_','SEQUO_REFERENCE_')):env.pop(key)
 encoded=base64.b64encode(json.dumps(config,separators=(',',':')).encode()).decode()
 methods={'customer':'test01CustomerStoredOrderReferences','vendor':'test02VendorPartAndParentOrderReferences','rider':'test03RiderMissionAndRecordedPayoutReferences','hub':'test04HubScopedParcelsHistoryAndCancelledReturn'}
 destination=['-destination','platform=iOS Simulator,id='+args.simulator]
 build=['xcodebuild','-project',str(Path(__file__).parent/'ReferenceDeviceTests.xcodeproj'),'-scheme','ReferenceDeviceTests',
  '-configuration','Debug','-sdk','iphonesimulator',*destination,
  '-derivedDataPath',str(output/'DerivedData'),'-disableAutomaticPackageResolution','-skipPackageUpdates',
  'CODE_SIGNING_ALLOWED=NO','build-for-testing']
 # Standard Xcode build produces the authoritative xctestrun file. A build setting alone is not a
 # test-process environment variable; inject the nonsecret fixture configuration in that official file.
 subprocess.run(build,env=env,check=True)
 generated=list((output/'DerivedData/Build/Products').glob('*.xctestrun'))
 require(len(generated)==1,'Expected one generated xctestrun after build-for-testing.')
 configured=configure_xctestrun(generated[0],encoded)
 command=['xcodebuild','-xctestrun',str(configured),*destination,'-resultBundlePath',str(result),
  '-parallel-testing-enabled','NO','-maximum-concurrent-test-simulator-destinations','1']
 if args.only:command+=['-only-testing:ReferenceUITests/ReferenceDeviceTests/'+methods[args.only]]
 else:command+=['-only-testing:ReferenceUITests/ReferenceDeviceTests']
 command+=['test-without-building']
 # No fabricated signing identity/entitlements. The simulator test host uses Xcode's standard unsigned build.
 completed=subprocess.run(command,env=env,check=False)
 require(result.exists(),'No xcresult was produced; no UI verification can be claimed.')
 summary=json.loads(subprocess.check_output(['xcrun','xcresulttool','get','test-results','summary','--path',str(result),'--compact'],text=True))
 (output/'test-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
 verify_summary(summary,1 if args.only else 4)
 completed.check_returncode()
 print('XCTest complete with zero failures/skips; screenshots and assertions: '+str(result))

if __name__=='__main__':main()
