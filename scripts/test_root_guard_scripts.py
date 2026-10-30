"""Execute the shipped fixed shell scripts against isolated fake Android commands in CI.

No root, Android device, or emulator is used. Real su/SELinux behavior remains a device check.
"""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/guard'


class RootScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        FIXTURE=str(self.root))

    def command(self, name, body):
        path = self.bin / name
        path.write_text('#!/usr/bin/env python3\n' + body)
        path.chmod(0o700)

    def script(self, asset, substitutions):
        text = (ASSETS / asset).read_text()
        for key, value in substitutions.items():
            text = text.replace('__' + key + '__', shlex.quote(str(value)))
        path = self.root / asset
        path.write_text(text)
        return path

    def watchdog(self, mode, enabled=True, fresh=False):
        (self.root / 'runtime').mkdir()
        if enabled:
            (self.root / 'enabled').touch()
        (self.root / 'heartbeat').write_text('1000' if fresh else '0')
        self.env['MODE'] = mode
        self.command('date', 'print(1000)\n')
        self.command('cmd', '''import os
from pathlib import Path
p=Path(os.environ['FIXTURE'])
if os.environ['MODE']=='disable-during-unstop': (p/'enabled').unlink(missing_ok=True)
''')
        self.command('am', '''import os,sys
from pathlib import Path
p=Path(os.environ['FIXTURE'])
with (p/'calls').open('a') as f: f.write(' '.join(sys.argv[1:])+'\\n')
if os.environ['MODE']=='recover': (p/'heartbeat').write_text('1000')
sys.exit(1 if os.environ['MODE']=='fail' else 0)
''')
        self.command('sleep', '''import os
from pathlib import Path
p=Path(os.environ['FIXTURE']); n=p/'ticks'
count=int(n.read_text())+1 if n.exists() else 1; n.write_text(str(count))
if os.environ['MODE'] in ('healthy','recover') and count>=2: (p/'enabled').unlink(missing_ok=True)
''')
        path = self.script('root-watchdog.sh', dict(ENABLED=self.root/'enabled', HEARTBEAT=self.root/'heartbeat',
            RUNTIME=self.root/'runtime', PACKAGE='io.github.wboat.shutup', USER=10,
            SERVICE='io.github.wboat.shutup/com.xiaomanjun.sleepdownschedule.feature.guard.GuardService'))
        result = subprocess.run(['/bin/sh', str(path)], env=self.env, timeout=10, capture_output=True, text=True)
        calls = (self.root/'calls').read_text().splitlines() if (self.root/'calls').exists() else []
        return result, calls

    def test_fresh_heartbeat_never_restarts(self):
        result, calls = self.watchdog('healthy', fresh=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], calls)
        self.assertFalse((self.root/'runtime/pid').exists())

    def test_stale_heartbeat_recovers_exact_service_and_user(self):
        result, calls = self.watchdog('recover')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(calls))
        self.assertIn('start-foreground-service --user 10 -n io.github.wboat.shutup/', calls[0])

    def test_failed_recovery_is_bounded_to_three_attempts(self):
        result, calls = self.watchdog('fail')
        self.assertEqual(1, result.returncode)
        self.assertEqual(3, len(calls))
        self.assertEqual('failed', (self.root/'runtime/status').read_text().strip())

    def test_disabled_flag_prevents_restart(self):
        result, calls = self.watchdog('disabled', enabled=False)
        self.assertEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_disabling_between_unstop_and_restart_prevents_launch(self):
        result, calls = self.watchdog('disable-during-unstop')
        self.assertEqual(0, result.returncode)
        self.assertEqual([], calls)

    def accessibility(self, existing, fail=False):
        state = self.root/'settings.json'
        state.write_text(json.dumps({'enabled_accessibility_services': existing, 'accessibility_enabled': '0'}))
        self.env['FAIL_WRITE'] = '1' if fail else '0'
        self.command('settings', '''import os,sys,json
from pathlib import Path
assert sys.argv[1:3]==['--user','10']
p=Path(os.environ['FIXTURE'])/'settings.json'; data=json.loads(p.read_text())
if sys.argv[3]=='get': print(data[sys.argv[5]])
else:
    if os.environ['FAIL_WRITE']=='1': sys.exit(1)
    data[sys.argv[5]]=sys.argv[6]; data.setdefault('_writes',[]).append([sys.argv[5],sys.argv[6]]); p.write_text(json.dumps(data))
''')
        component = 'io.github.wboat.shutup/com.xiaomanjun.sleepdownschedule.feature.guard.GuardLockAccessibilityService'
        path = self.script('enable-accessibility.sh', dict(USER=10, COMPONENT=component))
        result = subprocess.run(['/bin/sh', str(path)], env=self.env, timeout=10)
        return result, json.loads(state.read_text()), component

    def test_accessibility_preserves_other_services(self):
        result, state, component = self.accessibility('other.app/other.Service')
        self.assertEqual(0, result.returncode)
        self.assertEqual('other.app/other.Service:'+component, state['enabled_accessibility_services'])
        self.assertEqual('1', state['accessibility_enabled'])

    def test_accessibility_does_not_duplicate_own_component(self):
        component = 'io.github.wboat.shutup/com.xiaomanjun.sleepdownschedule.feature.guard.GuardLockAccessibilityService'
        result, state, _ = self.accessibility(component)
        self.assertEqual(0, result.returncode)
        self.assertEqual(component, state['enabled_accessibility_services'])

    def test_accessibility_empty_and_null_are_handled(self):
        result, state, component = self.accessibility('null')
        self.assertEqual(0, result.returncode)
        self.assertEqual(component, state['enabled_accessibility_services'])

    def test_accessibility_restarts_only_own_stale_service(self):
        component = 'io.github.wboat.shutup/com.xiaomanjun.sleepdownschedule.feature.guard.GuardLockAccessibilityService'
        result, state, _ = self.accessibility('first.app/First:'+component+':last.app/Last')
        self.assertEqual(0, result.returncode)
        writes = [value for key,value in state['_writes'] if key=='enabled_accessibility_services']
        self.assertEqual('first.app/First:last.app/Last', writes[0])
        self.assertEqual('first.app/First:last.app/Last:'+component, writes[1])

    def test_accessibility_write_failure_is_not_reported_as_success(self):
        result, state, _ = self.accessibility('other.app/other.Service', fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('other.app/other.Service', state['enabled_accessibility_services'])
        self.assertEqual('0', state['accessibility_enabled'])


if __name__ == '__main__':
    unittest.main()
