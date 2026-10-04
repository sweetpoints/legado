import 'dart:convert';
import 'dart:io';

import 'package:source_migration/source_migration.dart';
import 'package:source_legacy/source_legacy.dart';
import 'package:source_tools/source_tools.dart';
import 'package:source_v8/source_v8.dart';

Future<void> main(List<String> arguments) async {
  final cli = SourceCli(
    importLegacy: (input) async => LegacySourceImporter().import(input).source,
    migrate: (input) async => SourceMigrator().migrate(input).toJson(),
    execute: (source, stage, variables) async {
      final engine = createCliEngine(
        source,
        V8Runtime(
          prelude: source.metadata['legacy'] == true ? legacyScriptPrelude : '',
        ),
      );
      try {
        return await engine.execute(source, stage, input: variables);
      } finally {
        await engine.close();
      }
    },
  );
  final result = await cli.run(arguments);
  stdout.writeln(jsonEncode(result.json));
  exitCode = result.exitCode;
}
