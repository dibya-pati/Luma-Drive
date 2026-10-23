// SPDX-License-Identifier: GPL-3.0-or-later
const PcmCodec = (() => {
  const crcTable = Uint32Array.from({length:256}, (_, i) => { let n=i; for(let j=0;j<8;j++) n=n&1 ? 0xedb88320^(n>>>1) : n>>>1; return n>>>0; });
  function crc32(bytes) { let crc=0xffffffff; for (const byte of bytes) crc=crcTable[(crc^byte)&255]^(crc>>>8); return (crc^0xffffffff)>>>0; }
  class Decoder {
    constructor() { this.carry = new Uint8Array(); this.format = ''; }
    decode(bytes, bits, channels) {
      if (![16,24].includes(bits) || ![1,2].includes(channels)) throw new Error('Unsupported PCM format');
      const format = `${bits}/${channels}`;
      if (format !== this.format) { this.carry = new Uint8Array(); this.format = format; }
      const all = new Uint8Array(this.carry.length + bytes.length); all.set(this.carry); all.set(bytes, this.carry.length);
      const stride=bits/8, count=Math.floor(all.length/(stride*channels)), used=count*stride*channels;
      this.carry=all.slice(used);
      const output=Array.from({length:channels},()=>new Float32Array(count));
      for(let f=0;f<count;f++) for(let c=0;c<channels;c++) {
        const p=(f*channels+c)*stride;
        let n=all[p]|(all[p+1]<<8);
        if(bits===16) { if(n&0x8000)n-=0x10000; output[c][f]=n/32768; }
        else { n|=all[p+2]<<16; if(n&0x800000)n-=0x1000000; output[c][f]=n/8388608; }
      }
      return output;
    }
  }
  return {crc32, Decoder};
})();
if (typeof module !== 'undefined') module.exports = PcmCodec;
