// SPDX-License-Identifier: GPL-3.0-or-later
(function(root){
 'use strict';
 const finite=n=>typeof n==='number'&&Number.isFinite(n);
 function condition(code,isDay=true){
  if(code===0)return {text:isDay?'Clear skies':'Clear night',sky:'clear'};
  if(code===1)return {text:'Mostly clear',sky:'clear'};
  if(code===2)return {text:'Partly cloudy',sky:'cloud'};
  if(code===3)return {text:'Overcast',sky:'cloud'};
  if([45,48].includes(code))return {text:'Foggy',sky:'fog'};
  if([51,53,55,56,57].includes(code))return {text:'Drizzle',sky:'rain'};
  if([61,63,65,66,67,80,81,82].includes(code))return {text:'Rain',sky:'rain'};
  if([71,73,75,77,85,86].includes(code))return {text:'Snow',sky:'snow'};
  if([95,96,99].includes(code))return {text:'Thunderstorms',sky:'storm'};
  return {text:'Conditions unavailable',sky:'cloud'};
 }
 const temperature=(n,unit='C')=>finite(n)?Math.round(unit==='F'?n*9/5+32:n)+'°':'—';
 function place(snapshot,now=Date.now()){
  if(snapshot?.locationFresh!==true||typeof snapshot.locationName!=='string')return 'Finding your location';
  const name=snapshot.locationName.trim(),at=typeof snapshot.locationAt==='number'?snapshot.locationAt:Date.parse(snapshot.locationAt);
  return name&&Number.isFinite(at)&&now>=at&&now-at<=120000?name:'Finding your location';
 }
 function localTime(time,zone){
  try{
   const parts=new Intl.DateTimeFormat('en-CA',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(new Date(time));
   const p=Object.fromEntries(parts.map(x=>[x.type,x.value]));return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;
  }catch(_){return new Date(time).toISOString().slice(0,16);}
 }
 function describe(snapshot,unit='C',now=Date.now()){
  const f=snapshot?.forecast;if(!f?.current||!f.daily||!f.hourly)return null;
  const c=f.current,local=localTime(now,f.timezone),day=local.slice(0,10),days=f.daily.time||[];
  const index=days.indexOf(day),today=index>=0,di=today?index:0;
  const high=f.daily.temperature_2m_max?.[di],low=f.daily.temperature_2m_min?.[di],precip=f.daily.precipitation_probability_max?.[di];
  const currentDay=typeof c.time==='string'?c.time.slice(0,10):'',stale=!!snapshot.stale||!!snapshot.error||currentDay!==day||now-(snapshot.fetchedAt||0)>45*60000;
  const state=condition(c.weather_code,c.is_day!==0);
  const summary=[state.text+(finite(c.apparent_temperature)?`, feeling like ${temperature(c.apparent_temperature,unit)}.`:'.')];
  if(today&&finite(high)&&finite(low))summary.push(`Today’s high is ${temperature(high,unit)}, with a low of ${temperature(low,unit)}.`);
  if(today&&finite(precip))summary.push(`${Math.round(precip)}% chance of precipitation.`);
  const times=f.hourly.time||[],hours=[];
  const start=local.slice(0,13)+':00';
  for(let i=0;i<times.length&&hours.length<6;i++){
   if(typeof times[i]!=='string'||times[i]<start||!finite(f.hourly.temperature_2m?.[i]))continue;
   if(hours.length&&Date.parse(times[i]+'Z')-Date.parse(hours[hours.length-1].time+'Z')<2*3600000)continue;
   const n=f.hourly.temperature_2m[i];hours.push({time:times[i],label:times[i].slice(11,16),temperature:unit==='F'?n*9/5+32:n,text:temperature(n,unit)});
  }
  const wind=finite(c.wind_speed_10m)?Math.round(c.wind_speed_10m*(unit==='F'?.621371:1))+(unit==='F'?' mph':' km/h'):'—';
  return {condition:state.text,sky:state.sky,night:c.is_day===0,temperature:temperature(c.temperature_2m,unit),highLow:today?`H ${temperature(high,unit)}  ·  L ${temperature(low,unit)}`:'Saved forecast',summary:summary.join(' '),feels:temperature(c.apparent_temperature,unit),wind,humidity:finite(c.relative_humidity_2m)?Math.round(c.relative_humidity_2m)+'%':'—',precip:today&&finite(precip)?Math.round(precip)+'%':'—',hours,stale,today:today&&currentDay===day,day:currentDay,timezone:f.timezone};
 }
 const api={condition,temperature,localTime,describe,place};
 if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.LumaWeather=api;
})(globalThis);
