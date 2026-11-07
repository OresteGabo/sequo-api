const fs = require('node:fs');
const http = require('node:http');
const assert = require('node:assert/strict');
const puppeteer = require('puppeteer-core');
const base = process.cwd() + '/src/main/resources/static/';
let state = 'Deposited'; let offline = false; let expired = false;
const calls = [];
const server = http.createServer(async (req, res) => {
  if (req.url === '/relay-operator.html' || req.url === '/relay-operator.js') {
    res.setHeader('Content-Type', req.url.endsWith('.js') ? 'text/javascript' : 'text/html');
    res.end(fs.readFileSync(base + req.url.substring(1))); return;
  }
  let raw = ''; for await (const part of req) raw += part;
  calls.push({ path: req.url, auth: req.headers.authorization, body: raw && JSON.parse(raw) });
  res.setHeader('Content-Type','application/json');
  if (req.url.startsWith('/api/relay/') && expired) { res.statusCode = 403; res.end('{}'); return; }
  if (offline && req.url.startsWith('/api/relay/')) { res.statusCode = 503; res.end('{}'); return; }
  let response = {};
  const parcel = {id:'parcel-fixture-A',relayPointId:'site-A',status:state,history:[{type:'Deposit',createdAt:'2026-11-08T00:00:00Z'}, ...(state === 'PickedUp' ? [{type:'RelayRelease',createdAt:'2026-11-08T00:10:00Z'}]:[])]};
  if (req.url.includes('/verify') || req.url.includes('/refresh')) response = {accessToken:'fixture-access',refreshToken:'fixture-refresh'};
  else if(req.url.endsWith('/sites')) response = [{id:'site-A',name:'Relais A'}];
  else if(req.url.includes('?relayPointId=')) response = [parcel];
  else if(req.url.endsWith('/release')) {state = 'PickedUp'; response = {parcel:{...parcel,status:state}};}
  else if(req.url.includes('/parcels/')) response = parcel;
  res.end(JSON.stringify(response));
});
(async () => {
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  const browser = await puppeteer.launch({executablePath:process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true,args:['--no-sandbox']});
  try {
    const page = await browser.newPage(); await page.setViewport({width:390,height:844});
    const errors=[]; page.on('pageerror',e=>errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/relay-operator.html`);
    await page.type('#phone','+22890000000'); await page.click('#request-code button');
    await page.waitForSelector('#verify-code:not([hidden])'); await page.type('#otp','123456'); await page.click('#verify-code button');
    await page.waitForSelector('#parcels button'); await page.click('#parcels button'); await page.waitForSelector('#detail:not([hidden])');
    await page.type('#numeric','123456'); await page.click('#issue button'); await page.waitForFunction(()=>document.getElementById('numeric').value === '');
    await page.type('#pickup','123456'); await page.click('#identity'); await page.click('#release button');
    await page.waitForFunction(()=>document.getElementById('parcel-state').textContent.includes('Retiré'));
    assert.equal(calls.find(c=>c.path.endsWith('/release')).body.identityDocumentMatched,true);
    assert.equal(await page.$eval('#issue',e=>e.hidden),true);
    assert.equal(await page.$eval('#release',e=>e.hidden),true);
    assert.ok(await page.$eval('#parcels',e=>e.textContent.includes('Retiré')));
    await page.screenshot({path:'/tmp/sequo-task13-web.png',fullPage:true});
    await page.reload(); await page.waitForSelector('#parcels button');
    assert.ok(calls.some(c=>c.path.endsWith('/refresh')));
    offline=true; await page.click('#reload'); await page.waitForFunction(()=>document.getElementById('message').textContent.includes('503'));
    offline=false; expired=true; await page.click('#reload'); await page.waitForSelector('#login:not([hidden])');
    assert.equal(await page.$eval('#parcels',e=>e.children.length),0);
    assert.equal(await page.$eval('#parcel-title',e=>e.textContent),'');
    assert.equal(await page.$eval('#history',e=>e.children.length),0);
    assert.deepEqual(errors,[]);
    console.log('PASS: mobile browser sign-in, scoped list/detail, issuance, identity release, refresh restore, offline error, expired session clearing. Mock HTTP only.');
  } finally {await browser.close();server.close();}
})().catch(error=>{console.error(error);server.close();process.exitCode=1;});
