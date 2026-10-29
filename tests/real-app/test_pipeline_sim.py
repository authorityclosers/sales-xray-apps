import importlib.util
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('sim', ROOT / 'scripts/pipeline_sim.py')
sim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sim)


class PipelineSimTests(unittest.TestCase):
    def test_the_fictional_lead_is_the_same_every_night(self):
        self.assertEqual(sim.lead_command('t', '+1 202 555 0142'), sim.lead_command('t', '+12025550142'))
        self.assertNotEqual(sim.lead_command('t', '+12025550142'), sim.lead_command('u', '+12025550142'))

    def test_workspace_is_chosen_by_name_or_id_never_by_accident(self):
        choices = [{'tenant_id': 'p', 'name': 'Personal'}, {'tenant_id': 'a', 'name': 'Authority Closers'}]
        self.assertEqual(sim.pick_workspace(choices, 'authority closers')['tenant_id'], 'a')
        self.assertEqual(sim.pick_workspace(choices, 'a')['name'], 'Authority Closers')
        self.assertIsNone(sim.pick_workspace(choices, 'Example Sales'))
        self.assertIsNone(sim.pick_workspace(choices, ''))

    def test_minutes_cover_the_clip(self):
        self.assertTrue(sim.minutes_left({'available_seconds': 60}, 45))
        self.assertFalse(sim.minutes_left({'available_seconds': 30}, 45))
        self.assertTrue(sim.minutes_left({'available_seconds': 0, 'unlimited': True}, 45))
        self.assertFalse(sim.minutes_left(None, 45))

    def test_refusal_words_never_include_echoed_input(self):
        self.assertEqual(sim.detail({'detail': 'Your current free call allowance has been used.'}),
                         'Your current free call allowance has been used.')
        self.assertEqual(sim.detail({'detail': [{'input': {'password': 'x'}}]}), '')

    def test_the_call_is_ours_only_on_the_lead_and_after_the_dial(self):
        since = datetime(2026, 10, 30, 2, 0, tzinfo=timezone.utc)
        calls = [
            {'lead_id': 'other', 'at': '2026-10-30T02:05:00Z', 'state': 'waiting'},
            {'lead_id': 'lead', 'at': '2026-10-30T01:55:00+00:00', 'state': 'report_ready'},
            {'lead_id': 'lead', 'at': '2026-10-30T02:03:00+00:00', 'state': 'uploaded'},
        ]
        self.assertEqual(sim.find_call(calls, 'lead', since)['state'], 'uploaded')
        self.assertIsNone(sim.find_call(calls[:2], 'lead', since))
        self.assertEqual(sim.server_stage({'state': 'uploaded', 'attention': True}), 'analysis_failed')

    def test_the_phone_queue_reads_from_the_app_preferences(self):
        xml = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map>'
               '<boolean name="on" value="true" />'
               '<string name="job:1">{&quot;id&quot;:&quot;1&quot;,&quot;state&quot;:&quot;held&quot;,'
               '&quot;hold&quot;:&quot;allowance&quot;,&quot;name&quot;:&quot;Pipeline Sim(0012025550142)_20261030020000.mp3&quot;}</string>'
               '<string name="told:allowance">x</string></map>')
        jobs = sim.device_jobs(xml)
        self.assertEqual(len(jobs), 1)
        self.assertEqual((jobs[0]['state'], jobs[0]['hold']), ('held', 'allowance'))

    def test_poll_counts_a_skipped_stage_as_reached_and_names_a_stuck_one(self):
        answers = iter([
            (200, {'calls': [{'lead_id': 'lead', 'at': '2026-10-30T02:03:00Z', 'state': 'waiting'}]}),
            (200, {'calls': [{'lead_id': 'lead', 'at': '2026-10-30T02:03:00Z', 'state': 'analysing'}]}),
        ])
        args = SimpleNamespace(state='s', jar='j', since=0, until='uploaded', stage='uploaded', timeout=60, every=0)
        with patch.object(sim, 'open', create=True), patch.object(sim.json, 'load', return_value={'base': 'b', 'lead_id': 'lead'}), \
                patch.object(sim.Session, '__init__', return_value=None), \
                patch.object(sim.Session, 'request', side_effect=lambda *a, **k: next(answers)), \
                patch('builtins.print'):
            sim.poll(args)
        missing = iter([(200, {'calls': [{'lead_id': 'lead', 'at': '2026-10-30T02:03:00Z', 'state': 'missing'}]})])
        with patch.object(sim, 'open', create=True), patch.object(sim.json, 'load', return_value={'base': 'b', 'lead_id': 'lead'}), \
                patch.object(sim.Session, '__init__', return_value=None), \
                patch.object(sim.Session, 'request', side_effect=lambda *a, **k: next(missing)), \
                patch('builtins.print'):
            with self.assertRaises(sim.Stuck) as stuck:
                sim.poll(args)
        self.assertEqual(stuck.exception.stage, 'recorded')

    def test_prepare_refuses_without_the_staging_login(self):
        args = SimpleNamespace(base='https://example.invalid', jar='j', cookies='c', workspace='Authority Closers',
                               lead_number='+12025550142', lead_name='x', clip_seconds=45)
        with patch.dict(sim.os.environ, {'SX_SIM_EMAIL': '', 'SX_SIM_PASSWORD': ''}):
            with self.assertRaises(sim.Stuck) as stuck:
                sim.prepare(args)
        self.assertEqual(stuck.exception.stage, 'sign_in')


if __name__ == '__main__':
    unittest.main()
