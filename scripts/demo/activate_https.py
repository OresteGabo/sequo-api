import pathlib,subprocess,json,urllib.request
root=pathlib.Path('/home/ubuntu/sequo-demo-v1')
result=json.loads((root/'smoke-result.json').read_text())
assert result['products']==120 and len(result['journeys'])==3 and all(j['status']=='DELIVERED' for j in result['journeys'])
# Confirm live service stays healthy before changing only the extra listener.
with urllib.request.urlopen('http://127.0.0.1:8080/actuator/health',timeout=8) as r:assert json.load(r)['status']=='UP'
original=subprocess.check_output(['sudo','cat','/etc/caddy/Caddyfile']).decode()
backup=root/'Caddyfile.before-demo'
if not backup.exists():backup.write_text(original)
extra='\nhttps://api.sequoservice.com:8443 {\n    reverse_proxy 127.0.0.1:8081\n}\n'
if 'api.sequoservice.com:8443' not in original:
    candidate=root/'Caddyfile.candidate';candidate.write_text(original+extra)
    subprocess.run(['sudo','caddy','validate','--adapter','caddyfile','--config',str(candidate)],check=True)
    subprocess.run(['sudo','cp',str(candidate),'/etc/caddy/Caddyfile'],check=True)
    try:subprocess.run(['sudo','systemctl','reload','caddy'],check=True)
    except Exception:
        rollback=root/'Caddyfile.rollback';rollback.write_text(original)
        subprocess.run(['sudo','cp',str(rollback),'/etc/caddy/Caddyfile'],check=True)
        subprocess.run(['sudo','systemctl','reload','caddy'],check=True)
        raise
settings=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line)
access=root/'ACCESS.md'
access.write_text('# Sequo Demo access\n\nOpen **Sequo Demo** on your iPhone. Enter this private code on the login screen:\n\n`'+settings['DEMO_ACCESS_CODE']+'`\n\nBrowse the demo catalog, choose the prefilled demo address at checkout, then use **Orders** to advance payment, seller preparation, rider assignment, pickup and delivery. No real money or delivery is involved.\n\nDemo API: https://api.sequoservice.com:8443\n\nStart with one shop. The guided flow supports up to four shops per order. Complete deliveries to free busy riders.\n')
access.chmod(0o600)
print('Enabled separate HTTPS demo listener. Private access instructions saved without printing the code.')
