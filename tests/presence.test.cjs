// SPDX-License-Identifier: GPL-3.0-or-later
const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/web/presence.js'),'utf8');
function harness(fetchImpl=()=>Promise.resolve({ok:true})){
 const listeners={},intervals=new Map(),timeouts=new Map(),requests=[];let nextTimer=0;
 const context={addEventListener:(name,fn)=>listeners[name]=fn,crypto:{getRandomValues(bytes){bytes.forEach((_,i)=>bytes[i]=i+1);}},AbortController,
  setInterval(fn,ms){const id=++nextTimer;intervals.set(id,{fn,ms});return id;},clearInterval(id){intervals.delete(id);},
  setTimeout(fn,ms){const id=++nextTimer;timeouts.set(id,{fn,ms});return id;},clearTimeout(id){timeouts.delete(id);},
  fetch(url,options){requests.push({url,options});return fetchImpl(url,options);},encodeURIComponent,Math,Promise};
 vm.runInNewContext(source,context);
 return {presence:context.LumaPresence,listeners,intervals,timeouts,requests,tick(){for(const t of [...intervals.values()])t.fn();}};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));

test('start waits for pairing and then sends authenticated heartbeat without putting token in URL',async()=>{
 const h=harness();const id=h.presence.id;
 assert.match(id,/^[a-f0-9]{32}$/);assert.equal(h.presence.start(()=>''),id);assert.equal(h.requests.length,0);
 let token='secret-token';h.presence.start(()=>token);assert.equal(h.requests.length,0);
 h.tick();assert.equal(h.requests.length,1);
 const [{url,options}]=h.requests;assert.equal(url,`/api/presence?client=${id}&state=seen`);assert.equal(options.method,'POST');assert.equal(options.headers.Authorization,'Bearer secret-token');assert.equal(options.cache,'no-store');assert.equal(url.includes(token),false);
 await flush();
});

test('only one request stays pending while heartbeat ticks continue',()=>{
 const h=harness(()=>new Promise(()=>{}));h.presence.start(()=> 'token');h.tick();h.tick();h.tick();assert.equal(h.requests.length,1);
});

test('pagehide sends leave immediately even when the heartbeat never resolves',()=>{
 const h=harness(()=>new Promise(()=>{}));const id=h.presence.start(()=> 'token');
 h.listeners.pagehide();
 assert.equal(h.requests.length,2);
 assert.equal(h.requests[0].options.signal.aborted,true);
 assert.equal(h.requests[1].url,`/api/presence?client=${id}&state=left`);
 assert.equal(h.requests[1].options.keepalive,true);
});

test('pagehide sends a keepalive leave and pageshow resumes the same document identity',async()=>{
 const h=harness();const id=h.presence.start(()=> 'token');
 h.listeners.pagehide();await flush();
 assert.equal(h.requests.length,2);assert.equal(h.requests[1].url,`/api/presence?client=${id}&state=left`);assert.equal(h.requests[1].options.keepalive,true);
 h.listeners.pageshow();await flush();
 assert.equal(h.presence.id,id);assert.equal(h.requests.length,3);assert.equal(h.requests[2].url,`/api/presence?client=${id}&state=seen`);
});
