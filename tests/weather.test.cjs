// SPDX-License-Identifier: GPL-3.0-or-later
const test=require('node:test'),assert=require('node:assert/strict');
const Weather=require('../app/src/main/assets/web/weather.js');
const now=Date.parse('2026-10-19T02:00:00Z');
function fixture(){return {fetchedAt:now,locationAt:now,forecast:{timezone:'America/Los_Angeles',current:{time:'2026-10-18T19:00',temperature_2m:20,apparent_temperature:19,weather_code:2,is_day:0,wind_speed_10m:16,relative_humidity_2m:64},daily:{time:['2026-10-18','2026-10-19'],temperature_2m_max:[24,22],temperature_2m_min:[13,12],precipitation_probability_max:[10,30]},hourly:{time:Array.from({length:24},(_,i)=>`2026-10-${i<5?'18':'19'}T${String((i+19)%24).padStart(2,'0')}:00`),temperature_2m:Array.from({length:24},(_,i)=>20-i*.2)}}};}
test('weather uses the phone location time zone across UTC day boundaries',()=>{const view=Weather.describe(fixture(),'C',now);assert.equal(view.today,true);assert.equal(view.stale,false);assert.equal(view.hours[0].time,'2026-10-18T19:00');assert.equal(view.hours.length,6);assert.match(view.highLow,/24°.*13°/);});
test('stale previous-day weather cannot claim to be today or show expired hours',()=>{const old=fixture(),view=Weather.describe(old,'C',now+3*86400000);assert.equal(view.today,false);assert.equal(view.stale,true);assert.equal(view.hours.length,0);assert.equal(view.precip,'—');assert.doesNotMatch(view.summary,/Today/);});
test('null weather values remain unavailable rather than becoming zero degrees',()=>{const data=fixture();data.forecast.current.temperature_2m=null;data.forecast.current.wind_speed_10m=null;data.forecast.daily.precipitation_probability_max[0]=null;const view=Weather.describe(data,'C',now);assert.equal(view.temperature,'—');assert.equal(view.wind,'—');assert.equal(view.precip,'—');assert.doesNotMatch(view.summary,/0%/);});
test('temperature and wind units convert together',()=>{const view=Weather.describe(fixture(),'F',now);assert.equal(view.temperature,'68°');assert.equal(view.wind,'10 mph');assert.equal(view.hours[0].text,'68°');});
test('provider failure marks saved data stale, and unknown codes never invent clear skies',()=>{const data=fixture();data.error='Weather could not update';data.forecast.current.weather_code=999;const view=Weather.describe(data,'C',now);assert.equal(view.stale,true);assert.equal(view.condition,'Conditions unavailable');});
test('place names require a fresh phone location and expire after two minutes',()=>{
 const data={locationFresh:true,locationName:' Portland ',locationAt:now};
 assert.equal(Weather.place(data,now),'Portland');
 assert.equal(Weather.place(data,now+120001),'Finding your location');
 assert.equal(Weather.place({...data,locationFresh:false},now),'Finding your location');
 assert.equal(Weather.place({...data,locationAt:now+1},now),'Finding your location');
});
