import pathlib,subprocess,json,datetime,math,re,urllib.request
root=pathlib.Path('/home/ubuntu/sequo-live-vendors-hubs-20261109');root.mkdir(mode=0o700,exist_ok=True)
meta=json.loads(subprocess.check_output(['docker','inspect','sequo-api-postgres-1']))[0]
env=dict(v.split('=',1) for v in meta['Config']['Env'] if '=' in v)
pguser=env['POSTGRES_USER'];db=env['POSTGRES_DB'];container='sequo-api-postgres-1'
def sql(query):return subprocess.check_output(['docker','exec','-i',container,'psql','-U',pguser,'-d',db,'-At','-v','ON_ERROR_STOP=1'],input=query.encode()).decode().strip()
def q(value):
 if value is None:return 'null'
 if isinstance(value,bool):return 'true' if value else 'false'
 if isinstance(value,(int,float)):
  assert math.isfinite(value);return str(value)
 return "'"+str(value).replace("'","''")+"'"
def insert(table,row):
 assert re.fullmatch('[a-z_]+',table) and all(re.fullmatch('[a-z_]+',k) for k in row)
 return 'insert into '+table+' ('+','.join(row)+') values ('+','.join(q(v) for v in row.values())+');'
now=datetime.datetime.now(datetime.timezone.utc).isoformat()
shops=['bistro','saveurs','grains','marche','boissons','mode','petits','pas','tech','maison','tissus','essentiels']
names=['Yawa Afi','Kossi Sena','Ama Esi','Mawuli Koffi','Dede Akou','Afi Yawa','Komlan Eyram','Sena Kossi','Esso Mawuli','Akossua Ama','Yao Komlan','Eyram Dede']
vendors=[];statements=[];newusers=[];roles=[];memberships=[];hubs=[];grids=[];slots=[]
for slug,name in zip(shops,names):
 merchant='demo-v1-shop-'+slug;owner=merchant+'-owner'
 assert sql('select count(*) from merchants m join users u on u.id=m.owner_user_id join user_roles r on r.user_id=u.id and r.role_code=\'MERCHANT_OWNER\' join merchant_memberships mm on mm.user_id=u.id and mm.merchant_id=m.id and mm.active=true where m.id='+q(merchant)+' and u.id='+q(owner)+" and u.display_name like '[TEST]%';")=='1'
 label='[TEST] '+name+' — vendeur '+slug
 vendors.append(dict(userId=owner,merchantId=merchant,name=label))
 statements.append('update users set display_name='+q(label)+',updated_at=current_timestamp,record_version=record_version+1 where id='+q(owner)+';')
def add_user(id,name,role):
 newusers.append(dict(id=id,email=id+'@fixture.invalid',display_name='[TEST] '+name,is_active=True,provider='EMAIL',status='ACTIVE',created_at=now,updated_at=now))
 roles.append(dict(user_id=id,role_code=role))
for i,(slug,name) in enumerate(zip(shops[:4],['Ami — equipe Bistro','Kokou — equipe Saveurs','Yawa — equipe Grains','Sena — equipe Marche']),1):
 id='live-test-v1-vendor-staff-'+str(i);add_user(id,name,'MERCHANT_STAFF')
 memberships.append(dict(id=id+'-membership',user_id=id,merchant_id='demo-v1-shop-'+slug,role_code='MERCHANT_STAFF',active=True,created_at=now,updated_at=now))
locations=[('tokoin','Tokoin',6.1725,1.2314,'Dede — operatrice Tokoin'),('assigame','Assigame',6.1256,1.2254,'Esso — operateur Assigame'),('adidogome','Adidogome',6.1945,1.1680,'Afi — operatrice Adidogome'),('hedzranawoe','Hedzranawoe',6.1870,1.2510,'Komlan — operateur Hedzranawoe'),('agoe','Agoe',6.2445,1.2120,'Ama — operatrice Agoe'),('be-kpota','Be-Kpota',6.1675,1.2620,'Kossi — operateur Be-Kpota')]
for slug,area,lat,lon,operator in locations:
 id='live-test-v1-hub-'+slug;uid=id+'-operator';add_user(uid,operator,'RELAY_PARTNER')
 hubs.append(dict(id=id,operator_user_id=uid,name='[TEST] SequoHub '+area,status='ACTIVE',city='Lome',neighborhood=area,landmark='SITE FICTIF — tests uniquement. Aucun point physique ni livraison reelle. Coordonnees indicatives.',latitude=lat,longitude=lon,created_at=now,updated_at=now))
 for letter in ['A','B']:
  gid=id+'-grid-'+letter.lower();grids.append(dict(id=gid,relay_point_id=id,grid_code='TEST-'+letter,label='[TEST] Etagere logique '+letter,created_at=now,updated_at=now))
  for n in range(1,7):slots.append(dict(id=gid+'-'+str(n),relay_point_id=id,relay_locker_grid_id=gid,locker_code='TEST-'+letter+str(n),status='AVAILABLE',created_at=now,updated_at=now))
tables=[('users',newusers),('user_roles',roles),('merchant_memberships',memberships),('relay_points',hubs),('relay_locker_grids',grids),('relay_lockers',slots)]
for table,rows in tables:
 if table=='user_roles':
  statements.extend(insert(table,row) for row in rows)
  continue
 assert sql('select count(*) from '+table+' where id in ('+','.join(q(r['id']) for r in rows)+');')=='0',table+' IDs already exist; nothing will be overwritten.'
 for row in rows:statements.append(insert(table,row))
backup=root/'database-before.dump';assert not backup.exists()
with backup.open('wb') as f:subprocess.run(['docker','exec',container,'pg_dump','-U',pguser,'-d',db,'-Fc'],stdout=f,check=True)
backup.chmod(0o600)
script='\n'.join(statements)
# Validate all foreign keys and normal role constraints before committing.
checks="""do $checks$ begin
 if (select count(*) from relay_points rp join users u on u.id=rp.operator_user_id join user_roles ur on ur.user_id=u.id and ur.role_code='RELAY_PARTNER' where rp.id like 'live-test-v1-hub-%' and rp.status='ACTIVE') <> 6 then raise exception 'Expected six scoped relay operators'; end if;
 if (select count(*) from user_roles where user_id like 'live-test-v1-vendor-staff-%' and role_code='MERCHANT_STAFF') <> 4 then raise exception 'Expected four scoped vendor staff roles'; end if;
end $checks$;"""
sql('begin;\n'+script+'\n'+checks+'\nrollback;')
sql('begin;\n'+script+'\n'+checks+'\ncommit;')
assert sql("select count(*) from relay_points rp join users u on u.id=rp.operator_user_id join user_roles ur on ur.user_id=u.id and ur.role_code='RELAY_PARTNER' where rp.id like 'live-test-v1-hub-%' and rp.status='ACTIVE' and u.is_active=true and u.status='ACTIVE' and rp.latitude is not null and rp.longitude is not null;")=='6'
assert sql("select count(*) from relay_lockers where relay_point_id like 'live-test-v1-hub-%' and status='AVAILABLE';")=='72'
assert sql("select count(*) from merchant_memberships where id like 'live-test-v1-vendor-staff-%' and active=true;")=='4'
# New hubs use the normal default ACTIVE controls and always-open/no-schedule fallback.
for table in ['hub_control_decisions','hub_opening_hours','hub_opening_hour_exceptions']:
 exists=sql("select count(*) from information_schema.tables where table_schema='public' and table_name="+q(table)+';')
 if exists=='1':assert sql('select count(*) from '+table+" where relay_point_id like 'live-test-v1-hub-%';")=='0'
with urllib.request.urlopen('https://api.sequoservice.com/actuator/health',timeout=10) as r:assert json.load(r)['status']=='UP'
result={'vendorOwners':vendors,'staff':memberships,'relayPoints':hubs,'operators':[u for u in newusers if '-operator' in u['id']],'grids':[g['id'] for g in grids],'slots':[s['id'] for s in slots],'verifiedAt':now,'note':'Fictional master data only. No new authentication path, paid order, payment, delivery or physical locker is created.'}
(root/'seed-manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('Verified in live database: 12 named vendor owners, 4 scoped vendor staff, 6 relay hubs/operators, 12 grids and 72 available logical slots. API healthy.')
