// SPDX-License-Identifier: GPL-3.0-or-later
(function(root){
 'use strict';
 const read=()=>{let value='';try{value=localStorage.getItem('projectionToken')||'';}catch(_){}if(value)return value;try{return sessionStorage.getItem('projectionToken')||'';}catch(_){return '';}};
 function remember(value,link){
  let stored=false;
  try{if(value)localStorage.setItem('projectionToken',value);else localStorage.removeItem('projectionToken');sessionStorage.removeItem('projectionToken');stored=true;}catch(_){}
  if(link)link.href=value&&!stored?'/aa#token='+encodeURIComponent(value):'/aa';
 }
 function acceptIncoming(link){
  const incoming=new URLSearchParams(location.hash.slice(1)).get('token')||new URLSearchParams(location.search).get('token');
  if(!incoming)return '';
  remember(incoming,link);history.replaceState(null,'',location.pathname);return incoming;
 }
 root.LumaPairing={read,remember,acceptIncoming};
})(globalThis);
