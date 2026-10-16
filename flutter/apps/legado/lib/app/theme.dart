import 'package:material_ui/material_ui.dart';

ThemeData readerTheme(Brightness brightness) => ThemeData(
  useMaterial3: true,
  colorScheme: ColorScheme.fromSeed(
    seedColor: const Color(0xff4355a5),
    brightness: brightness,
  ),
);
