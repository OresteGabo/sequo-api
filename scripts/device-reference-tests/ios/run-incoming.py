#!/usr/bin/env python3
"""Explicit installed Vendor incoming scene UI test, prepared before the fresh server scene exists.
No product build/install, HTTP, fixture creation, payment/reset, credential injection or simulator boot.
"""
import argparse,base64,hashlib,importlib.util,json,os,plistlib,subprocess
from pathlib import Path

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('reference_runner',HERE/'run.py')
shared=importlib.util.module_from_spec(spec);spec.loader.exec_module(shared)
ROOT=Path('/tmp/sequo-vendor-timing-60-15-devices').resolve()
HEAD='79ed43ce097e92acfe5f4f0e69737e71cff72c74'
RELEASE='sequo-vendor-incoming-20261109-02'
BUNDLE=shared.BUNDLES['vendor']
METHOD='ReferenceUITests/VendorIncomingOrderUiTests/testIncomingOrderUsesExistingMerchantAction'
INSPECT_METHOD='ReferenceUITests/VendorIncomingOrderUiTests/testExistingWaitingQueueIsReadable'
SOURCES=['ReferenceUITests/VendorIncomingOrderUiTests.swift','ReferenceUITests/ReferenceDeviceTests.swift',
 'ReferenceTestHost/AppDelegate.swift','ReferenceDeviceTests.xcodeproj/project.pbxproj',
 'ReferenceDeviceTests.xcodeproj/xcshareddata/xcschemes/ReferenceDeviceTests.xcscheme','run.py','run-incoming.py']
require=shared.require

def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def source_hashes():return {name:digest(HERE/name) for name in SOURCES}
def payload(path):
 records=[]
 for item in sorted(path.rglob('*'),key=lambda p:p.relative_to(path).as_posix()):
  relative=item.relative_to(path).as_posix()
  if item.is_symlink():
   require(item.resolve().is_relative_to(path),'Payload symlink escaped the identified app.')
   records.append([relative,'symlink',os.readlink(item)])
  elif item.is_file():records.append([relative,'file',digest(item)])
 return hashlib.sha256(json.dumps(records,ensure_ascii=True,separators=(',',':')).encode()).hexdigest()

def artifact():
 metadata=json.loads((ROOT/'vendor-ios/build-target.json').read_text())
 app=Path(metadata['artifact']).resolve()
 require(app.is_dir() and app.suffix=='.app' and app.is_relative_to(ROOT),'Identified Vendor app must remain in the approved artifact root.')
 require(metadata['app']=='SequoVendor' and metadata['platform']=='ios' and metadata['environment']=='LOCAL_FIXTURE' and
  metadata['apiBaseUrl']==shared.BASE and metadata['releaseId']==RELEASE and metadata['applicationId']==BUNDLE and metadata['sourceHead']==HEAD,
  'Only the approved 79ed43c Vendor LOCAL_FIXTURE artifact is supported.')
 require(plistlib.loads((app/'Info.plist').read_bytes())['CFBundleIdentifier']==BUNDLE,'Artifact bundle mismatch.')
 require(metadata.get('debugDylibSha256') and payload(app)==metadata['payloadSha256'],'Identified full app payload changed.')
 for relative,sha in metadata['debugDylibSha256'].items():
  path=(app/relative).resolve();require(path.is_relative_to(app) and digest(path)==sha,'Identified debug payload changed.')
 return metadata

def environment():
 result=os.environ.copy()
 for key in list(result):
  if key.startswith(('XCODE_XCCONFIG_FILE','DYLD_','SIMCTL_CHILD_','SEQUO_REFERENCE_')):result.pop(key)
 return result

def main():
 p=argparse.ArgumentParser(description=__doc__)
 mode=p.add_mutually_exclusive_group(required=True)
 mode.add_argument('--prepare-test-bundle',action='store_true',help='Compile isolated XCTest only, before creating the fresh scene; never run/install the product.')
 mode.add_argument('--run-prepared',action='store_true',help='Run exactly one action case using an unchanged prepared test bundle.')
 p.add_argument('--simulator',required=True);p.add_argument('--output-dir',type=Path,required=True)
 p.add_argument('--incoming-reference');p.add_argument('--incoming-action',choices=['accept','refuse','wait','inspect-waiting'])
 p.add_argument('--incoming-amount-cfa',type=int)
 args=p.parse_args()
 require(args.simulator==shared.APPROVED_SIMULATOR,'Only the approved simulator UDID is allowed.')
 metadata=artifact();output=args.output_dir.resolve()
 require(output.is_relative_to(ROOT) and output!=ROOT,'Use a dedicated output directory inside the approved artifact root.')
 supplied=(args.incoming_reference,args.incoming_action,args.incoming_amount_cfa)
 destination=['-destination','platform=iOS Simulator,id='+args.simulator]
 env=environment();manifest=output/'prepared-incoming-test.json'
 if args.prepare_test_bundle:
  require(all(v is None for v in supplied),'Prepare first without a scene; create the fresh server scene only after build-for-testing finishes.')
  require(not output.exists(),'Use a new output directory; existing prepared/results evidence is retained.')
  output.mkdir(parents=True)
  command=['xcodebuild','-project',str(HERE/'ReferenceDeviceTests.xcodeproj'),'-scheme','ReferenceDeviceTests','-configuration','Debug',
   '-sdk','iphonesimulator',*destination,'-derivedDataPath',str(output/'DerivedData'),'-disableAutomaticPackageResolution','-skipPackageUpdates',
   'CODE_SIGNING_ALLOWED=NO','build-for-testing']
  subprocess.run(command,env=env,check=True)
  generated=list((output/'DerivedData/Build/Products').glob('*.xctestrun'))
  require(len(generated)==1,'Expected one authoritative Xcode xctestrun.')
  manifest.write_text(json.dumps(dict(protocol='SEQUO_VENDOR_INCOMING_UI_V1',simulator=args.simulator,sourceHead=HEAD,
   payloadSha256=metadata['payloadSha256'],sourceHashes=source_hashes(),xctestrun=str(generated[0]),xctestrunSha256=digest(generated[0])),indent=2)+'\n')
  print('Isolated test bundle prepared; no product was installed/launched and no scene created.');return
 require(shared.valid(args.incoming_reference,'M'),'Pass the exact fresh server M reference with a valid checksum.')
 require(args.incoming_action in ['accept','refuse','wait','inspect-waiting'] and args.incoming_amount_cfa is not None and 1<=args.incoming_amount_cfa<=1_000_000,
  'An explicit action and actual bounded server item subtotal are required.')
 plan=json.loads(manifest.read_text())
 require(plan['protocol']=='SEQUO_VENDOR_INCOMING_UI_V1' and plan['simulator']==args.simulator and plan['sourceHead']==HEAD and
  plan['payloadSha256']==metadata['payloadSha256'] and plan['sourceHashes']==source_hashes(),'Prepared bundle provenance changed; prepare a new bundle before creating a new scene.')
 generated=Path(plan['xctestrun']).resolve()
 require(generated.is_relative_to(output/'DerivedData/Build/Products') and digest(generated)==plan['xctestrunSha256'],'Prepared xctestrun changed.')
 devices=json.loads(subprocess.check_output(['xcrun','simctl','list','devices','available','-j'],text=True))
 require(any(d['udid']==args.simulator and d['state']=='Booted' for group in devices['devices'].values() for d in group),'Approved simulator must already be booted.')
 installed=Path(subprocess.check_output(['xcrun','simctl','get_app_container',args.simulator,BUNDLE,'app'],text=True).strip()).resolve()
 require(plistlib.loads((installed/'Info.plist').read_bytes())['CFBundleIdentifier']==BUNDLE and payload(installed)==metadata['payloadSha256'],
  'Installed Vendor full payload does not match the identified approved artifact.')
 case=output/('case-'+args.incoming_action+'-'+args.incoming_reference)
 require(not case.exists(),'Preserve existing case evidence; use a fresh scene/case.')
 case.mkdir()
 config=dict(enabled=True,environment='LOCAL_FIXTURE',simulatorUDID=args.simulator,apiBaseURL=shared.BASE,releaseID=RELEASE,bundle=BUNDLE,
  sourceHead=HEAD,payloadSha256=metadata['payloadSha256'],action=args.incoming_action,expectedReference=args.incoming_reference,
  expectedAmountCfa=args.incoming_amount_cfa,expectedQuantity=2)
 (case/'incoming-configuration.json').write_text(json.dumps(config,indent=2)+'\n')
 encoded=base64.b64encode(json.dumps(config,separators=(',',':')).encode()).decode()
 configured=shared.configure_xctestrun(generated,encoded)
 result=case/'VendorIncomingOrder.xcresult'
 command=['xcodebuild','-xctestrun',str(configured),*destination,'-resultBundlePath',str(result),
  '-parallel-testing-enabled','NO','-maximum-concurrent-test-simulator-destinations','1','-only-testing:'+(INSPECT_METHOD if args.incoming_action=='inspect-waiting' else METHOD),'test-without-building']
 completed=subprocess.run(command,env=env,check=False)
 require(result.exists(),'No xcresult; do not claim UI verification.')
 summary=json.loads(subprocess.check_output(['xcrun','xcresulttool','get','test-results','summary','--path',str(result),'--compact'],text=True))
 (case/'test-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
 shared.verify_summary(summary,1);completed.check_returncode()
 print('Exactly one installed Vendor UI case passed, zero failures/skips: '+str(result))

if __name__=='__main__':main()
