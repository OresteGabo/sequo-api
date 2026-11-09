#!/usr/bin/env python3
"""Prepare, validate and install the folded-box brand across sibling projects.

Run with /opt/homebrew/bin/python3 (Pillow) and librsvg installed.
Preparation writes only inside sequo-api. --apply checks original file hashes
before installing the prepared files into the seven sibling projects.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
STAGE = ROOT / 'build/branding-rollout'
EXPORT = ROOT / 'docs/branding/platform-assets'
BRAND = runpy.run_path(str(ROOT / 'scripts/sequo-brand.py'))
MOBILE = {'Sequo': ('sequo', ''), 'SequoVendor': ('sequovendor', 'V'),
          'SequoHub': ('sequohub', 'H'), 'SequoRider': ('sequorider', 'R')}
WEB = ('sequo-admin', 'sequo-developers', 'sequo-services')
NS = '{http://www.w3.org/2000/svg}'
plans = []


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_text(repo, relative):
    return (WORKSPACE / repo / relative).read_text()


def stage(repo, relative, data):
    if isinstance(data, str):
        data = data.encode()
    previous = next((p for p in plans if p['repo']==repo and p['path']==str(relative)), None)
    if previous:
        assert previous['sha256']==digest(data), f'Conflicting generated file: {repo}/{relative}'
        return
    original = WORKSPACE / repo / relative
    target = STAGE / 'files' / repo / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    plans.append({'repo': repo, 'path': str(relative), 'sha256': digest(data),
                  'previous_sha256': digest(original.read_bytes()) if original.exists() else None})


def patch(repo, relative, replacements):
    source = source_text(repo,relative)
    for before, after in replacements:
        if after in source:
            continue
        if before not in source:
            raise ValueError(f'Expected source missing: {repo}/{relative}: {before[:90]}')
        source = source.replace(before, after)
    stage(repo, relative, source)


def svg(body, viewbox, label):
    return BRAND['svg'](body, viewbox, label)


def themed(body, dark=False):
    if not dark:
        return body
    return body.replace('#7AD8A9', '#CDEFD9').replace('#0B6B46', '#7AD8A9').replace('#087F4F', '#65D99D')


def wordmark(color):
    return ''.join(f'<g transform="translate({x} 0)">{BRAND["path"](d, color)}</g>' for x, d in BRAND['WORDMARK'])


def badge(role):
    if not role:
        return ''
    shapes = {
        'V': 'M-24 -24 H-10 L0 9 L10 -24 H24 L7 25 H-7 Z',
        'H': 'M-23 -24 H-9 V-7 H9 V-24 H23 V24 H9 V7 H-9 V24 H-23 Z',
        'R': 'M-21 24 V-24 H5 Q24 -24 24 -7 Q24 4 13 8 L26 24 H8 L-5 8 H-7 V24 Z M-7 -12 V-4 H4 Q10 -4 10 -8 Q10 -12 4 -12 Z',
    }
    return ('<g transform="translate(370 380)"><circle r="49" fill="#F7F8F4"/>'
            '<circle r="43" fill="#002C1C"/>' + BRAND['path'](shapes[role], '#FFFFFF') + '</g>')


def icon(role='', dark=False, tinted=False, rounded=False, adaptive=False):
    body = BRAND['symbol']()
    if tinted:
        body = BRAND['symbol'](True).replace('#FFFFFF', '#002C1C')
    else:
        body = themed(body, dark)
    artwork = f'<g transform="translate(0 -8)">{body}</g>' + badge(role)
    if adaptive:
        return svg('<g transform="translate(256 256) scale(.68) translate(-256 -256)">' + artwork + '</g>', '0 0 512 512', 'SEQUO adaptive foreground')
    background = '#0B1110' if dark else '#F7F8F4'
    return svg(f'<rect width="512" height="512" rx="{112 if rounded else 0}" fill="{background}"/>' + artwork,
               '0 0 512 512', 'SEQUO' + (f' {role}' if role else '') + ' app icon')


def render(source, target, width):
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['rsvg-convert', '--width', str(width), '--output', str(target), str(source)], check=True)


def export_svg(name, content, width=None):
    target = EXPORT / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)
    ET.parse(target)
    if width:
        render(target, target.with_suffix('.png'), width)
    return target


def flatten(d, a=1, b=0, c=0, e=0, f=0):
    """Flatten the master's explicit M/L/H/V/Q/Z front contour for Android."""
    tokens = re.findall(r'[MLHVQZ]|-?\d*\.?\d+', d)
    out, i, x, y = [], 0, 0., 0.
    def xy(px, py):
        return f'{a*px+c*py+e:g},{b*px+py+f:g}'
    while i < len(tokens):
        cmd = tokens[i]; i += 1
        if cmd == 'Z':
            out.append('Z'); continue
        count = {'M':2, 'L':2, 'H':1, 'V':1, 'Q':4}[cmd]
        values = list(map(float, tokens[i:i+count])); i += count
        if cmd in ('M', 'L'):
            x,y = values; out.append(cmd + xy(x,y))
        elif cmd == 'H':
            x=values[0]; out.append('L'+xy(x,y))
        elif cmd == 'V':
            y=values[0]; out.append('L'+xy(x,y))
        else:
            qx,qy,x,y=values; out.append('Q'+xy(qx,qy)+' '+xy(x,y))
    return ' '.join(out)


def android_vector(role='', adaptive=True, mono=False):
    paths = [(BRAND['TOP'], BRAND['MINT']), (BRAND['SIDE'], BRAND['SECONDARY']),
             (flatten(BRAND['FRONT'], b=.25, e=72, f=170), BRAND['PRIMARY'])]
    shapes = ''.join(f'<path android:fillColor="{("#FFFFFF" if mono else color)}" android:pathData="{d}"/>' for d,color in paths)
    if role and not mono:
        # Reuse path-only SVG badge as a vector circle and outlined letter.
        node=ET.fromstring('<svg xmlns="http://www.w3.org/2000/svg">'+badge(role)+'</svg>')[0]
        shapes += '<group android:translateX="370" android:translateY="388">'
        for child in node:
            if child.tag.endswith('circle'):
                radius=float(child.attrib['r'])
                d=f'M{-radius},0 A{radius},{radius} 0,1 0,{radius},0 A{radius},{radius} 0,1 0,{-radius},0 Z'
            else:
                d=child.attrib['d']
            shapes += f'<path android:fillColor="{child.attrib["fill"]}" android:fillType="evenOdd" android:pathData="{d}"/>'
        shapes += '</group>'
    scale='.68' if adaptive else '1'
    offset='81.92' if adaptive else '0'
    return ('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="'+('24' if mono else '108')+'dp" '
            'android:height="'+('24' if mono else '108')+'dp" android:viewportWidth="512" android:viewportHeight="512">'
            f'<group android:scaleX="{scale}" android:scaleY="{scale}" android:translateX="{offset}" android:translateY="{offset}">'
            + '<group android:translateY="-8">' + shapes + '</group></group></vector>\n')


def prepare_exports():
    EXPORT.mkdir(parents=True, exist_ok=True)
    base=BRAND['symbol']()
    export_svg('sequo-symbol.svg', svg(base,'24 40 464 448','SEQUO folded parcel'),1024)
    export_svg('sequo-symbol-dark.svg',svg(themed(base,True),'24 40 464 448','SEQUO folded parcel for dark surfaces'),1024)
    export_svg('sequo-symbol-white.svg',svg(BRAND['symbol'](True),'24 40 464 448','SEQUO one-color parcel'),1024)
    for name,color,dark in [('light','#002C1C',False),('horizontal','#FFFFFF',True),('monochrome','#FFFFFF',False)]:
        mark = BRAND['symbol'](True) if name=='monochrome' else themed(base,dark)
        body=mark+'<g transform="translate(548 152)">'+wordmark(color)+'</g>'
        export_svg(f'sequo-logo-{name}.svg',svg(body,'24 40 1622 448','SEQUO full logo'),1600)
        if name!='monochrome':
            export_svg(f'sequo-wordmark-{name}.svg',svg(wordmark(color),'-16 -16 1090 280','SEQUO wordmark'),1280)
            body='<g transform="translate(106 -36)">'+mark+'</g><g transform="translate(72 452) scale(.55)">'+wordmark(color)+'</g>'
            export_svg(f'sequo-stacked-{name}.svg',svg(body,'0 0 724 630','SEQUO stacked logo'),1024)
    for repo,(_,role) in MOBILE.items():
        folder=EXPORT/repo; folder.mkdir(exist_ok=True)
        for name,dark,tinted in [('app-icon',False,False),('app-icon-dark',True,False),('app-icon-tinted',False,True)]:
            export_svg(f'{repo}/{name}.svg',icon(role,dark,tinted),1024)
            # Apple app icons must be opaque RGB and must not bake in a mask.
            p=folder/(name+'.png'); im=Image.open(p).convert('RGB'); im.save(p)
        export_svg(f'{repo}/app-icon-preview.svg',icon(role,rounded=True),512)
        export_svg(f'{repo}/adaptive-foreground.svg',icon(role,adaptive=True),432)
        (folder/'adaptive-foreground.xml').write_text(android_vector(role))
    export_svg('sequo-app-icon.svg',icon(rounded=True),512)
    export_svg('sequo-favicon.svg',icon(),512)
    favicon=Image.open(EXPORT/'sequo-favicon.png').convert('RGBA')
    favicon.save(EXPORT/'favicon.ico',sizes=[(16,16),(32,32),(48,48)])
    for name,size in [('apple-touch-icon',180),('icon-192',192),('icon-512',512),('maskable-192',192),('maskable-512',512)]:
        content = icon(adaptive=True).replace('<title id="title">SEQUO adaptive foreground</title>', '<title id="title">SEQUO maskable icon</title><rect width="512" height="512" fill="#F7F8F4"/>') if name.startswith('maskable') else icon()
        source=export_svg(name+'.svg',content)
        render(source,EXPORT/(name+'.png'),size)


def mobile(repo, resource_package, role):
    resources=Path('shared/src/commonMain/composeResources')
    for source in EXPORT.glob('sequo-*.svg'):
        stage(repo,resources/'files/brand/logos'/source.name,source.read_bytes())
    draw={
        'sequo_brand_symbol': 'sequo-symbol', 'sequo_brand_symbol_dark':'sequo-symbol-dark',
        'sequo_brand_logo_light':'sequo-logo-light','sequo_brand_logo_dark':'sequo-logo-horizontal',
        'sequo_brand_wordmark_light':'sequo-wordmark-light','sequo_brand_wordmark_dark':'sequo-wordmark-horizontal',
        'sequo_brand_stacked_light':'sequo-stacked-light','sequo_brand_stacked_dark':'sequo-stacked-horizontal',
    }
    for key,value in draw.items():
        target=resources/'drawable'/(key+'.png')
        stage(repo,target,(EXPORT/(value+'.png')).read_bytes())
    imports='\n'.join(f'import {resource_package}.shared.generated.resources.{key}' for key in draw)
    helper=f'''package dev.orestegabo.sequo.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource
import {resource_package}.shared.generated.resources.Res
{imports}

// Logo artwork follows the app's selected theme, including explicit dark mode.
@Composable
private fun darkBrandSurface() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

@Composable
fun sequoBrandSymbolPainter(): Painter = painterResource(
    if (darkBrandSurface()) Res.drawable.sequo_brand_symbol_dark else Res.drawable.sequo_brand_symbol,
)

@Composable
fun sequoBrandWordmarkPainter(): Painter = painterResource(
    if (darkBrandSurface()) Res.drawable.sequo_brand_wordmark_dark else Res.drawable.sequo_brand_wordmark_light,
)

@Composable
fun sequoBrandLogoPainter(stacked: Boolean = false): Painter = painterResource(
    if (stacked) {{
        if (darkBrandSurface()) Res.drawable.sequo_brand_stacked_dark else Res.drawable.sequo_brand_stacked_light
    }} else {{
        if (darkBrandSurface()) Res.drawable.sequo_brand_logo_dark else Res.drawable.sequo_brand_logo_light
    }},
)
'''
    stage(repo,'shared/src/commonMain/kotlin/dev/orestegabo/sequo/design/SequoBrand.kt',helper)
    kotlin=WORKSPACE/repo/'shared/src/commonMain/kotlin'
    for file in kotlin.rglob('*.kt'):
        source=source_text(repo,file.relative_to(WORKSPACE/repo)); original=source; used=set()
        for old,new in [('sequo_icon_green','sequoBrandSymbolPainter()'),('sequo_shopping_icon','sequoBrandSymbolPainter()'),
                        ('sequo_app_icon','sequoBrandSymbolPainter()'),('sequo_vendor_app_icon','sequoBrandSymbolPainter()'),
                        ('sequohub_logo_mark','sequoBrandSymbolPainter()'),('rider_brand_icon','sequoBrandSymbolPainter()'),
                        ('sequo_primary_dark','sequoBrandWordmarkPainter()'),('sequohub_logo_text','sequoBrandWordmarkPainter()'),
                        ('sequohub_logo_full','sequoBrandLogoPainter(stacked = true)')]:
            match=f'painterResource(Res.drawable.{old})'
            if match in source:
                source=source.replace(match,new); used.add(new.split('(')[0])
        if source!=original:
            for function in sorted(used):
                source=source.replace('\nimport ',f'\nimport dev.orestegabo.sequo.design.{function}\nimport ',1)
            stage(repo,file.relative_to(WORKSPACE/repo),source)
    if repo=='SequoRider':
        relative=Path('shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/splash/RiderSplash.kt')
        patch(repo,relative,[('\nimport androidx.compose.foundation.layout.Arrangement','\nimport androidx.compose.foundation.Image\nimport androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.height\nimport androidx.compose.foundation.layout.width\nimport androidx.compose.ui.unit.dp\nimport dev.orestegabo.sequo.design.sequoBrandLogoPainter\nimport androidx.compose.foundation.layout.Arrangement'),
                             ('        Text(appText(RiderText.AppName)', '        Image(sequoBrandLogoPainter(), contentDescription = "SEQUO", modifier = Modifier.width(240.dp).height(72.dp))\n        Spacer(Modifier.height(16.dp))\n        Text(appText(RiderText.AppName)')])
    android=Path('androidApp/src/main/res')
    stage(repo,android/'drawable/ic_brand_foreground.xml',android_vector(role))
    stage(repo,android/'drawable/ic_brand_monochrome.xml',android_vector(adaptive=True,mono=True))
    stage(repo,android/'drawable/ic_stat_sequo.xml',android_vector(mono=True,adaptive=False))
    background='<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="#F7F8F4"/></shape>\n'
    stage(repo,android/'drawable/ic_launcher_background.xml',background)
    for name in ('ic_launcher','ic_launcher_round'):
        stage(repo,android/f'mipmap-anydpi-v26/{name}.xml','<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/ic_launcher_background"/><foreground android:drawable="@drawable/ic_brand_foreground"/><monochrome android:drawable="@drawable/ic_brand_monochrome"/></adaptive-icon>\n')
    master=Image.open(EXPORT/repo/'app-icon.png').convert('RGBA')
    for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
        square=master.resize((size,size),Image.Resampling.LANCZOS)
        target=STAGE/'rendered'/repo/f'launcher-{size}.png'; target.parent.mkdir(parents=True,exist_ok=True)
        square.convert('RGB').save(target)
        stage(repo,android/f'mipmap-{density}/ic_launcher.png',target.read_bytes())
        # Android launchers apply their own adaptive masks. Legacy round resources
        # get a true circular alpha mask, not a pre-rounded square inside a circle.
        from PIL import ImageDraw
        mask=Image.new('L',(size*4,size*4)); ImageDraw.Draw(mask).ellipse((0,0,size*4-1,size*4-1),fill=255)
        circle=square.copy(); circle.putalpha(mask.resize((size,size),Image.Resampling.LANCZOS)); circle.save(target)
        stage(repo,android/f'mipmap-{density}/ic_launcher_round.png',target.read_bytes())
    for file in (WORKSPACE/repo/android).glob('values*/styles.xml'):
        source=file.read_text()
        if 'windowSplashScreenAnimatedIcon' in source:
            source=re.sub(r'(<item name="android:windowSplashScreenAnimatedIcon">).*?(</item>)',r'\1@drawable/ic_brand_foreground\2',source)
            stage(repo,file.relative_to(WORKSPACE/repo),source)
    if repo=='Sequo':
        patch(repo,'androidApp/src/main/kotlin/dev/orestegabo/sequo/MainActivity.kt',
              [('.setSmallIcon(R.mipmap.ic_launcher)','.setSmallIcon(R.drawable.ic_stat_sequo)')])
    catalog=Path('iosApp/iosApp/Assets.xcassets/AppIcon.appiconset')
    contents=json.loads((WORKSPACE/repo/catalog/'Contents.json').read_text())
    if not any(entry.get('appearances') for entry in contents['images']):
        for appearance in ('dark','tinted'):
            contents['images'].append({'appearances':[{'appearance':'luminosity','value':appearance}],
                                       'idiom':'universal','platform':'ios','size':'1024x1024'})
    for entry in contents['images']:
        appearance=next((a['value'] for a in entry.get('appearances',[]) if a['appearance']=='luminosity'),None)
        if appearance:
            entry['filename']=f'app-icon-{appearance}-1024.png'
        if 'filename' not in entry:
            continue
        size=round(float(entry['size'].split('x')[0])*float(entry.get('scale','1x').rstrip('x')))
        image=Image.open(EXPORT/repo/('app-icon-'+appearance+'.png' if appearance else 'app-icon.png')).convert('RGB')
        file=STAGE/'rendered'/repo/entry['filename']; file.parent.mkdir(parents=True,exist_ok=True)
        image.resize((size,size),Image.Resampling.LANCZOS).save(file)
        stage(repo,catalog/entry['filename'],file.read_bytes())
    stage(repo,catalog/'Contents.json',json.dumps(contents,indent=2)+'\n')


def web(repo):
    for file in EXPORT.glob('*'):
        if file.stem=='app-family-preview':
            continue
        if file.is_file() and file.suffix in ('.svg','.png','.ico'):
            stage(repo,Path('public/brand')/file.name,file.read_bytes())
    stage(repo,'public/favicon.ico',(EXPORT/'favicon.ico').read_bytes())
    manifest={'name':{'sequo-admin':'SEQUO Operations','sequo-developers':'SEQUO Developers','sequo-services':'SEQUO'}[repo],
              'short_name':'SEQUO','start_url':'/','display':'browser','background_color':'#F7F8F4','theme_color':'#0B6B46',
              'icons':[{'src':f'/brand/{name}-{size}.png','sizes':f'{size}x{size}','type':'image/png',**({'purpose':'maskable'} if name=='maskable' else {'purpose':'any'})} for name in ('icon','maskable') for size in (192,512)]}
    stage(repo,'public/site.webmanifest',json.dumps(manifest,indent=2)+'\n')
    index=source_text(repo,'index.html')
    index=re.sub(r'^[ \t]*<link rel="(?:icon|alternate icon|apple-touch-icon|manifest)"[^>]*>\r?\n', '',index, flags=re.MULTILINE)
    links='''    <link rel="icon" type="image/svg+xml" href="/brand/sequo-favicon.svg" />
    <link rel="alternate icon" href="/favicon.ico" sizes="16x16 32x32 48x48" />
    <link rel="apple-touch-icon" href="/brand/apple-touch-icon.png" sizes="180x180" />
    <link rel="manifest" href="/site.webmanifest" />
'''
    index=re.sub(r'(^[ \t]*<meta charset="UTF-8"[^>]*>\r?\n)',lambda m:m[1]+links,index,flags=re.MULTILINE)
    if 'name="theme-color"' not in index:
        index=index.replace('  <head>','  <head>\n    <meta name="theme-color" content="#0B6B46" />')
    stage(repo,'index.html',index)
    if repo=='sequo-admin':
        patch(repo,'src/App.vue',[
            ('<img src="/brand/sequo-icon-green.png" alt="" /><span\n          >SEQUO<span class="brand-caption">OPERATIONS</span></span\n        >','<span class="brand-lockup"><img class="brand-logo" src="/brand/sequo-logo-horizontal.svg" alt="SEQUO" /><span class="brand-caption">OPERATIONS</span></span>'),
            ('<img class="brand-icon" src="/brand/sequo-icon-green.png" alt="" /><span\n          class="brand-wordmark"\n          >SEQUO<small>ADMIN WORKSPACE</small></span\n        >','<span class="brand-lockup"><img class="brand-logo" src="/brand/sequo-logo-light.svg" alt="SEQUO" /><small class="brand-caption">ADMIN WORKSPACE</small></span>'),
            ('<span class="workspace-mark">S</span\n        >','<img class="workspace-mark" src="/brand/sequo-app-icon.svg" alt="" />')])
        css=source_text(repo,'src/assets/main.css')
        css+=('' if '/* Shared SEQUO vector lockup;' in css else '''
/* Shared SEQUO vector lockup; captions stay separate from the brand artwork. */
.brand-lockup { display: flex; flex-direction: column; min-width: 0; }
.auth-brand .brand-logo { width: 210px; max-width: 100%; height: auto; border-radius: 0; }
.brand .brand-logo { width: 170px; max-width: 100%; height: auto; border-radius: 0; }
.brand .brand-caption { font-size: 10px; letter-spacing: 1.4px; color: #6f7b73; margin-top: 4px; }
.workspace-mark { object-fit: contain; }
''')
        stage(repo,'src/assets/main.css',css)
    elif repo=='sequo-developers':
        patch(repo,'src/App.vue',[
            ('<span class="brand-sigil">S</span>','<span class="brand-sigil"><img src="/brand/sequo-symbol.svg" alt="" /></span>'),
            ('<strong>Sequo</strong>','<img class="brand-wordmark-image" src="/brand/sequo-wordmark-light.svg" alt="SEQUO" />')])
        css=source_text(repo,'src/assets/main.css')
        css+=('' if '/* Keep the symbol visible when' in css else '''
/* Keep the symbol visible when the navigation rail is collapsed. */
.brand-sigil { background: transparent; box-shadow: none; border-radius: 0; }
.brand-sigil img { width: 100%; height: 100%; object-fit: contain; }
.brand-wordmark-image { display: block; width: 106px; max-width: 100%; height: auto; }
''')
        stage(repo,'src/assets/main.css',css)
    else:
        patch(repo,'src/App.vue',[('/images/sequo-logo.png','/brand/sequo-logo-light.svg')])
    stage(repo,'public/brand/README.md','SEQUO folded S parcel assets generated from sequo-api/docs/branding.\nRebuild with sequo-api/scripts/sequo-brand-rollout.py. All lettering is vector geometry; no fonts required.\n')


def prepare():
    # These directories contain disposable current staging outputs, not sources.
    for folder in (STAGE/'files', STAGE/'rendered'):
        if folder.exists():
            shutil.rmtree(folder)
    prepare_exports()
    for repo,(package,role) in MOBILE.items():
        mobile(repo,package,role)
    for repo in WEB:
        web(repo)
    manifest=ROOT/'docs/branding/installation.json'
    if manifest.exists():
        destinations={(p['repo'],p['path']) for p in plans}
        for record in json.loads(manifest.read_text()):
            target=WORKSPACE/record['repo']/record['path']
            if target.is_file() and (record['repo'],record['path']) not in destinations:
                stage(record['repo'],record['path'],target.read_bytes())
    # Duplicate staged destinations would make the installation ambiguous.
    names=[(p['repo'],p['path']) for p in plans]
    assert len(names)==len(set(names)), 'Duplicate staged destination'
    STAGE.mkdir(parents=True,exist_ok=True)
    (STAGE/'plan.json').write_text(json.dumps(plans,indent=2)+'\n')
    print(f'Prepared {len(plans)} files across {len(MOBILE)+len(WEB)} repositories in {STAGE}')


def apply():
    plan=json.loads((STAGE/'plan.json').read_text())
    for item in plan:
        target=WORKSPACE/item['repo']/item['path']
        current=digest(target.read_bytes()) if target.exists() else None
        if current not in (item['previous_sha256'],item['sha256']):
            raise RuntimeError(f'Concurrent edit detected: {target}')
        source=STAGE/'files'/item['repo']/item['path']
        assert digest(source.read_bytes())==item['sha256']
    for item in plan:
        target=WORKSPACE/item['repo']/item['path']
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(STAGE/'files'/item['repo']/item['path'],target)
    (ROOT/'docs/branding/installation.json').write_text(json.dumps(plan,indent=2)+'\n')
    print(f'Installed and verified {len(plan)} prepared files.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true',help='Install the already prepared files into sibling repositories')
    args=parser.parse_args()
    apply() if args.apply else prepare()
