#!/usr/bin/env python3
"""Remove obsolete SEQUO branding after checking references and file hashes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
MOBILE = ('Sequo','SequoVendor','SequoHub','SequoRider')
PLAN = ROOT / 'build/brand-cleanup/plan.json'
LEGACY = {
    'sequo_relay_icon','sequo_primary_light','sequo_hub_app_icon',
    'sequo_icon_green','sequo_rider_icon','sequo_primary_dark',
    'sequohub_logo_full','sequohub_logo_mark','sequohub_logo_text',
    'rider_brand_icon','sequo_vendor_app_icon','sequo_app_icon','sequo_shopping_icon',
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def prepare():
    deletes=set(); edits=[]
    for relative in ('docs/branding/archive','docs/branding/alternatives','build/branding-rollout/backup',
                     'build/branding-rollout/customer-catalog-review'):
        folder=ROOT/relative
        if folder.exists():
            deletes.update(p for p in folder.rglob('*') if p.is_file())
    for relative in ('scripts/sequo-logo-alternatives.py','docs/branding/previews/box-refinement.svg',
                     'docs/branding/previews/box-refinement.png'):
        file=ROOT/relative
        if file.exists(): deletes.add(file)
    canonical={p.name for p in (ROOT/'docs/branding/platform-assets').glob('sequo-*.svg')}
    for repo in MOBILE:
        base=WORKSPACE/repo
        kotlin=list((base/'shared/src').rglob('*.kt'))
        cleaned=[]
        for file in kotlin:
            source=file.read_text()
            new=re.sub(r'^import [\w.]+\.generated\.resources\.('+'|'.join(sorted(LEGACY))+r')\s*\n','',source,flags=re.MULTILINE)
            if new!=source:
                edits.append({'path':str(file.relative_to(WORKSPACE)),'previous_sha256':sha(file.read_bytes()),'content':new})
            cleaned.append(new)
        actual_code='\n'.join(line for source in cleaned for line in source.splitlines() if not line.startswith('import '))
        resources=base/'shared/src/commonMain/composeResources'
        for file in (resources/'drawable').glob('*'):
            if file.is_file() and file.stem in LEGACY:
                assert not re.search(r'\b'+re.escape(file.stem)+r'\b',actual_code),f'Still used: {file}'
                deletes.add(file)
        logos=resources/'files/brand/logos'
        for file in logos.rglob('*'):
            if file.is_file() and (file.parent!=logos or file.name not in canonical):
                assert file.name not in actual_code,f'File resource still used: {file}'
                deletes.add(file)
        android=base/'androidApp/src/main/res'
        candidates={'ic_launcher_foreground.xml','sequo_launcher_foreground.png',
                    'ic_launcher_foreground_adaptive.png','ic_launcher_foreground.png','sequo_vendor_app_icon.xml'}
        for file in android.rglob('*'):
            if file.is_file() and file.name in candidates:
                refs='\n'.join(p.read_text() for p in android.rglob('*.xml') if p!=file)
                assert not re.search(r'@(?:drawable|mipmap)/'+re.escape(file.stem)+r'\b',refs),f'Android resource still used: {file}'
                deletes.add(file)
    for repo,relative in [('sequo-admin','public/brand/sequo-icon-green.png'),
                          ('sequo-services','public/images/sequo-logo.png'),('sequo-services','public/images/sequo-icon.png')]:
        file=WORKSPACE/repo/relative
        if file.exists():
            source='\n'.join(p.read_text() for p in (WORKSPACE/repo/'src').rglob('*') if p.suffix in ('.vue','.ts','.css','.html'))
            assert '/'+str(Path(relative).relative_to('public')) not in source,f'Web asset still used: {file}'
            deletes.add(file)
    provenance=WORKSPACE/'sequo-services/public/images/PROVENANCE.md'
    if provenance.exists():
        source=provenance.read_text()
        header='## Existing brand assets'
        if header in source:
            source=source.split(header)[0]+'''## Current brand assets

The current folded-S parcel identity is served from `public/brand/`, generated
from `sequo-api/docs/branding/platform-assets/`. The old logo PNG copies have
been removed. Provider logos and the commerce hero image are unchanged.
'''
            edits.append({'path':str(provenance.relative_to(WORKSPACE)),'previous_sha256':sha(provenance.read_bytes()),'content':source})
    data={'delete':[{'path':str(p.relative_to(WORKSPACE)),'sha256':sha(p.read_bytes())} for p in sorted(deletes)],'edit':edits}
    PLAN.parent.mkdir(parents=True,exist_ok=True); PLAN.write_text(json.dumps(data,indent=2)+'\n')
    print(f'Prepared removal of {len(deletes)} obsolete branding files and {len(edits)} import/documentation updates.')


def apply():
    data=json.loads(PLAN.read_text())
    for item in data['delete']:
        file=WORKSPACE/item['path']; assert file.is_file() and sha(file.read_bytes())==item['sha256'],f'Changed since preparation: {file}'
    for item in data['edit']:
        file=WORKSPACE/item['path']; assert sha(file.read_bytes())==item['previous_sha256'],f'Changed since preparation: {file}'
    for item in data['edit']:
        (WORKSPACE/item['path']).write_text(item['content'])
    for item in data['delete']:
        (WORKSPACE/item['path']).unlink()
    # Remove only empty folders under the explicitly retired directories.
    for relative in ('docs/branding/archive','docs/branding/alternatives','build/branding-rollout/backup',
                     'build/branding-rollout/customer-catalog-review'):
        folder=ROOT/relative
        if folder.exists():
            for directory in sorted((p for p in folder.rglob('*') if p.is_dir()),key=lambda p:len(p.parts),reverse=True):
                directory.rmdir()
            folder.rmdir()
    manifest=ROOT/'docs/branding/installation.json'
    records=json.loads(manifest.read_text())
    records=[r for r in records if (WORKSPACE/r['repo']/r['path']).exists()]
    for record in records:
        record['sha256']=sha((WORKSPACE/record['repo']/record['path']).read_bytes())
    manifest.write_text(json.dumps(records,indent=2)+'\n')
    print(f'Removed {len(data["delete"])} obsolete branding files; current resources retained.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    apply() if args.apply else prepare()
