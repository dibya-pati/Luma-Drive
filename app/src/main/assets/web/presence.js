// SPDX-License-Identifier: GPL-3.0-or-later
(function(root){
 'use strict';
 function makeId(){
  const bytes=new Uint8Array(16);
  try{root.crypto.getRandomValues(bytes);return Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');}
  catch(_){return Array.from({length:32},()=>Math.floor(Math.random()*16).toString(16)).join('');}
 }
 const id=makeId();let getToken=()=>'',timer=null,stopped=true,pending=false,queued=null,controller=null,generation=0;
 function dispatch(state,keepalive,replacePending=false){
  let token='';try{token=getToken()||'';}catch(_){}
  if(!token)return;
  if(pending&&!replacePending){queued={state,keepalive};return;}
  const requestId=++generation;pending=true;
  const path='/api/presence?client='+encodeURIComponent(id)+'&state='+state;
  const aborter=typeof root.AbortController==='function'?new root.AbortController():null;controller=aborter;
  const timeout=root.setTimeout(()=>aborter?.abort(),4000);
  let request;
  try{
   request=root.fetch(path,{method:'POST',headers:{Authorization:'Bearer '+token},cache:'no-store',keepalive:!!keepalive,...(aborter?{signal:aborter.signal}:{})});
  }catch(_){request=Promise.resolve();}
  Promise.resolve(request).catch(()=>{}).finally(()=>{
   root.clearTimeout(timeout);if(requestId!==generation)return;pending=false;controller=null;
   const next=queued;queued=null;
   if(next){if(next.state==='left'&&stopped)dispatch(next.state,next.keepalive);else if(next.state==='seen'&&!stopped)dispatch(next.state,next.keepalive);}
  });
 }
 function start(tokenProvider){
  if(typeof tokenProvider==='function')getToken=tokenProvider;
  if(!stopped)return id;
  stopped=false;
  dispatch('seen',false);
  timer=root.setInterval(()=>{if(!stopped)dispatch('seen',false);},5000);
  return id;
 }
 function ping(){if(!stopped)dispatch('seen',false);}
 function leave(){
  if(stopped)return;
  stopped=true;
  if(timer!==null){root.clearInterval(timer);timer=null;}
  controller?.abort();
  queued=null;dispatch('left',true,true);
 }
 root.addEventListener?.('pagehide',leave);
 root.addEventListener?.('pageshow',()=>{if(!stopped)return;stopped=false;dispatch('seen',false);timer=root.setInterval(()=>{if(!stopped)dispatch('seen',false);},5000);});
 root.LumaPresence={id,start,ping};
})(globalThis);
