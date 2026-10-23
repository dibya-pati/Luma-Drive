// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';
const $=id=>document.getElementById(id),appLink=$('open-aa');
let token=LumaPairing.acceptIncoming(appLink)||LumaPairing.read(),snapshot=null,pendingOpen=false,timer=null,polling=false,zone;
globalThis.LumaPresence?.start(()=>token);
const displaySettings=LumaDisplaySettings.create({input:$('display-fps'),output:$('display-fps-value'),status:$('display-fps-status')});
displaySettings.setPaired(token);
const colorPreference=window.matchMedia?.('(prefers-color-scheme: light)');let phoneThemeKnown=false;
function applyTheme(theme){if(theme==='light'||theme==='dark'){phoneThemeKnown=true;document.documentElement.dataset.theme=theme;}else if(!phoneThemeKnown)document.documentElement.dataset.theme=colorPreference?.matches?'light':'dark';const meta=$('theme-color');if(meta)meta.content=document.documentElement.dataset.theme==='light'?'#f4f6f1':'#0c120f';}
applyTheme();colorPreference?.addEventListener?.('change',()=>applyTheme());
let unit;try{unit=localStorage.getItem('lumaWeatherUnit');}catch(_){}
if(!['C','F'].includes(unit))unit=navigator.language==='en-US'?'F':'C';
function clock(){
 const now=new Date(),options=zone?{timeZone:zone}:{};
 try{
  $('clock').textContent=new Intl.DateTimeFormat(undefined,{...options,hour:'numeric',minute:'2-digit'}).format(now);
  $('date').textContent=new Intl.DateTimeFormat(undefined,{...options,weekday:'short',month:'short',day:'numeric'}).format(now);
  const hour=Number(new Intl.DateTimeFormat('en-GB',{...options,hour:'2-digit',hourCycle:'h23'}).format(now));
  $('greeting').textContent=hour<12?'Good morning.':hour<18?'Good afternoon.':'Good evening.';
 }catch(_){zone=undefined;}
}
function connection(text,ready=false){$('connection').textContent=text;$('connection').classList.toggle('ready',ready);$('pair-phone').hidden=ready;}
function openPair(openApp=false){pendingOpen=openApp;$('pair-status').textContent='The code is shown in the Luma app.';$('pair-status').classList.remove('error');$('pair-dialog').showModal();$('pair-code').focus();}
$('pair-phone').onclick=()=>openPair();$('close-pair').onclick=()=>{$('pair-dialog').close();pendingOpen=false;};
$('pair-dialog').addEventListener('cancel',()=>{pendingOpen=false;});
appLink.addEventListener('click',event=>{if(!token){event.preventDefault();openPair(true);}else{appLink.setAttribute('aria-busy','true');appLink.querySelector('.card-action').firstChild.textContent='Opening Android Auto ';}});
window.addEventListener('pageshow',()=>{appLink.removeAttribute('aria-busy');appLink.querySelector('.card-action').firstChild.textContent='Open Android Auto ';displaySettings.refresh();});
$('pair-form').addEventListener('submit',async event=>{
 event.preventDefault();const code=$('pair-code').value.trim();if(!/^[0-9]{6}$/.test(code))return;
 $('pair-submit').disabled=true;$('pair-submit').textContent='Connecting…';$('pair-status').textContent='Connecting to your phone…';$('pair-status').classList.remove('error');
 try{
  const response=await fetch('/api/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code}),signal:AbortSignal.timeout(6000)});
  const result=await response.json();if(!response.ok||!result.token)throw new Error(result.error||'Open a new pairing code on your phone.');
  token=result.token;snapshot=null;phoneThemeKnown=false;applyTheme();LumaPairing.remember(token,appLink);displaySettings.setPaired(token);$('pair-code').value='';$('pair-dialog').close();connection('Phone connected',true);globalThis.LumaPresence?.ping();render();
  if(pendingOpen){pendingOpen=false;location.assign(appLink.href);return;}refresh();
 }catch(error){$('pair-status').textContent=error.name==='TimeoutError'?'Phone did not respond. Check the connection and try again.':error.message||'Could not connect. Try again.';$('pair-status').classList.add('error');}
 finally{$('pair-submit').disabled=false;$('pair-submit').textContent='Connect phone';}
});
function empty(title,copy,action){
 $('weather-content').hidden=true;$('weather-empty').hidden=false;$('weather-empty-title').textContent=title;$('weather-empty-copy').textContent=copy;
 $('weather-setup').hidden=!action;$('weather-setup').textContent=action||'';$('units').hidden=true;
 $('weather-title').textContent='LOCAL WEATHER';$('weather-freshness').textContent='Weather comes from your phone.';
}
$('weather-setup').onclick=()=>{if(!token)openPair();else refresh();};
function drawHours(hours){
 const root=$('hourly');root.replaceChildren();
 if(!hours.length){const p=document.createElement('p');p.textContent='Hourly forecast unavailable. Waiting for an update.';root.append(p);return;}
 const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');const width=Math.max(280,root.clientWidth||600),height=parseFloat(getComputedStyle(root).getPropertyValue('--chart-height'))||118;svg.setAttribute('viewBox',`0 0 ${width} ${height}`);svg.setAttribute('role','img');svg.setAttribute('aria-label',hours.map(h=>`${h.label}: ${h.text}`).join(', '));
 const min=Math.min(...hours.map(h=>h.temperature)),max=Math.max(...hours.map(h=>h.temperature)),range=Math.max(max-min,4);
 const points=hours.map((h,i)=>({x:24+i*(width-48)/Math.max(hours.length-1,1),y:height-30-(h.temperature-min)/range*Math.max(10,height-56),h}));
 const line=document.createElementNS(ns,'polyline');line.setAttribute('points',points.map(p=>`${p.x},${p.y}`).join(' '));line.setAttribute('class','curve');svg.append(line);
 for(const p of points){const dot=document.createElementNS(ns,'circle');dot.setAttribute('cx',p.x);dot.setAttribute('cy',p.y);dot.setAttribute('r',3);dot.setAttribute('class','point');svg.append(dot);
  for(const [text,y,cl] of [[p.h.text,p.y-13,'temp-label'],[p.h.label,height-7,'hour-label']]){const el=document.createElementNS(ns,'text');el.textContent=text;el.setAttribute('x',p.x);el.setAttribute('y',y);el.setAttribute('text-anchor','middle');el.setAttribute('class',cl);svg.append(el);}
 }root.append(svg);
}
function render(){
 applyTheme(snapshot?.theme);$('weather-location').textContent=LumaWeather.place(snapshot);
 $('units').textContent=unit==='C'?'°C  /  °F':'°F  /  °C';$('units').setAttribute('aria-label',`Showing degrees ${unit==='C'?'Celsius':'Fahrenheit'}. Switch units`);
 if(!token){connection('Pair your phone');empty('Your day, at a glance.','Connect your phone for local weather and a look at the hours ahead.','Connect phone');return;}
 if(!snapshot){empty('Getting your weather…','Checking the forecast on your phone.');return;}
 if(!snapshot.enabled||!snapshot.permission){empty('Weather, wherever you are.','On your phone, open Luma and tap “Enable phone weather.” Allow approximate location to see the forecast here.');return;}
 const view=LumaWeather.describe(snapshot,unit);
 if(!view){empty(snapshot.error?'Weather is unavailable.':'Finding your local weather.',snapshot.error?'The phone needs internet for a fresh forecast. Luma will retry automatically.':snapshot.locationStatus||'Waiting for the phone’s approximate location.');return;}
 $('weather-empty').hidden=true;$('weather-content').hidden=false;$('units').hidden=false;zone=view.timezone;clock();
 $('weather-card').dataset.sky=view.sky;$('weather-card').dataset.night=String(view.night);$('weather-art').setAttribute('aria-label',view.condition);
 $('temperature').textContent=view.temperature.replace('°','');$('temperature-unit').textContent='°'+unit;$('condition').textContent=view.condition;$('high-low').textContent=view.highLow;
 $('weather-summary').textContent=view.summary;$('feels-like').textContent=view.feels;$('wind').textContent=view.wind;$('humidity').textContent=view.humidity;$('precipitation').textContent=view.precip;
 $('weather-title').textContent=view.today?'TODAY’S WEATHER':'SAVED WEATHER';
 $('forecast-note').textContent=view.stale?'Saved forecast':'';drawHours(view.hours);
 const date=new Date(snapshot.fetchedAt),stamp=Number.isFinite(date.getTime())?new Intl.DateTimeFormat(undefined,{month:'short',day:'numeric',hour:'numeric',minute:'2-digit',...(zone?{timeZone:zone}:{})}).format(date):'unknown time';
 $('weather-freshness').textContent=(view.stale?'Saved · ':'Updated ')+stamp+(snapshot.refreshing?' · Refreshing…':snapshot.error?' · Update unavailable':'');
}
$('units').onclick=()=>{unit=unit==='C'?'F':'C';try{localStorage.setItem('lumaWeatherUnit',unit);}catch(_){}render();};
async function refresh(){
 clearTimeout(timer);if(polling)return;if(!token){render();return;}polling=true;const requestToken=token;
 try{
  const response=await fetch('/api/weather',{headers:{Authorization:'Bearer '+token},cache:'no-store',signal:AbortSignal.timeout(6000)});
  if(token!==requestToken)return;
  if(response.status===401){token='';snapshot=null;phoneThemeKnown=false;applyTheme();LumaPairing.remember('',appLink);displaySettings.setPaired('');connection('Pair your phone');render();return;}
  if(!response.ok)throw new Error('Weather unavailable');const result=await response.json();if(token!==requestToken)return;snapshot=result;const count=snapshot.clients?.active;connection('Phone connected'+(typeof count==='number'?' · '+count+' client'+(count===1?'':'s'):''),true);render();
 }catch(_){if(token!==requestToken)return;connection('Phone unreachable');if(snapshot?.forecast){snapshot={...snapshot,stale:true,error:'Phone unreachable',locationFresh:false};render();}else empty('Phone is unavailable.','Check the hotspot connection and keep Luma running on your phone.','Try again');}
 finally{polling=false;if(token)timer=setTimeout(refresh,snapshot?.refreshing?2500:5000);}
}
window.addEventListener('hashchange',()=>{const incoming=LumaPairing.acceptIncoming(appLink);if(incoming){token=incoming;snapshot=null;phoneThemeKnown=false;applyTheme();displaySettings.setPaired(token);render();refresh();}});
window.addEventListener('online',refresh);
window.addEventListener('resize',()=>{const view=LumaWeather.describe(snapshot,unit);if(view)drawHours(view.hours);});
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'){clock();refresh();displaySettings.refresh();}});
clock();setInterval(clock,15000);render();refresh();
