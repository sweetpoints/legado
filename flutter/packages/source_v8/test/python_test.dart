import 'dart:io';

import 'package:test/test.dart';

import '../hook/python.dart';

void main() {
  test(
    'selects a real Python with the extraction/hash feature baseline',
    () async {
      final python = await pythonExecutable();
      final result = await Process.run(python, [
        '-c',
        'import hashlib, sys, tarfile; '
            'assert sys.version_info >= (3, 12); '
            'assert callable(hashlib.file_digest); '
            'assert callable(tarfile.data_filter)',
      ]);
      expect(result.exitCode, 0, reason: '${result.stderr}');
      expect(
        await pythonExecutable(
          candidates: ['/missing/source-v8-python', python],
        ),
        python,
      );
    },
  );

  test(
    'an unavailable or incompatible interpreter fails before SDK execution',
    () async {
      await expectLater(
        pythonExecutable(candidates: ['/missing/source-v8-python']),
        throwsA(
          isA<StateError>().having(
            (error) => error.message,
            'message',
            contains('Python 3.12+'),
          ),
        ),
      );
      await expectLater(
        pythonExecutable(candidates: [Platform.resolvedExecutable]),
        throwsStateError,
      );
    },
  );
}
