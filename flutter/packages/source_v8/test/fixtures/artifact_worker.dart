import 'dart:io';

import '../../hook/build.dart' show artifact;

Future<void> main(List<String> args) async {
  final result = await artifact(
    Directory(args[0]),
    'fixture.zip',
    url: args[1],
    digest: args[2],
  );
  stdout.write(await File('${result.path}/include/api.h').readAsString());
}
