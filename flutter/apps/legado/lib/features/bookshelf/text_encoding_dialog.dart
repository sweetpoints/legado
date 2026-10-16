import 'package:material_ui/material_ui.dart';

import '../../app/local_text_decoder.dart';
import '../../l10n/generated/app_localizations.dart';

/// Material 3 dialog kept off the normal one-step automatic import path.
class TextEncodingDialog extends StatelessWidget {
  const TextEncodingDialog({super.key});

  @override
  Widget build(BuildContext context) {
    final strings = AppLocalizations.of(context);
    final labels = {
      LocalTextEncoding.utf8: strings.shelfEncodingUtf8,
      LocalTextEncoding.gbk: strings.shelfEncodingGbk,
      LocalTextEncoding.utf16LittleEndian: strings.shelfEncodingUtf16Le,
      LocalTextEncoding.utf16BigEndian: strings.shelfEncodingUtf16Be,
    };
    return AlertDialog(
      title: Text(strings.shelfChooseTextEncoding),
      contentPadding: const EdgeInsets.fromLTRB(12, 16, 12, 0),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text(strings.shelfTextEncodingHint),
            ),
            const SizedBox(height: 12),
            for (final entry in labels.entries)
              ListTile(
                key: ValueKey('txt-encoding-${entry.key.name}'),
                title: Text(entry.value),
                onTap: () => Navigator.of(context).pop(entry.key),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(strings.cancel),
        ),
      ],
    );
  }
}
