import 'package:archive/archive.dart';
import 'package:test/test.dart';

import '../hook/build.dart' show sourceTreeDigest;

void main() {
  Archive tree(String contents, int time) => Archive()
    ..add(ArchiveFile.string('include/api.h', contents)..lastModTime = time)
    ..add(ArchiveFile.directory('include'));
  test('source integrity ignores request-time tar metadata', () async {
    expect(
      await sourceTreeDigest(tree('API', 1)),
      await sourceTreeDigest(tree('API', 2)),
    );
  });
  test('source integrity authenticates complete file contents', () async {
    expect(
      await sourceTreeDigest(tree('API', 1)),
      isNot(await sourceTreeDigest(tree('modified', 1))),
    );
  });
  test('source integrity rejects traversal and duplicate names', () async {
    await expectLater(
      sourceTreeDigest(Archive()..add(ArchiveFile.string('../evil', 'x'))),
      throwsStateError,
    );
    await expectLater(
      sourceTreeDigest(
        Archive()
          ..add(ArchiveFile.string('a', 'x'))
          ..add(ArchiveFile.string('b', 'y'))
          ..modifyAtIndex(1, ArchiveFile.string('a', 'y')),
      ),
      throwsStateError,
    );
  });
}
