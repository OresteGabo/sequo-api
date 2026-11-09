// Review built websites in an isolated headless Chrome profile; no account login.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const os = require('node:os');
const {spawn} = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'docs/branding/previews/web');
const repos = ['sequo-admin', 'sequo-developers', 'sequo-services'];
const mime = {'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.webp':'image/webp','.ico':'image/x-icon','.webmanifest':'application/manifest+json'};
const servers = [];
let chrome, socket;
const profile = fs.mkdtempSync(path.join(os.tmpdir(),'sequo-brand-chrome-'));
const pending = new Map(); let nextId=1;
function cdp(method, params={}, sessionId) {
  return new Promise((resolve,reject) => {
    const id=nextId++;
    const timeout=setTimeout(()=>{pending.delete(id);reject(new Error('CDP timeout: '+method));},30000);
    pending.set(id,{resolve,reject,timeout});
    socket.send(JSON.stringify({id,method,params,...(sessionId?{sessionId}:{})}));
  });
}
(async()=>{
  fs.mkdirSync(output,{recursive:true});
  const origins=[];
  for (const repo of repos) {
    const dist=path.join(root,'..',repo,'dist');
    assert(fs.existsSync(path.join(dist,'index.html')),repo+' must be built first');
    const server=http.createServer((req,res)=>{
      const pathname=decodeURIComponent(new URL(req.url,'http://localhost').pathname);
      const file=path.resolve(dist,'.'+(pathname==='/'?'/index.html':pathname));
      if (!file.startsWith(dist+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory()) {res.writeHead(404);res.end();return;}
      res.setHeader('Content-Type',mime[path.extname(file)]||'application/octet-stream');
      res.end(fs.readFileSync(file));
    });
    await new Promise(r=>server.listen(0,'127.0.0.1',r)); servers.push(server);
    origins.push('http://127.0.0.1:'+server.address().port);
  }
  chrome=spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',[
    '--headless=new','--no-first-run','--no-default-browser-check','--disable-background-networking',
    '--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'
  ],{stdio:'ignore'});
  const portFile=path.join(profile,'DevToolsActivePort');
  for(let i=0;i<200&&!fs.existsSync(portFile);i++) await new Promise(r=>setTimeout(r,100));
  assert(fs.existsSync(portFile),'Chrome debugging endpoint unavailable');
  const [port,endpoint]=fs.readFileSync(portFile,'utf8').trim().split('\n');
  socket=new WebSocket('ws://127.0.0.1:'+port+endpoint);
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
  socket.addEventListener('message',event=>{
    const response=JSON.parse(event.data); const waiter=pending.get(response.id);
    if(waiter){clearTimeout(waiter.timeout);pending.delete(response.id);response.error?waiter.reject(new Error(JSON.stringify(response.error))):waiter.resolve(response.result);}
  });
  const results=[];
  for(let i=0;i<repos.length;i++) {
    const {targetId}=await cdp('Target.createTarget',{url:'about:blank'});
    const {sessionId}=await cdp('Target.attachToTarget',{targetId,flatten:true});
    await cdp('Page.enable',{},sessionId); await cdp('Runtime.enable',{},sessionId);
    // Website branding can be checked without accessing any remote backend.
    await cdp('Network.enable',{},sessionId);
    await cdp('Network.setBlockedURLs',{urls:['https://api.sequoservice.com/*']},sessionId);
    for(const [name,width,height] of [['desktop',1440,1000],['mobile',390,844]]) {
      await cdp('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false},sessionId);
      await cdp('Page.navigate',{url:origins[i]},sessionId);
      let state;
      for(let attempt=0;attempt<100;attempt++) {
        const response=await cdp('Runtime.evaluate',{expression:`JSON.stringify({ready:document.readyState,images:[...document.images].filter(i=>i.src.includes('/brand/')).map(i=>({src:new URL(i.src).pathname,ok:i.complete&&i.naturalWidth>0,width:i.getBoundingClientRect().width,height:i.getBoundingClientRect().height})),overflow:document.documentElement.scrollWidth>innerWidth})`,returnByValue:true},sessionId);
        state=JSON.parse(response.result.value);
        if(state.ready==='complete'&&state.images.length&&state.images.every(i=>i.ok)) break;
        await new Promise(r=>setTimeout(r,100));
      }
      assert(state.images.length&&state.images.every(i=>i.ok),JSON.stringify({repo:repos[i],name,state}));
      assert(!state.overflow,'Horizontal page overflow: '+repos[i]+' '+name);
      const screenshot=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:false},sessionId);
      fs.writeFileSync(path.join(output,repos[i]+'-'+name+'.png'),Buffer.from(screenshot.data,'base64'));
      results.push({repo:repos[i],viewport:name,...state});
    }
    if(repos[i]==='sequo-developers') {
      await cdp('Runtime.evaluate',{expression:`document.querySelector('.rail-toggle').click()`},sessionId);
      await new Promise(r=>setTimeout(r,300));
      const response=await cdp('Runtime.evaluate',{expression:`JSON.stringify({symbolVisible:document.querySelector('.brand-sigil img').getBoundingClientRect().width>0})`,returnByValue:true},sessionId);
      assert(JSON.parse(response.result.value).symbolVisible,'Collapsed developer rail lost its symbol');
    }
    await cdp('Target.closeTarget',{targetId});
  }
  fs.writeFileSync(path.join(output,'validation.json'),JSON.stringify(results,null,2)+'\n');
  console.log('PASS: all three built websites load branding at desktop/mobile widths without overflow; collapsed developer rail retains its symbol.');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{
  if(socket)socket.close();
  if(chrome){chrome.kill('SIGTERM');await new Promise(r=>chrome.once('exit',r));}
  for(const server of servers)server.close();
  fs.rmSync(profile,{recursive:true,force:true});
});
