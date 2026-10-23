#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read an AudioFlinger dump; emit app/format facts without process or device identifiers."""
import json
import re
import sys

APPS = {'com.google.android.projection.gearhead', 'com.amazon.mp3'}
FORMATS = {1: 'PCM_16_BIT', 3: 'PCM_32_BIT', 4: 'PCM_8_24_BIT', 5: 'PCM_FLOAT', 6: 'PCM_24_BIT_PACKED'}

def summarize(text):
    clients = {}
    for line in text.splitlines():
        match = re.fullmatch(r'\s*(\d+)\s+(\d+)\s+([A-Za-z0-9_.]+)\s*', line)
        if match and match[3] in APPS:
            clients[(match[1],match[2])] = match[3]
    facts = []
    seen = set()
    active = False
    # Ignore historical track logs; keep the current track tables only.
    for line in text.split('Historical Thread Log',1)[0].splitlines():
        if line.startswith('Input thread'):
            direction = 'capture'; active = True
        elif re.match(r'^(?:Output|Playback|Mixer|Mmap|Duplicating) thread',line):
            direction = 'playback'; active = True
        if not active:
            continue
        prefix = r'\s*(yes|no)\s+\d+' if direction == 'capture' else r'\s*\d+\s+(yes|no)'
        match = re.match(prefix + r'\s+(\d+)/\s*(\d+)\s+\d+\s+\d+\s+\w+\s+0x[0-9a-fA-F]+\s+([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})\s+(\d+)\b',line)
        if not match or match[1] != 'yes':
            continue
        package = clients.get((match[2],match[3]))
        if package is None:
            continue
        fmt,mask,rate = int(match[4],16),int(match[5],16),int(match[6])
        key = (package,direction,fmt,mask,rate)
        if key in seen:
            continue
        seen.add(key)
        facts.append({'app':package,'direction':direction,'format':FORMATS.get(fmt,'UNKNOWN'),
                      'sampleRateHz':rate,'channelMaskHex':hex(mask),'channels':mask.bit_count()})
    return {'activeTracks':facts,'scope':'Current active app tracks only. This does not verify source precision, wire payloads or final speaker output.'}

if __name__ == '__main__':
    print(json.dumps(summarize(sys.stdin.read()),indent=2))
