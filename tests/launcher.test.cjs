// SPDX-License-Identifier: GPL-3.0-or-later
const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/web/browser-pairing.js'),'utf8');
function launcher({hash='',search='',blocked=false,sessionToken=''}={}){
 const saved=new Map(),link={href:'/aa'},events={};
 const location={pathname:'/',hash,search};
 const context={location,URLSearchParams,encodeURIComponent,sessionStorage:{removeItem(){},getItem(){return sessionToken;}},localStorage:{getItem(){if(blocked)throw new Error('Storage unavailable');return null;},setItem(k,v){if(blocked)throw new Error('Storage unavailable');saved.set(k,v);}},document:{getElementById:()=>link},history:{replaceState(a,b,url){assert.equal(url,'/');location.hash='';location.search='';}},window:{addEventListener:(name,fn)=>events[name]=fn}};
 vm.runInNewContext(source,context);context.LumaPairing.acceptIncoming(link);events.hashchange=()=>context.LumaPairing.acceptIncoming(link);
 return {saved,link,location,events,context};
}
test('launcher preserves private pairing links for AA without connecting or starting media',()=>{
 const page=launcher({hash:'#token=pairing-fixture'});
 assert.equal(page.saved.get('projectionToken'),'pairing-fixture');assert.equal(page.link.href,'/aa');assert.equal(page.location.hash,'');
 // The sandbox supplies no fetch, WebSocket or AudioContext. Any eager start fails this test.
});
test('blocked storage keeps a pairing token in the AA fragment instead of a request query',()=>{
 const page=launcher({search:'?token=pairing-fixture',blocked:true});
 assert.equal(page.link.href,'/aa#token=pairing-fixture');assert.equal(page.location.search,'');assert.equal(page.saved.size,0);
});
test('a newly supplied pairing fragment updates the launcher handoff',()=>{
 const page=launcher();assert.equal(page.saved.size,0);assert.equal(page.link.href,'/aa');
 page.location.hash='#token=replacement-fixture';page.events.hashchange();
 assert.equal(page.saved.get('projectionToken'),'replacement-fixture');assert.equal(page.location.hash,'');
});
test('session storage remains usable when local storage access is blocked',()=>{
 const page=launcher({blocked:true,sessionToken:'session-fixture'});
 assert.equal(page.context.LumaPairing.read(),'session-fixture');
});
