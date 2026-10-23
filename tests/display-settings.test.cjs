// SPDX-License-Identifier: GPL-3.0-or-later
const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/web/display-settings.js'),'utf8');
function page(fetchImpl,timeoutMs=7000){
 const listeners={};
 const input={value:'30',disabled:true,addEventListener:(name,fn)=>listeners[name]=fn};
 const output={value:'30 fps',textContent:'30 fps'},status={textContent:'Connect your phone to adjust frame rate.'};
 const context={fetch:fetchImpl,AbortController,setTimeout,clearTimeout};vm.runInNewContext(source,context);
 const settings=context.LumaDisplaySettings.create({input,output,status,fetchImpl,timeoutMs});
 return {input,output,status,listeners,settings};
}
const response=(body,ok=true)=>({ok,json:async()=>body});
const turn=()=>new Promise(resolve=>setImmediate(resolve));
test('paired launcher reads the phone saved default and enables the accessible range',async()=>{
 const calls=[],p=page(async(url,options)=>{calls.push([url,options]);return response({maxFps:30,minFps:10,maxAllowedFps:30,step:5});});
 p.settings.setPaired('phone-token');await turn();
 assert.equal(calls[0][0],'/api/display');assert.equal(calls[0][1].headers.Authorization,'Bearer phone-token');
 assert.equal(p.input.disabled,false);assert.equal(p.output.textContent,'30 fps');assert.match(p.status.textContent,/30 fps/);
});
test('changing the range previews locally and saves only on change',async()=>{
 const calls=[],p=page(async(url,options)=>{calls.push([url,options]);return response({maxFps:30,minFps:10,maxAllowedFps:30,step:5});});
 p.settings.setPaired('phone-token');await turn();p.input.value='15';p.listeners.input();
 assert.equal(calls.length,1);assert.equal(p.output.textContent,'15 fps');
 p.listeners.change();await turn();assert.equal(calls.length,2);assert.equal(calls[1][1].method,'POST');
 assert.deepEqual(JSON.parse(calls[1][1].body),{maxFps:15});assert.equal(p.status.textContent,'Saved · 30 fps.');
});
test('a failed write re-reads the canonical phone value before reporting a save',async()=>{
 let writes=0;const p=page(async(url,options)=>options?.method==='POST'?(writes++,response({error:'offline'},false)):response({maxFps:30}));
 p.settings.setPaired('phone-token');await turn();p.input.value='20';p.listeners.input();p.listeners.change();await turn();
 assert.equal(writes,1);assert.equal(p.input.value,'30');assert.equal(p.output.textContent,'30 fps');assert.match(p.status.textContent,/30 fps on your phone/);
});
test('unpaired launcher keeps the default visible but disabled with a connection hint',()=>{
 const p=page(async()=>{throw new Error('unexpected request');});
 assert.equal(p.input.disabled,true);assert.equal(p.output.textContent,'30 fps');assert.match(p.status.textContent,/Connect your phone/);
});
test('rapid changes serialize writes and the latest selection wins',async()=>{
 let finishFirst;const bodies=[];const p=page(async(url,options)=>{
  if(options?.method!=='POST')return response({maxFps:30});
  const fps=JSON.parse(options.body).maxFps;bodies.push(fps);
  if(bodies.length===1)return new Promise(resolve=>finishFirst=()=>resolve(response({maxFps:15})));
  return response({maxFps:fps});
 });
 p.settings.setPaired('phone-token');await turn();p.input.value='15';p.listeners.input();p.listeners.change();
 p.input.value='20';p.listeners.input();p.listeners.change();finishFirst();await turn();await turn();
 assert.deepEqual(bodies,[15,20]);assert.equal(p.input.value,'20');assert.match(p.status.textContent,/Saved · 20 fps/);
});
test('an old save acknowledgement does not overwrite a drag preview',async()=>{
 let finishFirst;const bodies=[];const p=page(async(url,options)=>{
  if(options?.method!=='POST')return response({maxFps:30});
  const fps=JSON.parse(options.body).maxFps;bodies.push(fps);
  if(bodies.length===1)return new Promise(resolve=>finishFirst=()=>resolve(response({maxFps:15})));
  return response({maxFps:fps});
 });
 p.settings.setPaired('phone-token');await turn();p.input.value='15';p.listeners.input();p.listeners.change();
 p.input.value='20';p.listeners.input();finishFirst();await turn();
 assert.equal(p.input.value,'20');assert.equal(p.output.textContent,'20 fps');assert.match(p.status.textContent,/Release to save/);
 p.listeners.change();await turn();assert.deepEqual(bodies,[15,20]);assert.equal(p.input.value,'20');
});
test('a token change aborts and ignores a stale response body',async()=>{
 let finishOld;const p=page(async(url,options)=>{
  if(options.headers.Authorization==='Bearer old-token')return {ok:true,json:()=>new Promise(resolve=>finishOld=()=>resolve({maxFps:15}))};
  return response({maxFps:20});
 });
 p.settings.setPaired('old-token');p.settings.setPaired('new-token');await turn();
 assert.equal(p.input.value,'20');assert.match(p.status.textContent,/20 fps/);
 finishOld();await turn();assert.equal(p.input.value,'20');assert.match(p.status.textContent,/20 fps/);
});
test('a stalled request times out instead of leaving the control loading',async()=>{
 const p=page(()=>new Promise(()=>{}),10);p.settings.setPaired('phone-token');
 await new Promise(resolve=>setTimeout(resolve,30));
 assert.equal(p.input.disabled,true);assert.match(p.status.textContent,/Could not load frame rate/);
});
test('an ambiguous timed out write refreshes the phone canonical value',async()=>{
 let reads=0;const p=page(async(url,options)=>{
  if(options?.method==='POST')return new Promise(()=>{});
  reads++;return response({maxFps:reads===1?30:20});
 },10);
 p.settings.setPaired('phone-token');await turn();p.input.value='15';p.listeners.input();p.listeners.change();
 await new Promise(resolve=>setTimeout(resolve,35));await turn();
 assert.equal(reads,2);assert.equal(p.input.value,'20');assert.match(p.status.textContent,/20 fps on your phone/);
});
test('launcher range has a proper label and stays outside the AA link',()=>{
 const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/web/index.html'),'utf8');
 assert.match(html,/<label id="display-settings-title" for="display-fps">Frame rate<\/label>/);
 assert.match(html,/<input id="display-fps" type="range" min="10" max="30" step="5" value="30"[^>]*aria-describedby=/);
 const aaStart=html.indexOf('<a id="open-aa"'),aaEnd=html.indexOf('</a>',aaStart),settings=html.indexOf('id="display-settings"');
 assert.ok(settings>aaEnd,'settings control must not activate the Android Auto link');
});
