import 'dart:convert';
import 'dart:io';

import 'package:source_engine/source_engine.dart';

/// JSON-only CLI responses and stable process exit codes.
class CliResult {
  const CliResult(this.exitCode, this.json);
  final int exitCode;
  final Map<String, Object?> json;
}

typedef MigrateSource = Future<Map<String, Object?>> Function(
  Map<String, Object?> source,
);
typedef ExecuteSource = Future<Object?> Function(
  SourceDefinition source,
  String stage,
  Map<String, Object?> variables,
);

class SourceCli {
  SourceCli({required this.migrate, required this.execute});
  final MigrateSource migrate;
  final ExecuteSource execute;

  Future<CliResult> run(List<String> arguments) async {
    try {
      if (arguments.isEmpty || arguments.first == '--help') {
        return CliResult(arguments.isEmpty ? 64 : 0, {
          'usage': 'source_tools validate FILE | migrate FILE --output FILE [--report FILE] | execute FILE STAGE [--variables JSON]',
          'stages': ['search', 'explore', 'info', 'toc', 'content'],
          'exitCodes': {
            '0': 'success',
            '1': 'execution failed',
            '2': 'invalid source',
            '3': 'migration needs review',
            '64': 'usage',
            '73': 'output exists',
            '74': 'file I/O',
          },
        });
      }
      final command = arguments.first;
      if (!['validate', 'migrate', 'execute'].contains(command) ||
          arguments.length < 2) {
        throw const _Usage('Unknown command or missing source path');
      }
      final path = arguments[1];
      final options = <String, String>{};
      var offset = 2;
      String? stage;
      if (command == 'execute') {
        if (arguments.length < 3) throw const _Usage('Missing execution stage');
        stage = arguments[2];
        if (!['search', 'explore', 'info', 'toc', 'content'].contains(stage)) {
          throw const _Usage('Unknown execution stage');
        }
        offset = 3;
      }
      final allowed = command == 'migrate'
          ? {'--output', '--report'}
          : command == 'execute'
          ? {'--variables'}
          : <String>{};
      while (offset < arguments.length) {
        final option = arguments[offset++];
        if (!allowed.contains(option) ||
            options.containsKey(option) ||
            offset == arguments.length) {
          throw _Usage('Invalid or duplicate option $option');
        }
        options[option] = arguments[offset++];
      }
      if (command == 'migrate' && !options.containsKey('--output')) {
        throw const _Usage('Migration requires --output');
      }
      final json = _object(jsonDecode(await File(path).readAsString()));
      if (command == 'migrate') {
        final output = File(options['--output']!);
        final report = File(
          options['--report'] ?? '${output.path}.report.json',
        );
        await _checkDestinations(File(path), output, report);
        final result = await migrate(json);
        final candidate = result['candidate'];
        // A report-only outcome must never be advertised as an executable source.
        if (candidate == null) {
          await _writeExclusive(report, result);
        } else {
          await _writeExclusive(output, candidate);
          await _writeExclusive(report, {...result}..remove('candidate'));
        }
        final status = result['status'];
        return CliResult(status == 'ready' ? 0 : 3, {
          'ok': status == 'ready',
          'command': command,
          'original': path,
          if (candidate != null) 'candidate': output.path,
          'report': report.path,
          'status': status,
        });
      }
      final source = SourceDefinition.fromJson(json);
      if (command == 'validate') {
        return CliResult(0, {
          'ok': true,
          'command': command,
          'id': source.id,
          'schemaVersion': source.schemaVersion,
        });
      }
      final variables = _object(jsonDecode(options['--variables'] ?? '{}'));
      final result = await execute(source, stage!, variables);
      return CliResult(0, {
        'ok': true,
        'command': command,
        'stage': stage,
        'result': result,
      });
    } on _Usage catch (error) {
      return _failure(64, 'usage', error.message);
    } on _OutputExists catch (error) {
      return _failure(73, 'output_exists', error.message);
    } on FileSystemException catch (error) {
      return _failure(74, 'file_io', '${error.message}: ${error.path}');
    } on FormatException catch (error) {
      return _failure(2, 'invalid_json', error.message);
    } on EngineException catch (error) {
      return _failure(
        error.code == 'invalid_source' || error.code == 'unsupported_version'
            ? 2
            : 1,
        error.code,
        error.message,
      );
    } on TypeError {
      return _failure(2, 'invalid_source', 'Source fields have invalid types');
    } catch (error) {
      return _failure(1, 'execution_failed', error.toString());
    }
  }

  static Map<String, Object?> _object(Object? value) {
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Expected a JSON object');
    }
    return Map<String, Object?>.from(value);
  }

  static Future<void> _checkDestinations(
    File input,
    File output,
    File report,
  ) async {
    final paths = [
      input.absolute.uri.normalizePath(),
      output.absolute.uri.normalizePath(),
      report.absolute.uri.normalizePath(),
    ];
    if (paths.toSet().length != paths.length) {
      throw const _Usage(
        'Original, candidate and report paths must be different',
      );
    }
    for (final destination in [output, report]) {
      if (await FileSystemEntity.type(destination.path, followLinks: false) !=
          FileSystemEntityType.notFound) {
        throw _OutputExists(destination.path);
      }
    }
  }

  static Future<void> _writeExclusive(File file, Object? value) async {
    await file.parent.create(recursive: true);
    // Lock in exclusive ownership so a concurrent migration cannot overwrite it.
    await file.create(exclusive: true);
    final handle = await file.open(mode: FileMode.writeOnly);
    try {
      await handle.writeString(
        '${const JsonEncoder.withIndent('  ').convert(value)}\n',
      );
    } finally {
      await handle.close();
    }
  }

  static CliResult _failure(int exitCode, String code, String message) =>
      CliResult(exitCode, {
        'ok': false,
        'error': {'code': code, 'message': message},
      });
}

class _Usage implements Exception {
  const _Usage(this.message);
  final String message;
}

class _OutputExists implements Exception {
  const _OutputExists(this.message);
  final String message;
}
