import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:drift/drift.dart';

part 'library_database.g.dart';

@DataClassName('LibraryBookRow')
class StoredBooks extends Table {
  @override
  String get tableName => 'books';
  TextColumn get id => text()();
  TextColumn get data => text()();
  @override
  Set<Column<Object>> get primaryKey => {id};
}

@DataClassName('LibrarySourceRow')
class StoredSources extends Table {
  @override
  String get tableName => 'sources';
  TextColumn get id => text()();
  TextColumn get data => text()();
  @override
  Set<Column<Object>> get primaryKey => {id};
}

@DataClassName('LibrarySettingRow')
class StoredSettings extends Table {
  @override
  String get tableName => 'settings';
  TextColumn get id => text()();
  TextColumn get value => text()();
  @override
  Set<Column<Object>> get primaryKey => {id};
}

@DataClassName('LibraryChapterRow')
class StoredChapters extends Table {
  @override
  String get tableName => 'chapters';
  TextColumn get bookId => text()();
  TextColumn get id => text()();
  IntColumn get position => integer()();
  TextColumn get data => text()();
  @override
  Set<Column<Object>> get primaryKey => {bookId, id};
  @override
  List<Set<Column<Object>>> get uniqueKeys => [
    {bookId, position},
  ];
}

@DataClassName('LibraryContentRow')
class StoredContent extends Table {
  @override
  String get tableName => 'content';
  TextColumn get bookId => text()();
  TextColumn get chapterId => text()();
  TextColumn get value => text()();
  @override
  Set<Column<Object>> get primaryKey => {bookId, chapterId};
}

@DataClassName('LibraryBackupFileRow')
class StoredBackupFiles extends Table {
  @override
  String get tableName => 'backup_files';
  TextColumn get id => text()();
  TextColumn get value => text()();
  @override
  Set<Column<Object>> get primaryKey => {id};
}

@DataClassName('LibraryArchiveEntryRow')
class StoredArchiveEntries extends Table {
  @override
  String get tableName => 'archive_entries';
  TextColumn get id => text()();
  BlobColumn get value => blob()();
  @override
  Set<Column<Object>> get primaryKey => {id};
}

@DataClassName('LibraryBookGroupRow')
class StoredBookGroups extends Table {
  @override
  String get tableName => 'book_groups';
  IntColumn get id => integer()();
  IntColumn get position => integer()();
  TextColumn get data => text()();
  @override
  Set<Column<Object>> get primaryKey => {id};
}

@DataClassName('LibraryLocalBookAssetRow')
class StoredLocalBookAssets extends Table {
  @override
  String get tableName => 'local_book_assets';
  TextColumn get bookId => text()();
  TextColumn get assetKey => text()();
  BoolColumn get managed => boolean()();
  TextColumn get data => text()();
  @override
  Set<Column<Object>> get primaryKey => {bookId, assetKey};
}

/// Generated schema deliberately matches the original SQLite version-1 layout.
/// Existing installations can adopt Drift without copying or clearing tables.
@DriftDatabase(
  tables: [
    StoredBooks,
    StoredSources,
    StoredSettings,
    StoredChapters,
    StoredContent,
    StoredBackupFiles,
    StoredArchiveEntries,
    StoredBookGroups,
    StoredLocalBookAssets,
  ],
)
class LibraryDatabase extends _$LibraryDatabase {
  LibraryDatabase(super.executor);

  @override
  int get schemaVersion => 4;

  @override
  MigrationStrategy get migration => MigrationStrategy(
    onCreate: (m) => transaction(m.createAll),
    onUpgrade: (m, from, to) async {
      if (from < 1 || from > 3 || to != 4) {
        throw StateError('Unsupported library schema transition');
      }
      await transaction(() async {
        if (from == 1) await m.createTable(storedArchiveEntries);
        if (from <= 2) {
          await m.createTable(storedBookGroups);
          final file = await (select(
            storedBackupFiles,
          )..where((t) => t.id.equals('bookGroup.json'))).getSingleOrNull();
          if (file != null) {
            final seen = <int>{};
            for (final entry in jsonDecode(file.value) as List) {
              final group = LegacyBookGroup(
                Map<String, Object?>.from(entry as Map),
              );
              if (!seen.add(group.id)) {
                throw const FormatException('Duplicate group id');
              }
              await into(storedBookGroups).insert(
                StoredBookGroupsCompanion.insert(
                  id: Value(group.id),
                  position: group.order,
                  data: jsonEncode(group.toJson()),
                ),
              );
            }
          }
        }
        // Old namespace-like BLOBs have no proven ownership. Do not infer it
        // from filenames or mutable book metadata, or delete retained entries.
        await m.createTable(storedLocalBookAssets);
      });
    },
    beforeOpen: (_) => customStatement('PRAGMA busy_timeout = 5000'),
  );
}
