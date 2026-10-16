import 'dart:io';

Future<String> pythonExecutable({List<String>? candidates}) async {
  final configured = Platform.environment['SOURCE_V8_PYTHON'];
  final choices =
      candidates ??
      (configured != null
          ? [configured]
          : ['python3', '/opt/homebrew/bin/python3', '/usr/local/bin/python3']);
  for (final candidate in choices) {
    try {
      final version = await Process.run(candidate, [
        '-c',
        'import sys; sys.exit(0 if sys.version_info >= (3, 12) else 1)',
      ]);
      if (version.exitCode == 0) return candidate;
    } on ProcessException {
      // Xcode often resolves python3 to Apple's old system Python. Try the
      // installed tool interpreter, never weaken SDK extraction or hashing.
    }
  }
  throw StateError(
    'V8 SDK preparation requires Python 3.12+. '
    'Set SOURCE_V8_PYTHON to its absolute executable path.',
  );
}
