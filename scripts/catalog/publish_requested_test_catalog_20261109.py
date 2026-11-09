import pathlib,subprocess,json,hashlib,datetime,urllib.request,zipfile,shutil
root=pathlib.Path('/home/ubuntu/sequo-live-test-catalog-20261109');root.mkdir(mode=0o700,exist_ok=True)
source='sequo-demo-v1-demo-postgres-1';target='sequo-api-postgres-1'
meta=json.loads(subprocess.check_output(['docker','inspect',target]))[0]
env=dict(v.split('=',1) for v in meta['Config']['Env'] if '=' in v)
user=env['POSTGRES_USER'];db=env['POSTGRES_DB']
def sql(container,username,database,query):
 return subprocess.check_output(['docker','exec','-i',container,'psql','-U',username,'-d',database,'-At','-v','ON_ERROR_STOP=1'],input=query.encode()).decode().strip()
def live(query):return sql(target,user,db,query)
def rows(table,where):
 raw=sql(source,'demo_app','sequo_demov1',f"select coalesce(json_agg(t),'[]') from (select * from {table} where {where}) t;")
 return json.loads(raw)
assert live("select max(version::int) from flyway_schema_history where success;")=='68'
# Exact namespace and narrow master data only; never copy a customer/admin, tokens, orders or payments.
shops=rows('merchants',"id like 'demo-v1-shop-%'")
products=rows('products',"id like 'demo-v1-%'")
couriers=rows('couriers',"id in ('demo-v1-rider-1','demo-v1-rider-2','demo-v1-rider-3','demo-v1-rider-4')")
assert len(shops)==12 and len(products)==120 and len(couriers)==4
userids=[s['owner_user_id'] for s in shops]+[c['user_id'] for c in couriers]
def quote(s):return "'"+s.replace("'","''")+"'"
users=rows('users','id in ('+','.join(map(quote,userids))+')')
assert len(users)==16 and all(u['id'].startswith('demo-v1-') and u['email'].endswith('@demo.invalid') for u in users)
# Prefix rider names explicitly as test data, including operational screens.
for u in users:u['display_name']=u['display_name'].replace('[DEMO]','[TEST]')
for s in shops:s['name']=s['name'].replace('[DEMO]','[TEST]')
for p in products:
 p['name']=p['name'].replace('[DEMO]','[TEST]')
 p['image_url']='https://api.sequoservice.com/test-catalog/'+p['id']+'.jpg'
 p['camera_verified']=False;p['captured_at_label']=None
roles=rows('user_roles','user_id in ('+','.join(map(quote,userids))+')')
assert all(r['role_code'] in ('MERCHANT_OWNER','COURIER') for r in roles)
tables=[('users',users),('user_roles',roles),('merchants',shops),('merchant_memberships',rows('merchant_memberships',"merchant_id like 'demo-v1-shop-%'")),('couriers',couriers),('merchant_storefronts',rows('merchant_storefronts',"merchant_id like 'demo-v1-shop-%'")),('products',products),('product_customization_groups',rows('product_customization_groups',"product_id like 'demo-v1-%'")),('product_customization_options',rows('product_customization_options',"group_id like 'demo-v1-%'"))]
for table,data in tables:
 if table=='user_roles':continue
 ids=[r['merchant_id'] if table=='merchant_storefronts' else r['id'] for r in data]
 col='merchant_id' if table=='merchant_storefronts' else 'id'
 assert live(f"select count(*) from {table} where {col} in ("+','.join(map(quote,ids))+');')=='0',f'{table} namespace is occupied; refusing to overwrite.'
# Backup before changes; unique path prevents accidental replacement of prior evidence.
backup=root/'database-before.dump';assert not backup.exists()
with backup.open('wb') as f:subprocess.run(['docker','exec',target,'pg_dump','-U',user,'-d',db,'-Fc'],stdout=f,check=True)
backup.chmod(0o600)
manifest={table:[r.get('id') or r.get('merchant_id') or r.get('user_id') for r in data] for table,data in tables}
(root/'seed-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
# Prepare publicly labelled graphics in a directory containing no private configuration.
assets=root/'assets';assets.mkdir(exist_ok=True)
with zipfile.ZipFile('/home/ubuntu/sequo-demo-v1/app.jar') as jar:
 for p in products:
  name=p['id']+'.jpg';content=jar.read('BOOT-INF/classes/demo/'+name)
  assert content[:2]==b'\xff\xd8';(assets/name).write_bytes(content)
subprocess.run(['sudo','mkdir','-p','/var/www/sequo-test-catalog'],check=True)
for file in assets.glob('*.jpg'):subprocess.run(['sudo','install','-m','644',str(file),'/var/www/sequo-test-catalog/'+file.name],check=True)
original=subprocess.check_output(['sudo','cat','/etc/caddy/Caddyfile']).decode()
(root/'Caddyfile.before').write_text(original)
needle='api.sequoservice.com {\n    reverse_proxy 127.0.0.1:8080\n}'
assert needle in original
replacement='''api.sequoservice.com {
    handle_path /test-catalog/* {
        root * /var/www/sequo-test-catalog
        file_server
    }
    handle {
        reverse_proxy 127.0.0.1:8080
    }
}'''
candidate=root/'Caddyfile.candidate';candidate.write_text(original.replace(needle,replacement,1))
subprocess.run(['sudo','caddy','validate','--adapter','caddyfile','--config',str(candidate)],check=True)
# Validate all inserts on a rollback transaction before the committed write.
statements=[]
for table,data in tables:
 payload=json.dumps(data,ensure_ascii=False)
 assert '$sequo_catalog$' not in payload
 statements.append(f'insert into {table} select * from json_populate_recordset(null::{table},$sequo_catalog${payload}$sequo_catalog$::json);')
script='\n'.join(statements)
live('begin;\n'+script+'\nrollback;')
subprocess.run(['sudo','cp',str(candidate),'/etc/caddy/Caddyfile'],check=True)
try:subprocess.run(['sudo','systemctl','reload','caddy'],check=True)
except Exception:
 subprocess.run(['sudo','cp',str(root/'Caddyfile.before'),'/etc/caddy/Caddyfile'],check=True)
 subprocess.run(['sudo','systemctl','reload','caddy'],check=True);raise
live('begin;\n'+script+'\ncommit;')
assert live("select count(*) from products where id like 'demo-v1-%';")=='120'
assert live("select count(*) from merchants where id like 'demo-v1-shop-%';")=='12'
assert live("select count(*) from couriers where id like 'demo-v1-rider-%';")=='4'
with urllib.request.urlopen('https://api.sequoservice.com/api/catalog/home',timeout=10) as r:catalog=json.load(r)
seeded=[p for p in catalog['products'] if p['id'].startswith('demo-v1-')]
assert len(seeded)==120 and all(not p['cameraVerified'] for p in seeded)
for p in seeded:
 with urllib.request.urlopen(p['imageUrl'],timeout=8) as r:assert r.read(2)==b'\xff\xd8'
(root/'verified.json').write_text(json.dumps({'shops':12,'products':120,'riders':4,'verifiedImages':120,'verifiedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()},indent=2)+'\n')
print('Live catalog verified: 12 labelled test shops, 120 products/images, 4 test rider records. Existing catalog and financial records preserved. No demo payment or order history copied.')
