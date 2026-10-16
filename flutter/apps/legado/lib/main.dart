import 'dart:convert';

import 'package:app_core/app_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

void main() => runApp(const LegadoApp());

ThemeData readerTheme(Brightness brightness) => ThemeData(
  useMaterial3: true,
  colorScheme: ColorScheme.fromSeed(
    seedColor: const Color(0xff4355a5),
    brightness: brightness,
  ),
);

class LegadoApp extends StatelessWidget {
  const LegadoApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '阅读',
    debugShowCheckedModeBanner: false,
    theme: readerTheme(Brightness.light),
    darkTheme: readerTheme(Brightness.dark),
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    supportedLocales: const [Locale('zh'), Locale('en')],
    home: const BackupPreviewPage(),
  );
}

/// Read-only migration utility; preview never commits application data.
class BackupPreviewPage extends StatefulWidget {
  const BackupPreviewPage({super.key});
  @override
  State<BackupPreviewPage> createState() => _BackupPreviewPageState();
}

class _BackupPreviewPageState extends State<BackupPreviewPage> {
  final _input = TextEditingController();
  LegacyBackupDocument? _document;
  String? _error;
  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  void _preview() {
    try {
      final document = LegacyBackupDocument.parse({
        'bookshelf.json': _input.text,
      });
      setState(() {
        _document = document;
        _error = null;
      });
    } on FormatException catch (error) {
      setState(() {
        _document = null;
        _error = '无法读取书架：${error.message}';
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('书架备份预览')),
    body: SafeArea(
      child: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 960),
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              const Text('粘贴旧版备份中的 bookshelf.json，检查书籍和阅读进度。预览不会导入或修改数据。'),
              const SizedBox(height: 16),
              TextField(
                controller: _input,
                minLines: 4,
                maxLines: 8,
                decoration: InputDecoration(
                  labelText: '书架 JSON',
                  border: const OutlineInputBorder(),
                  errorText: _error,
                  errorMaxLines: 4,
                ),
              ),
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerLeft,
                child: FilledButton(
                  onPressed: _preview,
                  child: const Text('预览'),
                ),
              ),
              if (_document case final document?) ...[
                const SizedBox(height: 24),
                Text(
                  '共 ${document.books.length} 本书',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                for (final book in document.books)
                  Card(
                    child: ListTile(
                      title: Text(book.name),
                      subtitle: Text(
                        '${book.author}\n章节索引 ${book.chapterIndex} · 字符位置 ${book.characterOffset}',
                      ),
                      isThreeLine: true,
                      onTap: () => showDialog<void>(
                        context: context,
                        builder: (context) => AlertDialog(
                          title: Text(book.name),
                          content: SingleChildScrollView(
                            child: SelectableText(
                              const JsonEncoder.withIndent('  ')
                                  .convert(book.toJson()),
                            ),
                          ),
                          actions: [
                            TextButton(
                              onPressed: () => Navigator.pop(context),
                              child: const Text('关闭'),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
              ],
            ],
          ),
        ),
      ),
    ),
  );
}
