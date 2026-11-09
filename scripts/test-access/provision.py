import pathlib,subprocess,json,os,secrets,hashlib,base64,datetime
os.umask(0o077)
stage=pathlib.Path('/home/ubuntu/sequo-test-flow-20261110-01')
pg=json.loads(subprocess.check_output(['docker','inspect','sequo-api-postgres-1']))[0]
env=dict(v.split('=',1) for v in pg['Config']['Env'] if '=' in v)
def sql(query):return subprocess.check_output(['docker','exec','-i','sequo-api-postgres-1','psql','-U',env['POSTGRES_USER'],'-d',env['POSTGRES_DB'],'-At','-v','ON_ERROR_STOP=1'],input=query.encode()).decode().strip()
def q(value):return "'"+str(value).replace("'","''")+"'"
assert sql('select max(version::int) from flyway_schema_history where success;')=='69'
assert sql('select count(*) from test_account_credentials;')=='0'
now=datetime.datetime.now(datetime.timezone.utc);expires=now+datetime.timedelta(days=30)
accounts=[]
shops=['bistro','saveurs','grains','marche','boissons','mode','petits','pas','tech','maison','tissus','essentiels']
for slug in shops:accounts.append(dict(username='vendor.'+slug,userId='demo-v1-shop-'+slug+'-owner',family='VENDOR',assignment='[TEST] '+slug))
for i,slug in enumerate(shops[:4],1):accounts.append(dict(username='staff.'+slug,userId='live-test-v1-vendor-staff-'+str(i),family='VENDOR',assignment='Staff: [TEST] '+slug))
for slug in ['tokoin','assigame','adidogome','hedzranawoe','agoe','be-kpota']:accounts.append(dict(username='hub.'+slug,userId='live-test-v1-hub-'+slug+'-operator',family='HUB',assignment='[TEST] SequoHub '+slug))
for i,name in enumerate(['ami','kossi','sena','yao'],1):accounts.append(dict(username='rider.'+name,userId='demo-v1-rider-'+str(i)+'-user',family='RIDER',assignment='[TEST] '+name))
accounts.append(dict(username='customer.flow',userId='live-test-v1-customer',family='CUSTOMER',assignment='Dedicated test customer'))
assert sql("select count(*) from users where id='live-test-v1-customer';")=='0'
statements=["insert into users(id,email,display_name,is_active,provider,status,created_at,updated_at) values ('live-test-v1-customer','customer-flow@fixture.invalid','[TEST] Customer Flow',true,'EMAIL','ACTIVE',current_timestamp,current_timestamp);","insert into user_roles(user_id,role_code) values ('live-test-v1-customer','CUSTOMER');","insert into customer_addresses(id,user_id,kind,label,recipient_name,phone_number,city,area,street_hint,latitude,longitude,active,created_at,updated_at,version) values ('live-test-v1-home','live-test-v1-customer','HOME','[TEST] Maison de test','Client fictif','+22800000000','Lome','Tokoin','Adresse fictive — aucun deplacement reel',6.178,1.237,true,current_timestamp,current_timestamp,0);"]
for a in accounts:
 if a['family']!='CUSTOMER':assert sql('select count(*) from users where id='+q(a['userId'])+" and is_active=true and status='ACTIVE' and provider='EMAIL' and email like '%.invalid';")=='1'
 password=secrets.token_urlsafe(18);salt=secrets.token_bytes(24)
 digest=hashlib.pbkdf2_hmac('sha256',password.encode(),salt,210000,dklen=32)
 hashed='pbkdf2-sha256:210000:'+base64.b64encode(salt).decode()+':'+base64.b64encode(digest).decode()
 a['password']=password
 statements.append('insert into test_account_credentials(username,user_id,app_family,password_hash,enabled,expires_at) values ('+','.join([q(a['username']),q(a['userId']),q(a['family']),q(hashed),'true',q(expires.isoformat())])+');')
payload='\n'.join(statements)
# Validate before committing, including all application-specific identity constraints.
sql('begin;\n'+payload+'\nrollback;')
sql('begin;\n'+payload+'\ncommit;')
assert sql('select count(*) from test_account_credentials;')=='27'
private={'expiresAt':expires.isoformat(),'accounts':accounts}
(stage/'private-test-credentials.json').write_text(json.dumps(private,indent=2)+'\n')
(stage/'private-test-credentials.json').chmod(0o600)
from zoneinfo import ZoneInfo
expiry=expires.astimezone(ZoneInfo('Europe/Paris')).strftime('%Y-%m-%d %H:%M Europe/Paris')
md='# Private Sequo test credentials\n\nNormal deployed API: https://api.sequoservice.com\n\nExpires: **'+expiry+'** (30 days). Keep this file private; it is not committed to Git.\n\nUse **Sign in to a test account** in the named app. Use the normal apps, not the local demo workspace.\n\n'
for family,app in [('CUSTOMER','Sequo'),('VENDOR','SequoVendor'),('RIDER','SequoRider'),('HUB','SequoHub')]:
 md+='## '+app+'\n\n| Username | Password | Assignment |\n| --- | --- | --- |\n'
 for a in accounts:
  if a['family']==family:md+='| `'+a['username']+'` | `'+a['password']+'` | '+a['assignment']+' |\n'
 md+='\n'
md+='Follow [TEST_ORDER_FLOW.md](../../TEST_ORDER_FLOW.md) for the complete relay journey. No real wallet charge or payout is created. Password expiry prevents new sign-ins; deactivate users or revoke sessions to stop active sessions immediately.\n'
(stage/'TEST_ACCOUNTS.md').write_text(md);(stage/'TEST_ACCOUNTS.md').chmod(0o600)
print('Provisioned 27 unique, hashed, scoped test credentials and one fictional customer/address. Private guide saved; no password printed.')
