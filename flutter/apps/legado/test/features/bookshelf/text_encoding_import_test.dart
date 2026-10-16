import 'dart:io';
import 'dart:typed_data';

import 'package:app_storage/app_storage.dart';
import 'package:enough_convert/gbk.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:legado_app/app/file_exchange.dart';
import 'package:legado_app/app/local_text_decoder.dart';
import 'package:legado_app/app/providers.dart';
import 'package:legado_app/features/bookshelf/bookshelf_page.dart';
import 'package:legado_app/l10n/generated/app_localizations.dart';
import 'package:material_ui/material_ui.dart';

import '../sources/source_test_repository.dart';

class _AutomaticOnlyFiles implements FileExchange {
  int automaticCalls = 0;
  @override
  Future<ImportedTextFile?> pickText() async {
    automaticCalls++;
    return (name: 'Automatic', text: '第一章 起点\n自动导入正文。');
  }

  @override
  Future<ImportedEpubFile?> pickEpub() async => null;
  @override
  Future<Uint8List?> pickBackup() async => null;
  @override
  Future<bool> saveBackup(Uint8List bytes, String name) async => false;
}

class _EncodedFiles extends _AutomaticOnlyFiles
    implements EncodedTextFileExchange {
  LocalTextEncoding? selected;
  bool cancel = false;
  final bytes = Uint8List.fromList(gbk.encode('第一章 起点\n你好，正文。\n第二章 后续\n再见。'));
  @override
  Future<ImportedTextFile?> pickTextEncoded(LocalTextEncoding encoding) async {
    selected = encoding;
    if (cancel) return null;
    return (
      name: 'GBK fixture',
      text: LocalTextDecoder.decode(bytes, encoding: encoding).text,
    );
  }
}

class _ConflictingFiles extends _EncodedFiles {
  @override
  Future<ImportedTextFile?> pickTextEncoded(LocalTextEncoding encoding) async =>
      throw const FileExchangeException('textEncodingConflict');
}

final class _MemoryFile extends PlatformFile {
  _MemoryFile(this.bytes);
  final Uint8List bytes;
  @override
  String get name => 'Fixture.TXT';
  @override
  Uri get uri => Uri.parse('memory:fixture');
  @override
  Never get xFile => throw UnimplementedError();
  @override
  int lengthSync() => bytes.length;
  @override
  Future<int> length() async => bytes.length;
  @override
  Future<Uint8List> readAsBytes() async => bytes;
  @override
  Stream<Uint8List> readAsByteStream() => Stream.value(bytes);
}

class _Picker extends FilePickerPlatform {
  PlatformFile? file;
  @override
  Future<PlatformFile?> pickFile({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    Function(FilePickerStatus)? onFileLoading,
    int compressionQuality = 0,
    AndroidOptions androidOptions = const AndroidOptions(),
    DarwinOptions darwinOptions = const DarwinOptions(),
    WindowsOptions windowsOptions = const WindowsOptions(),
    LinuxOptions linuxOptions = const LinuxOptions(),
    WebOptions webOptions = const WebOptions(),
  }) async {
    expect(type, FileType.custom);
    expect(allowedExtensions, ['txt']);
    return file;
  }
}

Future<ProviderContainer> mount(
  WidgetTester tester,
  SourceTestRepository repository,
  FileExchange files,
) async {
  tester.view.physicalSize = const Size(320, 800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final container = ProviderContainer(
    overrides: [
      libraryProvider.overrideWithValue(repository),
      fileExchangeProvider.overrideWithValue(files),
    ],
  );
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
    await repository.close();
  });
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        theme: ThemeData(useMaterial3: true),
        locale: const Locale('en'),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        home: const BookshelfPage(),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return container;
}

Future<void> openEncodingDialog(WidgetTester tester) async {
  await tester.tap(find.byTooltip('Bookshelf options'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Import TXT with encoding'));
  await tester.pumpAndSettle();
  expect(find.text('Choose TXT encoding'), findsOneWidget);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('native adapter honors explicit encoding and cancellation', () async {
    final previous = FilePickerPlatform.instance;
    final picker = _Picker();
    FilePickerPlatform.instance = picker;
    addTearDown(() => FilePickerPlatform.instance = previous);
    picker.file = _MemoryFile(Uint8List.fromList(gbk.encode('龘')));
    final result = await const NativeFileExchange().pickTextEncoded(
      LocalTextEncoding.gbk,
    );
    expect(result, (name: 'Fixture', text: '龘'));
    picker.file = null;
    expect(
      await const NativeFileExchange().pickTextEncoded(LocalTextEncoding.utf8),
      isNull,
    );
  });

  test(
    'native explicit encoding reports BOM conflict rather than overriding it',
    () async {
      final previous = FilePickerPlatform.instance;
      FilePickerPlatform.instance = _Picker()
        ..file = _MemoryFile(Uint8List.fromList([0xff, 0xfe, 0x2d, 0x4e]));
      addTearDown(() => FilePickerPlatform.instance = previous);
      await expectLater(
        const NativeFileExchange().pickTextEncoded(LocalTextEncoding.gbk),
        throwsA(
          isA<FileExchangeException>().having(
            (e) => e.code,
            'code',
            'textEncodingConflict',
          ),
        ),
      );
      expect((await const NativeFileExchange().pickText())!.text, '中');
    },
  );

  test('GBK controller import survives database close and reopen with chapter content', () async {
    final directory = await Directory.systemTemp.createTemp(
      'legado-txt-encoding-',
    );
    addTearDown(() => directory.delete(recursive: true));
    final path = '${directory.path}/library.sqlite';
    final repository = await SqliteLibraryRepository.open(path);
    final files = _EncodedFiles();
    final container = ProviderContainer(
      overrides: [
        libraryProvider.overrideWithValue(repository),
        fileExchangeProvider.overrideWithValue(files),
      ],
    );
    try {
      await container
          .read(importingTextProvider.notifier)
          .importText(encoding: LocalTextEncoding.gbk);
      expect(files.selected, LocalTextEncoding.gbk);
      expect(files.automaticCalls, 0);
      expect(container.read(importingTextProvider), isFalse);
    } finally {
      container.dispose();
      await repository.close();
    }
    final reopened = await SqliteLibraryRepository.open(path);
    try {
      final book = (await reopened.books()).single;
      final chapters = await reopened.chapters(book.url);
      expect(chapters.length, 2);
      expect(
        await reopened.content(book.url, chapters.first.url),
        '第一章 起点\n你好，正文。\n',
      );
      expect(
        await reopened.content(book.url, chapters.last.url),
        '第二章 后续\n再见。',
      );
    } finally {
      await reopened.close();
    }
  });

  testWidgets(
    'GBK choice imports without invoking automatic detection at 320px',
    (tester) async {
      final repository = SourceTestRepository();
      final files = _EncodedFiles();
      await mount(tester, repository, files);
      await openEncodingDialog(tester);
      expect(find.text('UTF-8'), findsOneWidget);
      expect(find.text('UTF-16 Little Endian'), findsOneWidget);
      expect(find.text('UTF-16 Big Endian'), findsOneWidget);
      await tester.tap(find.text('GBK / GB2312'));
      await tester.pumpAndSettle();
      expect(files.selected, LocalTextEncoding.gbk);
      expect(files.automaticCalls, 0);
      expect(find.text('GBK fixture'), findsOneWidget);
      expect(repository.contentRows.values.join(), contains('你好，正文。'));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'unsupported encoded capability reports a clear error without auto fallback',
    (tester) async {
      final repository = SourceTestRepository();
      final files = _AutomaticOnlyFiles();
      final container = await mount(tester, repository, files);
      await openEncodingDialog(tester);
      await tester.tap(find.text('GBK / GB2312'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining(
          'This file adapter cannot import a chosen encoding.',
        ),
        findsOneWidget,
      );
      expect(files.automaticCalls, 0);
      expect(repository.bookRows, isEmpty);
      expect(container.read(importingTextProvider), isFalse);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'BOM conflict is shown in the import UI and leaves the shelf unchanged',
    (tester) async {
      final repository = SourceTestRepository();
      final files = _ConflictingFiles();
      final container = await mount(tester, repository, files);
      await openEncodingDialog(tester);
      await tester.tap(find.text('GBK / GB2312'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining(
          'The selected encoding conflicts with this file’s BOM.',
        ),
        findsOneWidget,
      );
      expect(repository.bookRows, isEmpty);
      expect(files.automaticCalls, 0);
      expect(container.read(importingTextProvider), isFalse);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'normal TXT FAB preserves automatic one-step import for old adapters',
    (tester) async {
      final repository = SourceTestRepository();
      final files = _AutomaticOnlyFiles();
      await mount(tester, repository, files);
      await tester.tap(find.text('Import TXT book'));
      await tester.pumpAndSettle();
      expect(files.automaticCalls, 1);
      expect(find.text('Choose TXT encoding'), findsNothing);
      expect(find.text('Automatic'), findsOneWidget);
    },
  );

  testWidgets('cancelling encoding dialog never opens a file picker', (
    tester,
  ) async {
    final files = _EncodedFiles();
    await mount(tester, SourceTestRepository(), files);
    await openEncodingDialog(tester);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(files.selected, isNull);
    expect(files.automaticCalls, 0);
  });
}
