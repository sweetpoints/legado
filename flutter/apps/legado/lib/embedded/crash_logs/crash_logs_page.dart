import 'package:material_ui/material_ui.dart';

import '../app_logs/app_logs_page.dart';
import '../app_logs/diagnostics_bridge.dart';

class CrashLogsPage extends StatelessWidget {
  const CrashLogsPage({super.key, this.bridge});
  final DiagnosticsBridge? bridge;
  @override
  Widget build(BuildContext context) =>
      DiagnosticsPage(crash: true, bridge: bridge);
}
