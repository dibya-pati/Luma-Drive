# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('route',Path(__file__).resolve().parents[1]/'tools/summarize_audio_route.py')
route=importlib.util.module_from_spec(spec);spec.loader.exec_module(route)
class RouteTests(unittest.TestCase):
    def test_capture_and_playback_are_distinct(self):
        text='''Clients:
  2001  1001  com.google.android.projection.gearhead
  2002  1002  com.amazon.mp3
Input thread example:
       yes 10 2001/ 1001 1 2 A 0x000 00000001 0000000C 48000 8
Output thread example:
           11 yes 2002/ 1002 3 4 A 0x000 00000005 00000003 48000 1
'''
        result=route.summarize(text)['activeTracks']
        self.assertEqual(['capture','playback'],[x['direction'] for x in result])
        self.assertEqual(['PCM_16_BIT','PCM_FLOAT'],[x['format'] for x in result])
        self.assertEqual([2,2],[x['channels'] for x in result])
        self.assertNotIn('2001',str(result))
    def test_historical_or_inactive_data_is_excluded(self):
        text='''2001 1001 com.google.android.projection.gearhead
Input thread example:
no 10 2001/ 1001 1 2 A 0x000 00000001 0000000C 48000 8
Historical Thread Log
Input thread old:
yes 10 2001/ 1001 1 2 A 0x000 00000001 0000000C 48000 8
'''
        self.assertEqual([],route.summarize(text)['activeTracks'])
    def test_unrelated_apps_are_excluded(self):
        text='''2003 1003 com.example.other
Input thread example:
yes 10 2003/ 1003 1 2 A 0x000 00000001 0000000C 48000 8
'''
        self.assertEqual([],route.summarize(text)['activeTracks'])
if __name__ == '__main__':unittest.main()
