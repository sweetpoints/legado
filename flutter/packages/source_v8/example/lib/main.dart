import 'package:flutter/material.dart';
import 'package:source_engine/source_engine.dart';
import 'package:source_v8/source_v8.dart';

void main() => runApp(const MaterialApp(home: Example()));

class Example extends StatefulWidget {
  const Example({super.key});
  @override
  State<Example> createState() => _ExampleState();
}

class _ExampleState extends State<Example> {
  final runtime = V8Runtime();
  String value = 'Run V8';
  @override
  void dispose() {
    runtime.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: TextButton(
        onPressed: () async {
          final result = await runtime.evaluate(
            '21*2',
            ScriptContext(host: _Host()),
          );
          if (mounted) setState(() => value = 'V8 ${runtime.version}: $result');
        },
        child: Text(value),
      ),
    ),
  );
}

class _Host implements ScriptHost {
  @override
  Future<Object?> call(String method, List<Object?> arguments) =>
      throw UnsupportedError(method);
}
