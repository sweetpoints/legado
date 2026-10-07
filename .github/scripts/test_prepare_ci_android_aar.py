import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class PrepareCiAarTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        scripts = self.root / '.github/scripts'
        scripts.mkdir(parents=True)
        self.script = scripts / 'prepare-ci-android-aar.sh'
        shutil.copyfile(Path(__file__).with_name(self.script.name), self.script)
        (self.root / 'flutter/tool').mkdir(parents=True)
        self.wrapper = self.root / 'flutter/modules/source_host/.android'
        self.wrapper.mkdir(parents=True)
        (self.root / 'flutter/.gradle-source-host').mkdir()
        self.main_home = self.root / 'main-gradle-home'
        self.main_home.mkdir()
        (self.main_home / 'daemon-marker').write_text('untouched')
        (self.root / 'flutter/tool/prepare-android-aar.sh').write_text('''#!/usr/bin/env bash
python3 - <<'PY'
import json, os
from pathlib import Path
Path('prepare.json').write_text(json.dumps({'budget': os.environ['SOURCE_ENGINE_AAR_GRADLE_JVMARGS']}))
PY
exit "${TEST_PREPARE_EXIT:-0}"
''')
        (self.wrapper / 'gradlew').write_text('''#!/usr/bin/env bash
python3 - "$@" <<'PY'
import json, os, sys
from pathlib import Path
Path('stop.json').write_text(json.dumps({'home': os.environ['GRADLE_USER_HOME'], 'args': sys.argv[1:]}))
PY
exit "${TEST_STOP_EXIT:-0}"
''')

    def run_script(self, **extra):
        env = dict(os.environ)
        env.update(GRADLE_USER_HOME=str(self.main_home), SOURCE_ENGINE_AAR_GRADLE_JVMARGS='-Xmx8g')
        env.update(extra)
        return subprocess.run(['bash', str(self.script)], cwd=self.root, env=env, capture_output=True, text=True)

    def test_verified_budget_and_only_generated_wrapper_private_home(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads((self.root / 'prepare.json').read_text())['budget'],
                         '-Xmx2g -Xms256m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8')
        stop = json.loads((self.wrapper / 'stop.json').read_text())
        self.assertEqual(stop['home'], str(self.root / 'flutter/.gradle-source-host'))
        self.assertEqual(stop['args'], ['--stop', '--console=plain'])
        self.assertEqual((self.main_home / 'daemon-marker').read_text(), 'untouched')
        self.assertFalse((self.main_home / 'stop.json').exists())

    def test_prepare_failure_still_stops_own_daemon_and_preserves_failure(self):
        result = self.run_script(TEST_PREPARE_EXIT='7', TEST_STOP_EXIT='9')
        self.assertEqual(result.returncode, 7)
        self.assertTrue((self.wrapper / 'stop.json').exists())

    def test_stop_failure_is_not_reported_as_successful_setup(self):
        self.assertEqual(self.run_script(TEST_STOP_EXIT='9').returncode, 1)

    def test_missing_generated_wrapper_never_falls_back_to_root_wrapper(self):
        (self.wrapper / 'gradlew').unlink()
        (self.root / 'gradlew').write_text('exit 99\n')
        self.assertEqual(self.run_script(TEST_PREPARE_EXIT='7').returncode, 7)
        self.assertFalse((self.wrapper / 'stop.json').exists())


if __name__ == '__main__':
    unittest.main()
