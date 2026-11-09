import pathlib,json,time,urllib.request,urllib.error,uuid
root=pathlib.Path('/home/ubuntu/sequo-demo-v1')
env=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line)
base='http://127.0.0.1:8081'
def request(path,body=None,token=None,method=None):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=15) as r:
            raw=r.read();return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise RuntimeError(f'{path}: HTTP {e.code}; {e.read().decode()[:400]}') from None
for i in range(30):
    try:
        if request('/actuator/health')['status']=='UP':break
    except Exception:pass
    time.sleep(2)
else:raise RuntimeError('Demo API did not become ready')
catalog=request('/api/catalog/home');products=catalog['products']
assert len(products)==120
assert len({p['merchantId'] for p in products})==12
assert all(p['name'].startswith('[DEMO]') and not p['cameraVerified'] for p in products)
for p in products:
    with urllib.request.urlopen(base+'/api/demo/images/'+p['id'],timeout=8) as r:
        assert r.status==200 and r.headers.get_content_type()=='image/jpeg' and r.read(2)==b'\xff\xd8'
token=request('/api/auth/demo/session',{'accessCode':env['DEMO_ACCESS_CODE'],'deviceId':'demo-smoke-'+str(uuid.uuid4())})['accessToken']
assert request('/api/auth/me',token=token)['id']=='demo-v1-customer'
# No normal order is created from anonymous or unknown code access.
for path,body in [('/api/auth/demo/session',{'accessCode':'invalid','deviceId':'invalid'}),('/api/demo/checkouts/unknown/advance',{'action':'PAY'})]:
    try: request(path,body)
    except RuntimeError as e: assert 'HTTP 401' in str(e) or 'HTTP 403' in str(e)
    else: raise AssertionError('Anonymous/invalid demo access succeeded')
completed=[]
for test_case,ids in [('measured',['demo-v1-grains-01']),('meal',['demo-v1-bistro-01']),('two-shops',['demo-v1-mode-01','demo-v1-pas-01'])]:
    for pid in ids:
        p=next(p for p in products if p['id']==pid)
        selections=[{'groupId':g['id'],'optionIds':[next(o for o in g['options'] if o['active'])['id']]} for g in p.get('customizations',[]) if g['minChoices']>0]
        cart=request('/api/cart/items',{'productId':pid,'quantity':2 if test_case=='measured' else 1,'selections':selections},token,method='PUT')
        if test_case=='measured':assert cart['subtotalCfa']==950
    quote=request('/api/cart/quote?addressId=demo-v1-home',token=token)
    checkout=request('/api/orders/checkouts',{'addressId':'demo-v1-home','quoteRevision':quote['revision'],'paymentProvider':'yas_togo'},token)
    cid=checkout['checkoutId'];assert checkout['status']=='AWAITING_WEBHOOK'
    state=request('/api/demo/checkouts/'+cid,token=token)
    steps=[]
    while state['nextAction']:
        action=state['nextAction'];steps.append(action)
        state=request('/api/demo/checkouts/'+cid+'/advance',{'action':action,'riderId':'demo-v1-rider-1'},token)
        replay=request('/api/demo/checkouts/'+cid+'/advance',{'action':action,'riderId':'demo-v1-rider-1'},token)
        assert replay['stage']==state['stage']
    assert steps==['PAY','ACCEPT','PREPARE','PACK','ASSIGN','RIDER_ACCEPT','PICKUP','DELIVER']
    details=next(o for o in request('/api/orders',token=token) if o['order']['checkoutId']==cid)
    assert details['order']['orderStatus']=='DELIVERED'
    assert len(details['deliveries'])==len(ids)
    if test_case=='measured':assert '0.5' in details['lines'][0]['optionLabel']
    completed.append({'case':test_case,'checkoutId':cid,'orderId':details['order']['id'],'status':'DELIVERED','steps':steps})
(root/'smoke-result.json').write_text(json.dumps({'shops':12,'products':120,'verifiedImages':120,'journeys':completed},indent=2)+'\n')
print('Demo verified: 12 shops, 120 products/images, measured/meal/two-shop journeys all delivered, repeated requests did not skip steps.')
