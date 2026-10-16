import 'dart:collection';

import 'package:html/dom.dart';
import 'package:html/parser.dart' as html;
import 'package:source_engine/source_engine.dart';

/// Read-only Jsoup forest protocol. Each RPC owns a temporary Dart tree; the JS
/// weak wrappers retain JSON snapshots and stable IDs, not native host leases.
class PortableLegacyDomHost implements ScriptHost {
  const PortableLegacyDomHost(this.delegate);
  final ScriptHost delegate;
  static const maxNodes = 100000;
  static const readMembers = {
    'outerHtml',
    'toString',
    'text',
    'ownText',
    'html',
    'attr',
    'hasAttr',
    'data',
    'tagName',
    'id',
    'className',
    'select',
    'selectFirst',
    'getElementsByTag',
    'getElementsByClass',
    'getElementById',
    'parent',
    'children',
    'nextElementSibling',
    'previousElementSibling',
    'body',
    'head',
    'title',
  };

  @override
  Future<Object?> call(String method, List<Object?> arguments) async {
    if (method == 'orgJsoup.parse' || method == 'orgJsoup.parseBodyFragment') {
      if (arguments.isEmpty ||
          arguments.length > 2 ||
          arguments.first is! String ||
          (arguments.length == 2 && arguments.last is! String)) {
        throw const EngineException(
          'legacy.invalid_dom_overload',
          'HTML parsing requires text and optional base URI',
        );
      }
      if ((arguments.first as String).length > 8 * 1024 * 1024) {
        throw const EngineException(
          'legacy.dom_limit',
          'HTML input exceeds resource bounds',
        );
      }
      final document = method == 'orgJsoup.parseBodyFragment'
          ? (Document.html('')
              ..body!.nodes.addAll(
                html.parseFragment(arguments.first as String).nodes.toList(),
              ))
          : html.parse(arguments.first as String);
      return _Forest.capture(
        document,
        arguments.length == 2 ? arguments[1] as String : '',
      ).nodeMarker(document);
    }
    if (method.startsWith('orgJsoup.')) {
      throw const EngineException(
        'legacy.unsupported_org_api',
        'Only read-only HTML parse entry points are implemented; connection/XML/native leases are unavailable',
      );
    }
    if (method != 'javaHost.domCall') return delegate.call(method, arguments);
    if (arguments.length != 3 ||
        arguments[0] is! Map ||
        arguments[1] is! String ||
        arguments[2] is! List) {
      throw const EngineException(
        'legacy.invalid_dom',
        'DOM call requires marker, operation and argument list',
      );
    }
    final marker = Map<String, Object?>.from(arguments[0] as Map);
    final list = marker.containsKey('__legacyDomList');
    final raw = marker[list ? '__legacyDomList' : '__legacyDom'];
    if (raw is! Map) {
      throw const EngineException(
        'legacy.invalid_dom',
        'DOM marker is missing its table',
      );
    }
    final data = Map<String, Object?>.from(raw);
    final forest = _Forest.restore(data);
    final positions = list ? data['indexes'] : [data['index']];
    if (positions is! List ||
        positions.any(
          (index) => index is! int || index < 0 || index >= forest.nodes.length,
        )) {
      throw const EngineException(
        'legacy.invalid_dom',
        'DOM node identity is invalid',
      );
    }
    final selected = positions
        .cast<int>()
        .map((index) => forest.nodes[index])
        .toList();
    final operation = arguments[1] as String;
    final args = List<Object?>.from(arguments[2] as List);
    if (!readMembers.contains(operation)) {
      throw EngineException(
        'legacy.unsupported_dom_operation',
        'Read-only DOM operation unavailable: $operation',
      );
    }
    String textArgument() {
      if (args.length != 1 || args.single is! String) {
        throw const EngineException(
          'legacy.invalid_dom_overload',
          'Expected exactly one DOM string argument',
        );
      }
      return args.single as String;
    }

    if (!const {
          'attr',
          'hasAttr',
          'select',
          'selectFirst',
          'getElementsByTag',
          'getElementsByClass',
          'getElementById',
        }.contains(operation) &&
        args.isNotEmpty) {
      throw const EngineException(
        'legacy.unsupported_dom_operation',
        'DOM mutation overloads are unavailable',
      );
    }
    final elementNodes = selected.whereType<Element>().toList();
    Object? value;
    switch (operation) {
      case 'outerHtml':
      case 'toString':
        value = selected.map(_outerHtml).join('\n');
      case 'text':
        value = (await Future.wait(selected.map(_text)))
            .where((text) => text.isNotEmpty)
            .join(' ');
      case 'ownText':
        value = selected
            .map(
              (node) => node.nodes
                  .whereType<Text>()
                  .map((node) => node.data)
                  .join(' ')
                  .replaceAll(RegExp(r'[\t\n\r\f ]+'), ' ')
                  .trim(),
            )
            .where((text) => text.isNotEmpty)
            .join(' ');
      case 'html':
        value = elementNodes.map((node) => node.innerHtml).join('\n');
      case 'attr':
        final name = textArgument();
        final found = elementNodes.where(
          (node) => node.attributes.containsKey(name),
        );
        value = found.isEmpty ? '' : found.first.attributes[name] ?? '';
      case 'hasAttr':
        final name = textArgument();
        value = elementNodes.any((node) => node.attributes.containsKey(name));
      case 'data':
        value = elementNodes
            .map(
              (node) => node
                  .querySelectorAll('script,style')
                  .map((child) => child.text)
                  .join(''),
            )
            .join('');
      case 'tagName':
        value = elementNodes.firstOrNull?.localName ?? '';
      case 'id':
        value = elementNodes.firstOrNull?.id ?? '';
      case 'className':
        value = elementNodes.firstOrNull?.className ?? '';
      case 'select':
      case 'selectFirst':
        final selector = textArgument();
        final found = LinkedHashSet<Node>.identity();
        try {
          for (final node in selected) {
            found.addAll(_select(node, selector));
          }
        } on FormatException {
          throw const EngineException(
            'legacy.unsupported_dom_selector',
            'Selector is outside the supported CSS dialect',
          );
        }
        value = operation == 'selectFirst' ? found.firstOrNull : found.toList();
      case 'getElementsByTag':
        final name = textArgument();
        value = _descendants(selected)
            .whereType<Element>()
            .where(
              (node) => name == '*' || node.localName == name.toLowerCase(),
            )
            .toList();
      case 'getElementsByClass':
        final name = textArgument();
        value = _descendants(selected)
            .whereType<Element>()
            .where((node) => node.classes.contains(name))
            .toList();
      case 'getElementById':
        final name = textArgument();
        value = _descendants(selected)
            .whereType<Element>()
            .where((node) => node.id == name)
            .firstOrNull;
      case 'parent':
        value = selected.firstOrNull?.parentNode;
      case 'children':
        value = elementNodes.expand((node) => node.children).toList();
      case 'nextElementSibling':
        value = elementNodes.firstOrNull?.nextElementSibling;
      case 'previousElementSibling':
        value = elementNodes.firstOrNull?.previousElementSibling;
      case 'body':
        value = selected.whereType<Document>().firstOrNull?.body;
      case 'head':
        value = selected.whereType<Document>().firstOrNull?.head;
      case 'title':
        value =
            selected
                .whereType<Document>()
                .firstOrNull
                ?.querySelector('title')
                ?.text ??
            '';
    }
    if (value is Node) return forest.nodeMarker(value);
    if (value is List<Node>) return forest.listMarker(value);
    return value;
  }

  static List<Element> _select(Node node, String selector) {
    if (node is Document) return node.querySelectorAll(selector);
    if (node is! Element) return [];
    final result = node.querySelectorAll(selector);
    final clone = node.clone(true);
    final wrapper = Element.tag('legacy-wrapper')..append(clone);
    if (wrapper.querySelectorAll(selector).contains(clone)) {
      result.insert(0, node);
    }
    return result;
  }

  static Iterable<Node> _descendants(Iterable<Node> roots) sync* {
    final seen = HashSet<Node>.identity();
    final stack = roots.toList().reversed.toList();
    while (stack.isNotEmpty) {
      final node = stack.removeLast();
      if (!seen.add(node)) continue;
      yield node;
      stack.addAll(node.nodes.reversed);
    }
  }

  static String _outerHtml(Node node) => node is Document
      ? node.outerHtml
      : node is Element
      ? node.outerHtml
      : node is Text
      ? node.data
      : node.toString();
  static Future<String> _text(Node node) async {
    final values = await RuleEvaluator(_NoScript()).evaluate(
      '@legacy:@text',
      node is Document ? node.outerHtml : node,
      ScriptContext(host: _NoHost()),
    );
    return values.map(RuleEvaluator.text).join(' ');
  }
}

class _Forest {
  _Forest(
    this.nodes,
    this.rows,
    this.baseUri, {
    this.documentId,
    this.ids,
    this.roots,
  });
  final List<Node> nodes;
  final List<Map<String, Object?>> rows;
  final String baseUri;
  final String? documentId;
  final List<Object?>? ids;
  final List<Object?>? roots;
  static _Forest capture(Document document, String baseUri) {
    final nodes = <Node>[document];
    for (var i = 0; i < nodes.length; i++) {
      nodes.addAll(nodes[i].nodes);
      if (nodes.length > PortableLegacyDomHost.maxNodes) {
        throw const EngineException(
          'legacy.dom_limit',
          'DOM snapshot exceeds resource bounds',
        );
      }
    }
    final indexes = HashMap<Node, int>.identity();
    for (var i = 0; i < nodes.length; i++) {
      indexes[nodes[i]] = i;
    }
    final rows = nodes
        .map(
          (node) => <String, Object?>{
            'kind': node is Document
                ? 'document'
                : node is Element
                ? 'element'
                : node is Text
                ? 'text'
                : node is Comment
                ? 'comment'
                : node is DocumentType
                ? 'doctype'
                : 'unsupported',
            'baseUri': baseUri,
            if (node is Element) 'tag': node.localName,
            if (node is Element)
              'attributes': {
                for (final attr in node.attributes.entries)
                  attr.key.toString(): attr.value,
              },
            if (node is Text) 'value': node.data,
            if (node is Comment) 'value': node.data,
            if (node is DocumentType) 'name': node.name,
            'children': node.nodes.map((child) => indexes[child]!).toList(),
          },
        )
        .toList();
    return _Forest(nodes, rows, baseUri);
  }

  static _Forest restore(Map<String, Object?> data) {
    final raw = data['nodes'];
    if (!const [1, 2].contains(data['schemaVersion']) ||
        raw is! List ||
        raw.isEmpty ||
        raw.length > PortableLegacyDomHost.maxNodes) {
      throw const EngineException(
        'legacy.invalid_dom',
        'Unsupported or oversized DOM table',
      );
    }
    final rows = raw.map((row) {
      if (row is! Map) {
        throw const EngineException(
          'legacy.invalid_dom',
          'DOM table row must be an object',
        );
      }
      return Map<String, Object?>.from(row);
    }).toList();
    final parents = List<int>.filled(rows.length, 0);
    final edges = <List<int>>[];
    for (var i = 0; i < rows.length; i++) {
      final children = rows[i]['children'];
      if (children is! List ||
          children.any(
            (child) =>
                child is! int ||
                child < 0 ||
                child >= rows.length ||
                child == i,
          )) {
        throw const EngineException('legacy.invalid_dom', 'Invalid DOM edges');
      }
      for (final child in children.cast<int>()) {
        if (++parents[child] > 1) {
          throw const EngineException(
            'legacy.invalid_dom',
            'DOM nodes must have one parent',
          );
        }
      }
      edges.add(children.cast<int>());
    }
    final queue = Queue<int>.from([
      for (var i = 0; i < parents.length; i++)
        if (parents[i] == 0) i,
    ]);
    var visited = 0;
    while (queue.isNotEmpty) {
      final index = queue.removeFirst();
      visited++;
      queue.addAll(edges[index]);
    }
    if (visited != rows.length) {
      throw const EngineException('legacy.invalid_dom', 'Cyclic DOM table');
    }
    final nodes = rows.map<Node>((row) {
      switch (row['kind']) {
        case 'document':
          return Document();
        case 'element':
          if (row['tag'] is! String || row['attributes'] is! Map) {
            throw const EngineException(
              'legacy.invalid_dom',
              'Invalid DOM element',
            );
          }
          final node = Element.tag(row['tag'] as String);
          for (final attr in (row['attributes'] as Map).entries) {
            if (attr.key is! String || attr.value is! String) {
              throw const EngineException(
                'legacy.invalid_dom',
                'Invalid DOM attribute',
              );
            }
            node.attributes[attr.key as String] = attr.value as String;
          }
          return node;
        case 'text':
        case 'data':
        case 'cdata':
          return Text(row['value'] as String? ?? '');
        case 'comment':
          return Comment(row['value'] as String? ?? '');
        case 'doctype':
          return DocumentType(row['name'] as String? ?? 'html', '', '');
        default:
          throw const EngineException(
            'legacy.unsupported_dom_kind',
            'Unsupported DOM node kind',
          );
      }
    }).toList();
    for (var i = 0; i < nodes.length; i++) {
      for (final child in edges[i]) {
        nodes[i].nodes.add(nodes[child]);
      }
    }
    if (data['schemaVersion'] == 2 &&
        (data['documentId'] is! String ||
            data['ids'] is! List ||
            (data['ids'] as List).length != nodes.length ||
            (data['ids'] as List).toSet().length != nodes.length)) {
      throw const EngineException(
        'legacy.invalid_dom',
        'Invalid stable DOM identities',
      );
    }
    return _Forest(
      nodes,
      rows,
      rows.first['baseUri'] as String? ?? '',
      documentId: data['documentId'] as String?,
      ids: data['ids'] as List?,
      roots: data['roots'] as List?,
    );
  }

  Map<String, Object?> _table() => {
    'schemaVersion': documentId == null ? 1 : 2,
    'nodes': rows,
    if (documentId != null) 'documentId': documentId,
    if (ids != null) 'ids': ids,
    if (roots != null) 'roots': roots,
  };
  Map<String, Object?> nodeMarker(Node node) => {
    '__legacyDom': {..._table(), 'index': nodes.indexOf(node)},
  };
  Map<String, Object?> listMarker(List<Node> list) => {
    '__legacyDomList': {
      ..._table(),
      'indexes': list.map(nodes.indexOf).toList(),
    },
  };
}

class _NoScript implements ScriptRuntime {
  @override
  Future<Object?> evaluate(
    String code,
    ScriptContext context, {
    CancellationToken? cancellation,
  }) async => throw const EngineException(
    'legacy.unsupported_dom_selector',
    'DOM selection cannot execute scripts',
  );
  @override
  Future<void> close() async {}
}

class _NoHost implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> args) async =>
      throw const EngineException(
        'legacy.unsupported_dom_selector',
        'DOM selection cannot invoke hosts',
      );
}
