import 'dart:convert';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:app_backup/app_backup.dart';
import 'package:app_core/app_core.dart';
import 'package:crypto/crypto.dart';
import 'package:html/dom.dart' as html;
import 'package:html/parser.dart' as html;
import 'package:xml/xml.dart';

enum EpubImportFailure {
  invalidArchive,
  invalidPackage,
  encrypted,
  unsupported,
  empty,
}

/// A typed failure lets the UI translate errors without parsing exception text.
final class EpubImportException implements Exception {
  const EpubImportException(this.failure, this.detail);
  final EpubImportFailure failure;
  final String detail;
  @override
  String toString() => 'EPUB ${failure.name}: $detail';
}

/// Plain-text EPUB import only. No document code runs and no resource is fetched.
/// Package/spine semantics follow https://www.w3.org/TR/epub-33/ .
abstract final class EpubImporter {
  static Future<LegacyBookRecord> importBytes(
    LibraryRepository repository, {
    required String name,
    required Uint8List bytes,
  }) async {
    final durable = repository is LocalBookRepository;
    final original =
        durable && bytes.length <= LocalBookAssets.maximumAssetBytes
        ? Uint8List.fromList(bytes)
        : null;
    // A bound method captures only the data-only receiver. A closure here can
    // accidentally capture a repository with native handles or a BuildContext.
    final decoded = await Isolate.run(_DecodeJob(name, original ?? bytes).call);
    final book = decoded.book;
    final url = decoded.url;
    final existing = await repository.book(url);
    if (existing != null) return existing;
    final chapters = <ReaderChapter>[];
    final contents = <String, String>{};
    for (var index = 0; index < book.chapters.length; index++) {
      final item = book.chapters[index];
      final chapter = ReaderChapter(
        url: '$url#$index',
        title: item.title,
        index: index,
      );
      contents[chapter.url] = item.text;
      chapters.add(chapter);
    }
    final assetKey = original == null
        ? null
        : '${LocalBookAssets.prefix}${url.split(':').last}/original.epub';
    final record = LegacyBookRecord({
      'bookUrl': url,
      'name': book.title,
      'author': book.author,
      'origin': 'local',
      'originName': 'EPUB',
      'format': 'epub',
      'originalAssetKey': ?assetKey,
      'type': 0,
      'durChapterIndex': 0,
      'durChapterPos': 0,
      'totalChapterNum': chapters.length,
    });
    if (repository is LocalBookRepository) {
      await (repository as LocalBookRepository).importLocalBook(
        record,
        chapters,
        contents,
        assetsByKey: {?assetKey: original!},
      );
    } else {
      for (final entry in contents.entries) {
        await repository.putContent(url, entry.key, entry.value);
      }
      await repository.putChapters(url, chapters);
      await repository.putBook(record);
    }
    return await repository.book(url) ?? record;
  }
}

final class _DecodeJob {
  const _DecodeJob(this.name, this.bytes);
  final String name;
  final Uint8List bytes;

  ({_Book book, String url}) call() {
    late Map<String, Uint8List> files;
    try {
      files = LegacyBackupArchive.decodeEntries(bytes);
    } on FormatException catch (error) {
      throw EpubImportException(
        EpubImportFailure.invalidArchive,
        error.message,
      );
    }
    final book = _Package(files).read(name);
    final digest = sha256.convert(utf8.encode(jsonEncode(book.identity)));
    return (book: book, url: 'local:epub:$digest');
  }
}

final class _Book {
  const _Book(this.title, this.author, this.metadataTitle, this.chapters);
  final String title, author, metadataTitle;
  final List<({String path, String title, String text})> chapters;
  Map<String, Object?> get identity => {
    'version': 1,
    'title': metadataTitle,
    'author': author,
    'chapters': [
      for (final c in chapters) [c.title, c.text],
    ],
  };
}

final class _Item {
  const _Item(this.path, this.type, this.properties);
  final String path, type;
  final Set<String> properties;
}

final class _Package {
  _Package(this.files);
  final Map<String, Uint8List> files;
  Never fail(
    String detail, [
    EpubImportFailure kind = EpubImportFailure.invalidPackage,
  ]) => throw EpubImportException(kind, detail);

  String readText(String path) {
    final bytes = files[path];
    if (bytes == null) fail('Missing resource: $path');
    if (bytes.length > 8 * 1024 * 1024) {
      fail('Text resource exceeds 8 MiB parsing limit: $path');
    }
    try {
      if (bytes.length >= 2 &&
          ((bytes[0] == 0xff && bytes[1] == 0xfe) ||
              (bytes[0] == 0xfe && bytes[1] == 0xff))) {
        if (bytes.length.isOdd) fail('Truncated UTF-16 resource: $path');
        final data = ByteData.sublistView(bytes);
        final endian = bytes[0] == 0xff ? Endian.little : Endian.big;
        return String.fromCharCodes([
          for (var i = 2; i < bytes.length; i += 2) data.getUint16(i, endian),
        ]);
      }
      return utf8
          .decode(bytes, allowMalformed: false)
          .replaceFirst(RegExp('^\uFEFF'), '');
    } on FormatException {
      fail('Resource is not valid UTF-8 or BOM-marked UTF-16: $path');
    }
  }

  XmlDocument xml(String path, {bool allowDoctype = false}) {
    final text = readText(path);
    // Containers and package metadata do not need DTDs or entity declarations.
    if (RegExp(r'<!ENTITY', caseSensitive: false).hasMatch(text) ||
        (!allowDoctype &&
            RegExp(r'<!DOCTYPE', caseSensitive: false).hasMatch(text))) {
      fail('DTD/entity declaration is unsupported');
    }
    try {
      return XmlDocument.parse(text);
    } on XmlException {
      fail('Malformed XML: $path');
    }
  }

  List<XmlElement> elements(XmlNode node, String local) => node.descendants
      .whereType<XmlElement>()
      .where((e) => e.name.local == local)
      .toList();

  String resolve(String owner, String reference, {bool fragment = false}) {
    try {
      final uri = Uri.parse(reference);
      if (reference.isEmpty ||
          uri.hasScheme ||
          uri.hasAuthority ||
          uri.hasQuery ||
          (!fragment && uri.hasFragment) ||
          uri.path.startsWith('/')) {
        fail('Non-local resource path');
      }
      if (fragment && uri.path.isEmpty && owner.isNotEmpty) return owner;
      final path = Uri.decodeComponent(uri.path);
      if (path.startsWith('/') ||
          path.contains('\\') ||
          path.contains(':') ||
          RegExp(r'[\x00-\x1f\x7f]').hasMatch(path)) {
        fail('Unsafe resource path');
      }
      final parts = owner.isEmpty
          ? <String>[]
          : (owner.split('/')..removeLast());
      for (final part in path.split('/')) {
        if (part.isEmpty || part == '.') continue;
        if (part == '..') {
          if (parts.isEmpty) fail('Resource escapes EPUB root');
          parts.removeLast();
        } else {
          parts.add(part);
        }
      }
      if (parts.isEmpty) fail('Empty resource path');
      return parts.join('/');
    } on FormatException {
      fail('Malformed resource URI');
    }
  }

  _Book read(String fallbackName) {
    if (files['mimetype'] == null ||
        readText('mimetype').trim() != 'application/epub+zip') {
      fail('Missing or invalid EPUB mimetype');
    }
    if (files.containsKey('META-INF/encryption.xml') &&
        elements(xml('META-INF/encryption.xml'), 'EncryptedData').isNotEmpty) {
      fail(
        'Encrypted or obfuscated EPUB resources are unsupported',
        EpubImportFailure.encrypted,
      );
    }
    if (files.containsKey('META-INF/rights.xml')) {
      fail(
        'EPUB rights management is unsupported',
        EpubImportFailure.encrypted,
      );
    }
    final container = xml('META-INF/container.xml');
    if (container.rootElement.name.local != 'container') {
      fail('Invalid container root');
    }
    final roots = elements(container, 'rootfile')
        .where(
          (e) =>
              e.getAttribute('media-type') == 'application/oebps-package+xml',
        )
        .toList();
    if (roots.isEmpty) fail('No package document');
    final packagePath = resolve(
      '',
      roots.first.getAttribute('full-path') ?? '',
    );
    final package = xml(packagePath);
    if (package.rootElement.name.local != 'package') {
      fail('Invalid package root');
    }
    final metadata = elements(package, 'metadata');
    final metadataTitle = metadata.isEmpty
        ? ''
        : elements(metadata.first, 'title')
                  .map((e) => e.innerText.trim())
                  .where((t) => t.isNotEmpty)
                  .firstOrNull ??
              '';
    final author = metadata.isEmpty
        ? ''
        : elements(metadata.first, 'creator')
              .map((e) => e.innerText.trim())
              .where((t) => t.isNotEmpty)
              .join(', ');
    final title = metadataTitle.isEmpty ? fallbackName.trim() : metadataTitle;
    if (title.isEmpty) fail('EPUB has no title');
    final manifests = elements(package, 'manifest');
    final spines = elements(package, 'spine');
    if (manifests.length != 1 || spines.length != 1) {
      fail('EPUB requires one manifest and spine');
    }
    final manifest = <String, _Item>{};
    for (final entry in manifests.single.childElements.where(
      (e) => e.name.local == 'item',
    )) {
      final id = entry.getAttribute('id') ?? '';
      if (id.isEmpty || manifest.containsKey(id)) {
        fail('Missing or duplicate manifest id');
      }
      final path = resolve(packagePath, entry.getAttribute('href') ?? '');
      manifest[id] = _Item(
        path,
        entry.getAttribute('media-type') ?? '',
        (entry.getAttribute('properties') ?? '').split(RegExp(r'\s+')).toSet(),
      );
    }
    final labels = <String, String>{};
    for (final nav in manifest.values.where(
      (i) => i.properties.contains('nav'),
    )) {
      final document = html.parse(readText(nav.path));
      for (final toc
          in document
              .querySelectorAll('nav')
              .where(
                (n) => (n.attributes['epub:type'] ?? n.attributes['type'] ?? '')
                    .split(' ')
                    .contains('toc'),
              )) {
        for (final anchor in toc.querySelectorAll('a[href]')) {
          final path = resolve(
            nav.path,
            anchor.attributes['href']!,
            fragment: true,
          );
          if (anchor.text.trim().isNotEmpty) {
            labels.putIfAbsent(path, () => anchor.text.trim());
          }
        }
      }
    }
    final ncxId = spines.single.getAttribute('toc');
    if (ncxId != null) {
      final ncx = manifest[ncxId];
      if (ncx == null || ncx.type != 'application/x-dtbncx+xml') {
        fail('Invalid NCX manifest reference');
      }
      final document = xml(ncx.path, allowDoctype: true);
      for (final point in elements(document, 'navPoint')) {
        final targets = point.childElements.where(
          (e) => e.name.local == 'content',
        );
        final labelNodes = point.childElements.where(
          (e) => e.name.local == 'navLabel',
        );
        if (targets.isEmpty || labelNodes.isEmpty) continue;
        final label = labelNodes.first.innerText.trim();
        final path = resolve(
          ncx.path,
          targets.first.getAttribute('src') ?? '',
          fragment: true,
        );
        if (label.isNotEmpty) labels.putIfAbsent(path, () => label);
      }
    }
    final chapters = <({String path, String title, String text})>[];
    final seen = <String>{};
    var linearCount = 0;
    for (final ref in spines.single.childElements.where(
      (e) => e.name.local == 'itemref',
    )) {
      final id = ref.getAttribute('idref') ?? '';
      final item = manifest[id];
      if (item == null || !seen.add(id)) {
        fail('Missing or duplicate spine reference');
      }
      final linear = ref.getAttribute('linear') ?? 'yes';
      if (linear != 'yes' && linear != 'no') {
        fail('Invalid spine linear attribute');
      }
      if (linear == 'yes') linearCount++;
      if (item.properties.contains('nav')) continue;
      if (item.type != 'application/xhtml+xml' && item.type != 'text/html') {
        fail(
          'Non-text spine content: ${item.type}',
          EpubImportFailure.unsupported,
        );
      }
      final document = html.parse(readText(item.path));
      final heading = document.querySelector('h1,h2,h3')?.text.trim();
      final content = _plainText(document.body);
      if (content.isEmpty) continue;
      final chapterTitle =
          labels[item.path] ??
          (heading?.isNotEmpty == true
              ? heading!
              : document.querySelector('title')?.text.trim());
      chapters.add((
        path: item.path,
        title: chapterTitle?.isNotEmpty == true
            ? chapterTitle!
            : '${chapters.length + 1}',
        text: content,
      ));
    }
    if (linearCount == 0) fail('No linear spine entries');
    if (chapters.isEmpty) {
      fail('EPUB contains no readable text', EpubImportFailure.empty);
    }
    return _Book(title, author, metadataTitle, chapters);
  }
}

String _plainText(html.Element? body) {
  final output = StringBuffer();
  final stack = <({html.Node node, bool close})>[
    if (body != null) (node: body, close: false),
  ];
  // Iterative traversal avoids a stack overflow on deeply nested XHTML.
  while (stack.isNotEmpty) {
    final item = stack.removeLast();
    if (item.close) {
      output.write('\n');
      continue;
    }
    final node = item.node;
    if (node is html.Text) {
      output.write(node.text);
      continue;
    }
    if (node is! html.Element) continue;
    final tag = node.localName;
    if (const {
          'script',
          'style',
          'nav',
          'noscript',
          'template',
          'iframe',
          'object',
          'embed',
        }.contains(tag) ||
        node.attributes.containsKey('hidden') ||
        node.attributes['aria-hidden'] == 'true') {
      continue;
    }
    final block = const {
      'p',
      'div',
      'section',
      'article',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'li',
      'blockquote',
      'pre',
      'tr',
    }.contains(tag);
    if (block || tag == 'br') output.write('\n');
    if (block) stack.add((node: node, close: true));
    for (final child in node.nodes.reversed) {
      stack.add((node: child, close: false));
    }
  }
  return output
      .toString()
      .split('\n')
      .map((line) => line.replaceAll(RegExp(r'[\s\u00a0]+'), ' ').trim())
      .join('\n')
      .replaceAll(RegExp(r'\n{3,}'), '\n\n')
      .trim();
}
