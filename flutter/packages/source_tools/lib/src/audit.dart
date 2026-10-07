import 'dart:convert';

import 'package:crypto/crypto.dart';

typedef MigrationFunction = Future<Map<String, Object?>> Function(
  Map<String, Object?> source,
);

/// Every input position receives a report, including malformed entries.
class SourceAudit {
  SourceAudit(this.summary, this.entries);
  final Map<String, Object?> summary;
  final List<Map<String, Object?>> entries;

  static String _capability(Map issue) {
    final path = issue['path']?.toString().split('.').first;
    const known = {
      'loginUrl',
      'loginUi',
      'loginCheckJs',
      'webView',
      'header',
      'jsLib',
      'bookSourceType',
      'searchUrl',
      'exploreUrl',
      'ruleSearch',
      'ruleExplore',
      'ruleBookInfo',
      'ruleToc',
      'ruleContent',
      'script',
    };
    return known.contains(path)
        ? path!
        : issue['code']?.toString() ?? 'unknown';
  }

  static Future<SourceAudit> inspect(
    List<int> bytes,
    MigrationFunction migrate,
  ) async {
    final decoded = jsonDecode(utf8.decode(bytes));
    final inputs = decoded is List ? decoded : [decoded];
    final entries = <Map<String, Object?>>[];
    final capabilities = <String, int>{};
    final issueCodes = <String, int>{};
    final statuses = <String, int>{
      'unverified': 0,
      'manualRequired': 0,
      'noExecution': 0,
    };
    for (var index = 0; index < inputs.length; index++) {
      Map<String, Object?> result;
      try {
        final input = inputs[index];
        if (input is! Map<String, dynamic>) {
          throw const FormatException('Expected a source object');
        }
        result = await migrate(Map<String, Object?>.from(input));
        final issues = result['issues'] as List? ?? [];
        result = {
          ...result,
          'status': issues.isNotEmpty || result['status'] == 'manualRequired'
              ? 'manualRequired'
              : 'unverified',
        };
      } catch (_) {
        // Error text can contain source data; the audit output retains only a stable code.
        result = {
          'status': 'noExecution',
          'issues': [
            {
              'code': 'input.invalid_source',
              'path': '',
              'message': 'Entry could not be imported.',
            },
          ],
        };
      }
      final issues = result['issues'] as List? ?? [];
      for (final issue in issues) {
        final code = issue is Map
            ? issue['code']?.toString() ?? 'unknown'
            : 'unknown';
        issueCodes.update(code, (count) => count + 1, ifAbsent: () => 1);
        final capability = issue is Map ? _capability(issue) : 'unknown';
        capabilities.update(
          capability,
          (count) => count + 1,
          ifAbsent: () => 1,
        );
      }
      final status = result['status'] as String;
      statuses.update(status, (count) => count + 1, ifAbsent: () => 1);
      entries.add({
        ...result,
        'index': index,
        'verified': false,
        'executed': false,
        'inputSha256': sha256
            .convert(utf8.encode(jsonEncode(inputs[index])))
            .toString(),
      });
    }
    return SourceAudit({
      'reportVersion': 1,
      'inputSha256': sha256.convert(bytes).toString(),
      'sourceCount': inputs.length,
      'statusCounts': statuses,
      'issueCountsByCapability': capabilities,
      'issueCountsByCode': issueCodes,
      'verified': false,
      'executed': false,
      'entries': entries
          .map(
            (entry) => {
              'index': entry['index'],
              'status': entry['status'],
              'inputSha256': entry['inputSha256'],
              'issueCount': (entry['issues'] as List).length,
              'issues': (entry['issues'] as List)
                  .map(
                    (issue) => issue is Map
                        ? {
                            'code': issue['code'],
                            'capability': _capability(issue),
                          }
                        : {'code': 'unknown'},
                  )
                  .toList(),
            },
          )
          .toList(),
    }, entries);
  }
}
