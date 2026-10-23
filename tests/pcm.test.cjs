// SPDX-License-Identifier: GPL-3.0-or-later
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {Decoder,crc32} = require('../app/src/main/assets/web/pcm.js');
test('signed 16-bit extremes',()=>{
 const [samples]=new Decoder().decode(Uint8Array.from([0,128,0,0,255,127]),16,1);
 assert.deepEqual([...samples],[-1,0,32767/32768]);
});
test('packed 24-bit extremes and stereo order',()=>{
 const samples=new Decoder().decode(Uint8Array.from([0,0,128,255,255,127,0,0,0,1,0,0]),24,2);
 assert.deepEqual([...samples[0]],[-1,0]);
 assert.deepEqual([...samples[1]],[8388607/8388608,1/8388608]);
});
test('split stereo frame survives packet boundary',()=>{
 const d=new Decoder();
 assert.equal(d.decode(Uint8Array.from([0,128,255]),16,2)[0].length,0);
 const samples=d.decode(Uint8Array.from([127]),16,2);
 assert.deepEqual([...samples[0]],[-1]); assert.deepEqual([...samples[1]],[32767/32768]);
});
test('format changes discard incompatible carry',()=>{
 const d=new Decoder();d.decode(Uint8Array.from([7]),16,1);
 assert.deepEqual([...d.decode(Uint8Array.from([0,0,0]),24,1)[0]],[0]);
});
test('CRC32 matches standard check vector',()=>assert.equal(crc32(Buffer.from('123456789')),0xcbf43926));
test('unsupported formats fail explicitly',()=>assert.throws(()=>new Decoder().decode(new Uint8Array(),32,2)));
test('PCM16 conversion to Float32 preserves every signed sample exactly',()=>{
 const input=new Uint8Array(65536*2);
 for(let i=0;i<65536;i++){input[i*2]=i&255;input[i*2+1]=i>>>8;}
 const [samples]=new Decoder().decode(input,16,1);
 for(let i=0;i<65536;i++)assert.equal(Math.round(samples[i]*32768),i>=32768?i-65536:i);
});
