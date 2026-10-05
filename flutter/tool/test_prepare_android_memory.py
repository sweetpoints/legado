import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


class PrepareMemoryContractTest(unittest.TestCase):
    def invoke(self, override=None, legacy_shared_cache=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workspace = root / 'flutter'
            tool = workspace / 'tool'
            tool.mkdir(parents=True)
            (workspace / 'modules/source_host').mkdir(parents=True)
            shutil.copyfile(Path(__file__).with_name('prepare-android-aar.sh'), tool / 'prepare-android-aar.sh')
            (tool / 'source-host-min-sdk.gradle').write_text('// fixture')
            (tool / 'verify-android-aar.py').write_text('import sys\nif "--print-source-digest" in sys.argv: print("fixed-source-sha")\n')
            binaries = root / 'bin'
            binaries.mkdir()
            (binaries / 'java').write_text('#!/bin/sh\necho \'openjdk version "21.0.11"\' >&2\n')
            fake_flutter = binaries / 'flutter'
            fake_flutter.write_text('''#!/bin/sh
if [ "$1" = --version ]; then
  echo '{"frameworkVersion":"3.47.6"}'
else
  python3 -c 'import os,pathlib; pathlib.Path(os.environ["CAPTURE"]).write_text(os.environ["GRADLE_OPTS"])'
fi
''')
            (binaries / 'java').chmod(0o700)
            fake_flutter.chmod(0o700)
            public_cache = root / 'public-cache'
            (public_cache / 'caches').mkdir(parents=True)
            (public_cache / 'caches' / 'external-marker').write_text('keep external cache')
            (public_cache / 'wrapper').mkdir()
            # Existing users/CI may still have the symlink from the old script.
            if legacy_shared_cache:
                private_home = workspace / '.gradle-source-host'
                private_home.mkdir()
                (private_home / 'caches').symlink_to(public_cache / 'caches', target_is_directory=True)
            capture = root / 'captured.txt'
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ['PATH'],
                       SOURCE_ENGINE_JDK=str(root), SOURCE_ENGINE_ANDROID_TARGETS='android-arm64',
                       GRADLE_USER_HOME=str(root / 'public-cache'), CAPTURE=str(capture),
                       GRADLE_OPTS='-Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g" -Dorg.gradle.workers.max=2')
            env.pop('SOURCE_ENGINE_AAR_GRADLE_JVMARGS', None)
            if override is not None:
                env['SOURCE_ENGINE_AAR_GRADLE_JVMARGS'] = override
            subprocess.run(['bash', str(tool / 'prepare-android-aar.sh'), 'debug'], env=env, check=True, capture_output=True)
            # Parse exactly the shell-quoted options consumed by official gradlew.
            private_home = workspace / '.gradle-source-host'
            self.assertTrue((private_home / 'caches').is_dir())
            self.assertFalse((private_home / 'caches').is_symlink())
            self.assertFalse((private_home / 'caches' / 'external-marker').exists())
            self.assertEqual((public_cache / 'caches' / 'external-marker').read_text(), 'keep external cache')
            self.assertTrue((private_home / 'wrapper').is_symlink())
            options = shlex.split(capture.read_text())
            properties = {}
            for option in options:
                if option.startswith('-D'):
                    key, value = option[2:].split('=', 1)
                    properties[key] = value
            return properties

    def test_previous_shared_cache_symlink_is_detached_without_touching_external_cache(self):
        self.invoke(legacy_shared_cache=True)

    def test_ci_aar_limit_overrides_app_limit_as_one_daemon_property(self):
        args = '-Xmx2g -Xms256m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'
        properties = self.invoke(args)
        self.assertEqual(properties['org.gradle.jvmargs'], args)
        self.assertEqual(properties['org.gradle.workers.max'], '2')
        self.assertIn('org.gradle.java.home', properties)

    def test_no_aar_override_preserves_existing_local_gradle_options(self):
        properties = self.invoke()
        self.assertEqual(properties['org.gradle.jvmargs'], '-Xmx4g -XX:MaxMetaspaceSize=1g')
        self.assertEqual(properties['org.gradle.workers.max'], '2')


if __name__ == '__main__':
    unittest.main()
