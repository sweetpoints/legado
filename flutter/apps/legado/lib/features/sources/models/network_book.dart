import 'package:freezed_annotation/freezed_annotation.dart';

part 'network_book.freezed.dart';

/// A search/detail preview belongs to its source; it is not a bookshelf row.
@freezed
abstract class NetworkBook with _$NetworkBook {
  const NetworkBook._();
  const factory NetworkBook({
    required String sourceId,
    required Map<String, Object?> metadata,
  }) = _NetworkBook;

  String get url => metadata['bookUrl']?.toString() ?? '';
  String get name => metadata['name']?.toString() ?? '';
  String get author => metadata['author']?.toString() ?? '';
}
