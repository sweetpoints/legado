import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

/// Native ManagerViewModel is authoritative. Commands never modify a Dart copy
/// of business state, and are delivered in order including every draft keystroke.
class RssManagerBridge {
  RssManagerBridge({
    this.channel = const MethodChannel('legado/rss_manager'),
    this.events = const EventChannel('legado/rss_manager/state'),
  });
  final MethodChannel channel;
  final EventChannel events;
  bool _closed = false;
  Future<void> _commands = Future.value();
  final _lifetime = StreamController<void>.broadcast();
  final _acks = StreamController<RssManagerSnapshot>.broadcast();
  String? _binding;
  int _sequence = 0;
  int _revision = -1;
  Map<String, Object?>? _rejectedField;
  Future<void> command(String op, [Map<String, Object?> arguments = const {}]) {
    if (_closed) return Future.error(StateError('rss_manager_closed'));
    if (op == 'back' && _binding == null) {
      return const MethodChannel('legado/embedded_ui')
          .invokeMethod<void>('navigation.close');
    }
    final immutable = Map<String, Object?>.from(
      jsonDecode(jsonEncode({'op': op, ...arguments})) as Map,
    );
    final next = _commands.then((_) async {
      if (_closed || _binding == null) {
        throw StateError('rss_manager_not_bound');
      }
      final field = op == 'query' || op == 'draft';
      if (_rejectedField != null && (!field || _rejectedField!['op'] != op)) {
        throw StateError('rss_manager_field_rejected');
      }
      try {
        final response = _decode(
          await channel.invokeMethod<String>('command', {
            ...immutable,
            'binding': _binding,
            'sequence': ++_sequence,
          }),
        );
        if (response.data['binding'] != _binding) {
          throw StateError('rss_manager_binding_changed');
        }
        if (field) {
          final key = op == 'query' ? 'query' : 'draft';
          if (response.data[key] != immutable['value'] ||
              response.data['${key}Start'] != immutable['start'] ||
              response.data['${key}End'] != immutable['end']) {
            throw StateError('rss_manager_field_not_acknowledged');
          }
          _rejectedField = null;
        }
        // Method and Event channels may arrive in either order. Publish the
        // complete ACK through the same revision gate so rows cannot be lost.
        _acks.add(response);
      } catch (_) {
        if (field) _rejectedField = immutable;
        rethrow;
      }
    });
    _commands = next.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return next;
  }

  Future<void> retryField() async {
    final field = _rejectedField;
    if (field == null) return;
    await command(field['op']! as String, {...field}..remove('op'));
  }

  static RssManagerSnapshot _decode(Object? value) {
    if (value is! String || utf8.encode(value).length > 16 * 1024 * 1024) {
      throw const FormatException('invalid_rss_manager_snapshot');
    }
    try {
      return RssManagerSnapshot.fromJson(
        Map<String, Object?>.from(jsonDecode(value) as Map),
      );
    } on FormatException {
      rethrow;
    } catch (_) {
      throw const FormatException('invalid_rss_manager_snapshot');
    }
  }

  Stream<RssManagerSnapshot> snapshots() {
    if (_closed) return Stream.error(StateError('rss_manager_closed'));
    late StreamController<RssManagerSnapshot> controller;
    StreamSubscription<Object?>? subscription;
    StreamSubscription<void>? lifetime;
    StreamSubscription<RssManagerSnapshot>? acknowledgements;
    var disposed = false;
    var revision = 0;
    void deliver(Object? value) {
      if (disposed) return;
      try {
        final snapshot = value is RssManagerSnapshot ? value : _decode(value);
        final binding = snapshot.data['binding'] as String;
        final revision = snapshot.data['revision'] as int;
        if (_binding != null && _binding != binding) {
          throw StateError('rss_manager_binding_changed');
        }
        _binding = binding;
        if (revision <= _revision) return;
        _revision = revision;
        controller.add(snapshot);
      } catch (error, stack) {
        controller.addError(error, stack);
      }
    }

    Future<void> stop() async {
      if (disposed) return;
      disposed = true;
      await subscription?.cancel();
      await lifetime?.cancel();
      await acknowledgements?.cancel();
    }

    controller = StreamController(
      onListen: () {
        acknowledgements = _acks.stream.listen(deliver);
        lifetime = _lifetime.stream.listen(
          (_) => unawaited(stop().then((_) => controller.close())),
        );
        subscription = events.receiveBroadcastStream().listen(
          (value) {
            revision++;
            deliver(value);
          },
          onError: (Object error, StackTrace stack) {
            revision++;
            if (!disposed) controller.addError(error, stack);
          },
          onDone: () {
            revision++;
            if (!disposed) {
              controller.addError(StateError('rss_manager_state_disconnected'));
              unawaited(stop().then((_) => controller.close()));
            }
          },
        );
        final initialRevision = revision;
        unawaited(
          channel
              .invokeMethod<String>('snapshot')
              .then(
                (value) {
                  if (!disposed && revision == initialRevision) deliver(value);
                },
                onError: (Object error, StackTrace stack) {
                  if (!disposed && revision == initialRevision) {
                    controller.addError(error, stack);
                  }
                },
              ),
        );
      },
      onCancel: stop,
    );
    return controller.stream;
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _lifetime.add(null);
    await _lifetime.close();
    await _acks.close();
  }
}

class RssManagerSnapshot {
  RssManagerSnapshot(this.data);
  final Map<String, Object?> data;
  factory RssManagerSnapshot.fromJson(Map<String, Object?> data) {
    if (data['schemaVersion'] != 1 ||
        data['binding'] is! String ||
        data['revision'] is! int ||
        data['rows'] is! List ||
        data['loaded'] is! bool ||
        data['busy'] is! bool ||
        data['pending'] is! bool ||
        data['waitingNative'] is! bool ||
        data['query'] is! String ||
        data['draft'] is! String ||
        data['groups'] is! List ||
        data['selected'] is! List ||
        data['history'] is! List ||
        data['targets'] is! List ||
        data['queryStart'] is! int ||
        data['queryEnd'] is! int ||
        data['draftStart'] is! int ||
        data['draftEnd'] is! int ||
        data['scrollIndex'] is! int ||
        data['scrollOffset'] is! int ||
        data['error'] is! bool ||
        (data['dialog'] != null &&
            ![
              'Delete',
              'AddGroup',
              'RemoveGroup',
              'ImportUrl',
              'ExportResult',
              'Passphrase',
            ].contains(data['dialog']))) {
      throw const FormatException('invalid_rss_manager_snapshot');
    }
    for (final key in ['query', 'draft']) {
      final text = data[key] as String;
      final start = data['${key}Start'] as int;
      final end = data['${key}End'] as int;
      if (start < 0 || end < 0 || start > text.length || end > text.length) {
        throw const FormatException('invalid_rss_manager_selection');
      }
    }
    if ((data['binding'] as String).isEmpty ||
        (data['revision'] as int) < 0 ||
        (data['scrollIndex'] as int) < 0 ||
        (data['scrollOffset'] as int) < 0) {
      throw const FormatException('invalid_rss_manager_binding');
    }
    final feedback = data['feedback'];
    if (feedback != null &&
        (feedback is! Map ||
            feedback['summary'] is! String ||
            feedback['canEncode'] is! bool)) {
      throw const FormatException('invalid_rss_manager_feedback');
    }
    final rows = (data['rows'] as List)
        .map((r) => Map<String, Object?>.from(r as Map))
        .toList();
    final ids = <String>{};
    for (final r in rows) {
      if (r['id'] is! String ||
          !RegExp(r'^[0-9a-f]{64}$').hasMatch(r['id'] as String) ||
          !ids.add(r['id'] as String) ||
          r['name'] is! String ||
          r['enabled'] is! bool) {
        throw const FormatException('invalid_rss_manager_row');
      }
    }
    for (final key in ['groups', 'selected', 'history', 'targets']) {
      if ((data[key] as List).any((v) => v is! String)) {
        throw const FormatException('invalid_rss_manager_list');
      }
    }
    return RssManagerSnapshot(Map.unmodifiable({...data, 'rows': rows}));
  }
  bool get editable =>
      data['loaded'] == true &&
      data['busy'] == false &&
      data['pending'] == false;
  bool get nativeEditable => editable && data['waitingNative'] == false;
  List<Map<String, Object?>> get rows =>
      (data['rows'] as List).cast<Map<String, Object?>>();
  List<String> strings(String key) => (data[key] as List).cast<String>();
  List<String> get selection => rows
      .where((r) => strings('selected').contains(r['id']))
      .map((r) => r['id'] as String)
      .toList();
}
