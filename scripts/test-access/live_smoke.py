import pathlib,json,urllib.request,urllib.error,subprocess,datetime
stage=pathlib.Path('/home/ubuntu/sequo-test-flow-20261110-01')
private=json.loads((stage/'private-test-credentials.json').read_text());base='https://api.sequoservice.com'
def api(path,body=None,token=None,method=None):
 headers={'Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
 try:
  with urllib.request.urlopen(req,timeout=15) as r:
   content=r.read();return json.loads(content) if content else None
 except urllib.error.HTTPError as e:raise RuntimeError(f'{path}: HTTP {e.code}') from None
def denied(path,body=None,token=None,method=None,codes=(400,401,403,404)):
 try:api(path,body,token,method)
 except RuntimeError as e:assert any('HTTP '+str(c) in str(e) for c in codes),str(e)
 else:raise AssertionError('Expected denial at '+path)
assert api('/actuator/health')['status']=='UP'
assert api('/api/auth/test-accounts')['enabled']
tokens={}
for a in private['accounts']:
 source={'CUSTOMER':'SEQUO_APP','VENDOR':'SEQUO_HUB','HUB':'SEQUO_HUB','RIDER':'SEQUO_RIDER'}[a['family']]
 response=api('/api/auth/test-accounts/session',{'username':a['username'],'password':a['password'],'appFamily':a['family'],'device':{'deviceId':'test-flow-smoke-'+a['username'],'appSource':source}})
 tokens[a['username']]=response['accessToken']
 me=api('/api/auth/me',token=response['accessToken']);assert me['id']==a['userId']
 if a['family']=='VENDOR':assert len(me['merchantScopeIds'])==1 and me['merchantScopeIds'][0].startswith('demo-v1-shop-')
 if a['family']=='CUSTOMER':assert me['roles']==['CUSTOMER']
# Invalid password and wrong-app family do not create sessions.
a=next(a for a in private['accounts'] if a['username']=='vendor.mode')
denied('/api/auth/test-accounts/session',{'username':a['username'],'password':'not-the-password-123456','appFamily':'VENDOR','device':{'deviceId':'negative-login','appSource':'SEQUO_HUB'}},codes=(401,))
denied('/api/auth/test-accounts/session',{'username':a['username'],'password':a['password'],'appFamily':'HUB','device':{'deviceId':'negative-login','appSource':'SEQUO_HUB'}},codes=(401,))
customer=tokens['customer.flow'];vendor=tokens['vendor.mode'];rider=tokens['rider.ami'];hub=tokens['hub.tokoin']
catalog=api('/api/catalog/home');p=next(p for p in catalog['products'] if p['id']=='demo-v1-mode-01')
selections=[{'groupId':g['id'],'optionIds':[next(o for o in g['options'] if o['active'])['id']]} for g in p.get('customizations',[]) if g['minChoices']>0]
assert api('/api/cart',token=customer)['totalItems']==0
api('/api/cart/items',{'productId':p['id'],'quantity':1,'selections':selections},customer,method='PUT')
points=api('/api/cart/relay-points',token=customer);assert len(points)==6 and all(p['id'].startswith('live-test-v1-hub-') for p in points)
point='live-test-v1-hub-tokoin'
quote=api('/api/cart/quote?addressId=live-test-v1-home&relayPointId='+point,token=customer)
assert quote['testOrderAvailable'] and quote['referralCreditAppliedCfa']==0 and quote['subscriptionDiscountCfa']==0
checkout=api('/api/orders/checkouts',{'addressId':'live-test-v1-home','quoteRevision':quote['revision'],'paymentProvider':'yas_togo','relayPointId':point,'testOrder':True},customer)
cid=checkout['checkoutId'];assert checkout['status']=='AWAITING_WEBHOOK' and checkout['testOrder']
confirmed=api('/api/orders/checkouts/'+cid+'/test-payment',token=customer,method='POST')
assert confirmed['status']=='COMPLETED';oid=confirmed['orderId']
assert api('/api/orders/checkouts/'+cid+'/test-payment',token=customer,method='POST')['orderId']==oid
details=api('/api/orders/'+oid,token=customer);assert details['order']['testOrder']
part=details['merchantSubOrders'][0]['id']
denied('/api/merchant/sub-orders/'+part+'/accept',{'merchantId':'demo-v1-shop-mode'},tokens['vendor.bistro'],codes=(403,))
for action in ['accept','start-preparation','mark-packed']:api('/api/merchant/sub-orders/'+part+'/'+action,{'merchantId':'demo-v1-shop-mode','packageCount':1},vendor)
assert api('/api/orders/checkouts/'+cid+'/test-dispatch',token=customer)['ready']
api('/api/orders/checkouts/'+cid+'/test-dispatch',{'riderId':'demo-v1-rider-1'},customer)
assigned=api('/api/orders/'+oid,token=customer)['deliveries'];assert len(assigned)==1
mid=next(m['id'] for m in api('/api/rider/missions',token=rider) if m['orderId']==oid)
denied('/api/rider/missions/'+mid,token=tokens['rider.kossi'],codes=(403,))
api('/api/rider/missions/'+mid+'/accept',token=rider,method='POST')
api('/api/rider/missions/'+mid+'/pickup/complete',{'proofMetadata':'TEST scripted validation — no physical pickup','idempotencyKey':'test-flow-pickup-'+mid},rider)
pg=json.loads(subprocess.check_output(['docker','inspect','sequo-api-postgres-1']))[0];e=dict(v.split('=',1) for v in pg['Config']['Env'] if '=' in v)
def sql(q):return subprocess.check_output(['docker','exec','-i','sequo-api-postgres-1','psql','-U',e['POSTGRES_USER'],'-d',e['POSTGRES_DB'],'-At','-v','ON_ERROR_STOP=1'],input=q.encode()).decode().strip()
slot=sql("select locker_code from relay_lockers where relay_point_id='live-test-v1-hub-tokoin' and status='AVAILABLE' order by locker_code limit 1;");assert slot
api('/api/rider/missions/'+mid+'/relay-deposit',{'relayPointId':point,'lockerCode':slot,'proofMetadata':'TEST scripted validation — no physical relay deposit','idempotencyKey':'test-flow-deposit-'+mid},rider)
pickup=api('/api/orders/'+oid+'/relay-pickups',token=customer)[0];parcel=pickup['parcelId'];code=pickup['numericCode'];assert code
# Operators cannot read another hub's custody.
denied('/api/relay/parcels?relayPointId='+point,token=tokens['hub.assigame'],codes=(403,))
api('/api/relay/parcels/'+parcel+'/release',{'relayPointId':point,'rawNumericCode':code,'identityDocumentMatched':True,'eventId':'test-flow-release-'+parcel,'idempotencyKey':'test-flow-release-'+parcel},hub)
finished=api('/api/orders/'+oid,token=customer);assert finished['order']['orderStatus']=='DELIVERED'
assert finished['deliveries'][0]['status']=='RELEASED_BY_RELAY'
assert sql("select count(*) from merchant_payout_accruals where order_id='"+oid+"';")=='0'
assert sql("select count(*) from delivery_settlements where mission_id='"+mid+"';")=='0'
assert sql("select count(*) from settlement_ledger_entries where source_id in ('"+mid+"','"+part+"','"+parcel+"');")=='0'
assert sql("select count(*) from payment_webhook_events where checkout_id='"+cid+"';")=='0'
assert sql("select count(*) from delivery_benefit_applications where checkout_id='"+cid+"';")=='0'
assert sql("select status from relay_lockers where relay_point_id='"+point+"' and locker_code='"+slot+"';")=='AVAILABLE'
result={'credentialLoginsVerified':len(tokens),'checkoutId':cid,'orderId':oid,'missionId':mid,'status':'DELIVERED','relayStatus':'RELEASED_BY_RELAY','merchantPayouts':0,'riderSettlements':0,'ledgerEntries':0,'providerReceipts':0,'benefitApplications':0,'slotReleased':True,'verifiedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'note':'HTTP/service verification with explicitly scripted test custody, not a human physical delivery.'}
(stage/'live-verification.json').write_text(json.dumps(result,indent=2)+'\n')
print('27 scoped logins verified. Live test-only customer/vendor/rider/hub journey completed through pickup-code release. No provider receipt, financial ledger, payout, benefit consumption or occupied slot remains.')
