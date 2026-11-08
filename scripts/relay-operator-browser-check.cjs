const fs = require('node:fs');
const http = require('node:http');
const assert = require('node:assert/strict');
const puppeteer = require('puppeteer-core');
const base = process.cwd() + '/src/main/resources/static/';
let note = ''; let overdue = false; let state = 'Deposited'; let offline = false; let expired = false;
let omitReviewReadback = true; let uncertainReview = true; let uncertainDisposition = true;
const reviewEvents = []; const returnEvents = [];
let customerReturn = null; let uncertainReturn = true; let forbiddenReturns = false;
const calls = [];
const server = http.createServer(async (req, res) => {
  if (req.url === '/relay-operator.html' || req.url === '/relay-operator.js') {
    res.setHeader('Content-Type', req.url.endsWith('.js') ? 'text/javascript' : 'text/html');
    res.end(fs.readFileSync(base + req.url.substring(1))); return;
  }
  let raw = ''; for await (const part of req) raw += part;
  calls.push({ path: req.url, auth: req.headers.authorization, body: raw && JSON.parse(raw) });
  res.setHeader('Content-Type','application/json');
  if ((req.url.startsWith('/api/relay/') && expired) || (req.url.startsWith('/api/returns') && forbiddenReturns)) { res.statusCode = 403; res.end('{}'); return; }
  if (offline && (req.url.startsWith('/api/relay/') || req.url.startsWith('/api/returns'))) { res.statusCode = 503; res.end('{}'); return; }
  let response = {};
  const parcel = {id:'parcel-fixture-A',relayPointId:'site-A',status:state,custody:{feeStartsAt:'2026-10-08T00:00:00Z',reviewDueAt:'2026-10-30T00:00:00Z',storageFeeEligible:overdue && state === 'Deposited',reviewDue:overdue && state === 'Deposited',feePolicyKnown:true},history:[{type:'Deposit',createdAt:'2026-11-08T00:00:00Z'}, ...reviewEvents, ...returnEvents, ...(state === 'PickedUp' ? [{type:'RelayRelease',createdAt:'2026-11-08T00:10:00Z'}]:[])]};
  if (req.url.includes('/verify') || req.url.includes('/refresh')) response = {accessToken:'fixture-access',refreshToken:'fixture-refresh'};
  else if(req.url.endsWith('/sites')) response = [{id:'site-A',name:'Relais A',canReturnToSeller:true},{id:'site-B',name:'Relais B',canReturnToSeller:false}];
  else if(req.url.endsWith('/deadlines/evaluate')) {overdue=true;response=[];}
  else if(req.url.startsWith('/api/returns/relay?')) response = customerReturn?.relayPointId === new URL(req.url,'http://fixture.invalid').searchParams.get('relayPointId') ? [customerReturn] : [];
  else if(req.url.endsWith('/relay-dropoff')) {
    const body = JSON.parse(raw);
    if (body.rawReturnPin !== '654321') {res.statusCode=400;res.end(JSON.stringify({message:'Code PIN de retour invalide.'}));return;}
    if (body.relayPointId !== 'site-A') {res.statusCode=400;res.end(JSON.stringify({message:'Point relais non autorisé pour ce retour.'}));return;}
    customerReturn ||= {id:'return-fixture-A',orderId:'order-fixture',relayPointId:'site-A',status:'DroppedAtRelay',droppedAt:'2026-11-08T00:10:00Z',history:[{action:'RELAY_DROPOFF',actorUserId:'operator-fixture',createdAt:'2026-11-08T00:10:00Z',metadata:null}]};
    if (uncertainReturn) {uncertainReturn=false;res.statusCode=503;res.end(JSON.stringify({message:'Réponse de réception incertaine (503).'}));return;}
    response=customerReturn;
  }
  else if(req.url === '/api/returns/return-fixture-A') response = customerReturn;
  else if(req.url.includes('?relayPointId=')) response = [parcel];
  else if(req.url.endsWith('/review')) {
    const body=JSON.parse(raw); note=body.metadata;
    if (!reviewEvents.some(event=>event.id === body.eventId)) reviewEvents.push({id:body.eventId,type:'Review',actorUserId:'operator-fixture',createdAt:'2026-11-08T00:05:00Z',message:note});
    if (uncertainReview) {uncertainReview=false;res.statusCode=503;res.end(JSON.stringify({message:'Décision de revue incertaine.'}));return;}
    response=parcel;
  }
  else if(req.url.endsWith('/return-to-seller')) {
    const body=JSON.parse(raw); state='ReturnedToSeller';
    if (!returnEvents.some(event=>event.id === body.eventId)) returnEvents.push({id:body.eventId,type:'ReturnDropoff',actorUserId:'admin-fixture',createdAt:'2026-11-08T00:06:00Z',message:body.metadata});
    if (uncertainDisposition) {uncertainDisposition=false;res.statusCode=503;res.end(JSON.stringify({message:'Retour vendeur incertain.'}));return;}
    response=parcel;
  }
  else if(req.url.endsWith('/release')) {state = 'PickedUp'; response = {parcel:{...parcel,status:state}};}
  else if(req.url.includes('/parcels/')) {
    response=parcel;
    if(req.url === '/api/relay/parcels/parcel-fixture-A' && reviewEvents.length && omitReviewReadback) {
      omitReviewReadback=false; response={...parcel,history:parcel.history.filter(event=>event.type !== 'Review')};
    }
  }
  res.end(JSON.stringify(response));
});
(async () => {
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  const browser = await puppeteer.launch({executablePath:process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true,args:['--no-sandbox']});
  try {
    const page = await browser.newPage(); await page.setViewport({width:390,height:844});
    let acceptDecision=false; const confirmations=[];
    page.on('dialog',async dialog=>{confirmations.push(dialog.message()); if(acceptDecision) await dialog.accept(); else await dialog.dismiss();});
    const errors=[]; page.on('pageerror',e=>errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/relay-operator.html`);
    await page.type('#phone','+22890000000'); await page.click('#request-code button');
    await page.waitForSelector('#verify-code:not([hidden])'); await page.type('#otp','123456'); await page.click('#verify-code button');
    await page.waitForSelector('#parcels button'); await page.click('#parcels button'); await page.waitForSelector('#detail:not([hidden])');
    await page.click('#evaluate'); await page.waitForFunction(()=>document.getElementById('message').textContent === 'À jour.' && !document.getElementById('evaluate').disabled); await page.waitForSelector('#parcels button'); await page.click('#parcels button');
    await page.waitForSelector('#review:not([hidden])');
    await page.type('#review-note','Hold for SEQUO coordination'); await page.click('#review button');
    await page.waitForFunction(()=>!document.getElementById('review').querySelector('button').disabled);
    assert.equal(calls.filter(c=>c.path.endsWith('/review')).length,0);
    assert.ok(confirmations[0].includes('parcel-fixture-A'));
    acceptDecision=true; await page.click('#review button');
    await page.waitForFunction(()=>document.getElementById('message').textContent.includes('revue incertaine'));
    assert.equal(await page.$eval('#review-note',e=>e.readOnly),true);
    await page.click('#issue button');
    await page.waitForFunction(()=>document.getElementById('message').textContent.includes('décision reste à confirmer'));
    assert.equal(calls.filter(c=>c.path.endsWith('/pickup-credential')).length,0);
    await page.click('#review button');
    await page.waitForFunction(()=>document.getElementById('message').textContent.includes('revue n’est pas confirmée'));
    assert.equal(await page.$eval('#review-note',e=>e.readOnly),true);
    await page.click('#review button');
    await page.waitForFunction(()=>document.getElementById('review-note').value === '');
    await page.waitForFunction(()=>document.getElementById('history').textContent.includes('coordination'));
    const reviews=calls.filter(c=>c.path.endsWith('/review'));
    assert.equal(reviews.length,3); reviews.forEach(review=>assert.deepEqual(reviews[0].body,review.body)); assert.equal(reviewEvents.length,1);
    assert.ok(await page.$eval('#history',e=>e.textContent.includes('operator-fixture')));
    await page.click('#issue button'); await page.waitForFunction(()=>document.getElementById('message').textContent === 'À jour.');
    assert.ok(calls.some(c=>c.path.endsWith('/pickup-credential')));
    await page.type('#pickup','123456'); await page.click('#identity'); await page.click('#release button');
    await page.waitForFunction(()=>document.getElementById('parcel-state').textContent.includes('Retiré'));
    assert.equal(calls.find(c=>c.path.endsWith('/release')).body.identityDocumentMatched,true);
    assert.equal(await page.$eval('#issue',e=>e.hidden),true);
    assert.equal(await page.$eval('#release',e=>e.hidden),true);
    assert.ok(await page.$eval('#parcels',e=>e.textContent.includes('Retiré')));
    await page.type('#customer-return-id','return-fixture-A'); await page.type('#customer-return-pin','111111');
    await page.type('#customer-return-note','Retour physiquement reçu'); await page.click('#customer-return-received');
    await page.click('#return-intake button'); await page.waitForFunction(()=>document.getElementById('message').textContent.includes('Code PIN de retour invalide'));
    assert.equal(await page.$eval('#customer-returns',e=>e.children.length),0);
    assert.equal(await page.$eval('#customer-return-detail',e=>e.hidden),true);
    await page.$eval('#customer-return-pin',e=>{e.value='';}); await page.type('#customer-return-pin','654321');
    await page.click('#return-intake button'); await page.waitForFunction(()=>document.getElementById('message').textContent.includes('incertaine'));
    assert.equal(await page.$eval('#customer-return-pin',e=>e.value),'654321');
    await page.click('#return-intake button'); await page.waitForFunction(()=>document.getElementById('customer-return-state').textContent.includes('Reçu au relais'));
    const intakeCalls=calls.filter(c=>c.path.endsWith('/relay-dropoff') && c.body.rawReturnPin === '654321');
    assert.equal(intakeCalls.length,2); assert.ok(intakeCalls[0].body.idempotencyKey);
    assert.deepEqual(intakeCalls[0].body,intakeCalls[1].body);
    assert.equal(intakeCalls[0].body.relayPointId,'site-A'); assert.equal(intakeCalls[0].body.droppedAt,undefined);
    assert.equal(intakeCalls[0].body.metadata,'Retour physiquement reçu');
    assert.equal(customerReturn.history.length,1);
    assert.equal(await page.$eval('#customer-return-pin',e=>e.value),'');
    assert.equal(await page.$eval('#customer-return-received',e=>e.checked),false);
    assert.ok(await page.$eval('#customer-return-history',e=>e.textContent.includes('Dépôt du retour')));
    await page.select('#site','site-B'); await page.waitForFunction(()=>document.getElementById('returns-empty').hidden === false && !document.getElementById('site').disabled);
    assert.equal(await page.$eval('#customer-returns',e=>e.children.length),0);
    assert.equal(await page.$eval('#customer-return-title',e=>e.textContent),'');
    await page.type('#customer-return-pin','123456'); await page.select('#site','site-A');
    await page.waitForSelector('#customer-returns button');
    assert.equal(await page.$eval('#customer-return-pin',e=>e.value),'');
    await page.click('#customer-returns button'); await page.waitForSelector('#customer-return-detail:not([hidden])');
    await page.screenshot({path:'/tmp/sequo-task20-relay-web.png',fullPage:true});
    await page.reload(); await page.waitForSelector('#parcels button'); await page.click('#parcels button'); await page.waitForFunction(()=>document.getElementById('history').textContent.includes('Retrait validé'));
    assert.ok(calls.some(c=>c.path.endsWith('/refresh')));
    await page.waitForSelector('#customer-returns button'); await page.click('#customer-returns button');
    await page.waitForFunction(()=>document.getElementById('customer-return-history').textContent.includes('Dépôt du retour'));
    state='Deposited'; await page.click('#reload'); await page.waitForSelector('#parcels button'); await page.click('#parcels button');
    await page.waitForSelector('#seller-return:not([hidden])');
    await page.type('#return-note','Seller receipt fixture'); await page.click('#return-confirmed');
    acceptDecision=false; await page.click('#seller-return button');
    await page.waitForFunction(()=>!document.querySelector('#seller-return button').disabled);
    assert.equal(calls.filter(c=>c.path.endsWith('/return-to-seller')).length,0);
    acceptDecision=true; await page.click('#seller-return button');
    await page.waitForFunction(()=>document.getElementById('message').textContent.includes('Retour vendeur incertain'));
    assert.equal(await page.$eval('#return-note',e=>e.readOnly),true);
    await page.click('#seller-return button');
    await page.waitForFunction(()=>document.getElementById('parcel-state').textContent.includes('Retourné au vendeur'));
    const dispositions=calls.filter(c=>c.path.endsWith('/return-to-seller'));
    assert.equal(dispositions.length,2); assert.deepEqual(dispositions[0].body,dispositions[1].body); assert.equal(returnEvents.length,1);
    assert.ok(await page.$eval('#history',e=>e.textContent.includes('admin-fixture')));
    await page.reload(); await page.waitForSelector('#parcels button'); await page.click('#parcels button');
    await page.waitForFunction(()=>document.getElementById('parcel-state').textContent.includes('Retourné au vendeur'));
    assert.equal(await page.$eval('#seller-return',e=>e.hidden),true);
    await page.screenshot({path:'/tmp/sequo-task27-relay-web.png',fullPage:true});
    offline=true; await page.click('#reload'); await page.waitForFunction(()=>document.getElementById('message').textContent.includes('503'));
    offline=false; forbiddenReturns=true; await page.click('#reload'); await page.waitForSelector('#login:not([hidden])');
    assert.equal(await page.$eval('#parcels',e=>e.children.length),0);
    assert.equal(await page.$eval('#parcel-title',e=>e.textContent),'');
    assert.equal(await page.$eval('#history',e=>e.children.length),0);
    assert.equal(await page.$eval('#customer-returns',e=>e.children.length),0);
    assert.equal(await page.$eval('#customer-return-history',e=>e.children.length),0);
    assert.equal(await page.$eval('#customer-return-title',e=>e.textContent),'');
    assert.equal(await page.$eval('#customer-return-pin',e=>e.value),'');
    assert.deepEqual(errors,[]);
    console.log('PASS: confirmed/cancelled relay decisions with frozen uncertain retries and actor history, plus scoped customer-return intake, wrong PIN, uncertain retry/stable body, server history/readback, two-site clearing, refresh restore, offline error and return authorization failure clearing both modules. Mock HTTP only.');
  } finally {await browser.close();server.close();}
})().catch(error=>{console.error(error);server.close();process.exitCode=1;});
