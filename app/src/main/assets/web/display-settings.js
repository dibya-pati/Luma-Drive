/* SPDX-License-Identifier: GPL-3.0-or-later */
'use strict';
(function(global){
 const choices=[10,15,20,25,30];
 function create({input,output,status,fetchImpl=global.fetch.bind(global),timeoutMs=7000}){
  let token='',generation=0,readId=0,confirmed=null,selected=30,ready=false;
  let previewRevision=0,version=0,running=false,savePending=false,refreshPending=false;
  let activeGet=null,activePost=null;
  const valid=value=>choices.includes(Number(value));
  function paint(){output.value=`${selected} fps`;output.textContent=`${selected} fps`;input.value=String(selected);input.disabled=!ready;}
  function message(text){status.textContent=text;}
  function abortController(controller){try{controller?.abort();}catch(_){}}
  async function requestJson(url,options,setActive){
   const controller=new global.AbortController();setActive(controller);
   let rejectAbort;
   const aborted=new Promise((_,reject)=>{rejectAbort=reject;});
   const onAbort=()=>rejectAbort(new Error('Request timed out or pairing changed.'));
   controller.signal.addEventListener('abort',onAbort,{once:true});
   const timer=global.setTimeout(()=>controller.abort(),timeoutMs);
   try{
    const operation=(async()=>{
     const response=await fetchImpl(url,{...options,signal:controller.signal});
     if(!response.ok)throw new Error('Display settings request failed.');
     return await response.json();
    })();
    return await Promise.race([operation,aborted]);
   }finally{
    global.clearTimeout(timer);controller.signal.removeEventListener('abort',onAbort);
    setActive(null,controller);
   }
  }
  async function refresh(){
   const requestToken=token,requestGeneration=generation,requestId=++readId,requestPreview=previewRevision;
   if(!requestToken){ready=false;paint();message('Connect your phone to adjust frame rate.');return;}
   if(running){refreshPending=true;return;}
   abortController(activeGet);
   message('Loading frame rate…');
   try{
    const data=await requestJson('/api/display',{headers:{Authorization:'Bearer '+requestToken},cache:'no-store'},(controller,finished)=>{if(!finished||activeGet===finished)activeGet=controller;});
    if(requestGeneration!==generation||requestId!==readId||requestToken!==token)return;
    if(!valid(data.maxFps))throw new Error('Invalid display settings.');
    confirmed=Number(data.maxFps);ready=true;
    if(requestPreview===previewRevision){selected=confirmed;paint();message(`${confirmed} fps on your phone.`);}
    else message('Release to keep your frame rate change.');
   }catch(_){
    if(requestGeneration!==generation||requestId!==readId||requestToken!==token)return;
    confirmed=null;ready=false;paint();message('Could not load frame rate. Try again when your phone is available.');
   }
  }
  async function saveLatest(){
   if(running){savePending=true;return;}
   if(!ready||!token)return;
   running=true;
   try{
    while(ready&&token){
     savePending=false;
     const requestToken=token,requestGeneration=generation,requestVersion=version;
     const requestPreview=previewRevision,value=Number(input.value);
     if(!valid(value)){selected=confirmed??30;paint();return;}
     selected=value;++readId;abortController(activeGet);message('Saving…');
     try{
      const data=await requestJson('/api/display',{method:'POST',headers:{Authorization:'Bearer '+requestToken,'Content-Type':'application/json'},body:JSON.stringify({maxFps:value})},(controller,finished)=>{if(!finished||activePost===finished)activePost=controller;});
      if(requestGeneration!==generation||requestToken!==token)return;
      if(!valid(data.maxFps))throw new Error('Invalid display settings.');
      confirmed=Number(data.maxFps);
      if(requestVersion!==version)continue;
      if(requestPreview!==previewRevision){message('Release to save your frame rate change.');return;}
      selected=confirmed;paint();message(`Saved · ${confirmed} fps.`);return;
     }catch(_){
      if(requestGeneration!==generation||requestToken!==token)return;
      if(requestVersion!==version)continue;
      // The server may have committed before the connection failed. Read its
      // canonical value before showing a saved selection or accepting another.
      ready=false;input.disabled=true;message('Could not confirm the save. Checking the phone…');
      refreshPending=true;return;
     }
    }
   }finally{
    running=false;
    if(savePending){savePending=false;saveLatest();}
    else if(refreshPending){refreshPending=false;refresh();}
   }
  }
  input.addEventListener('input',()=>{selected=Number(input.value);previewRevision++;output.value=`${selected} fps`;output.textContent=`${selected} fps`;});
  input.addEventListener('change',()=>{if(!ready)return;selected=Number(input.value);previewRevision++;version++;message('Saving…');saveLatest();});
  function setPaired(nextToken){
   const next=String(nextToken||'');if(next===token){if(next)refresh();return;}
   abortController(activeGet);abortController(activePost);
   token=next;generation++;readId++;version++;previewRevision++;confirmed=null;selected=30;ready=false;
   savePending=false;paint();
   if(token)refresh();else message('Connect your phone to adjust frame rate.');
  }
  return {setPaired,refresh};
 }
 global.LumaDisplaySettings={create};
})(globalThis);
