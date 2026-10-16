import 'package:freezed_annotation/freezed_annotation.dart';

part 'rss_models.freezed.dart';

@Freezed(fromJson: false, toJson: false)
abstract class RssSource with _$RssSource {
  const RssSource._();
  const factory RssSource({
    required String url,
    required String name,
    @Default(<String, Object?>{}) Map<String, Object?> original,
  }) = _RssSource;

  /// HTML/JS subscription rules need the legacy rule host, not an XML parser.
  bool get requiresRuleEngine => [
    'ruleArticles',
    'ruleContent',
    'ruleTitle',
    'ruleLink',
    'sortUrl',
  ].any((key) => original[key]?.toString().trim().isNotEmpty ?? false);

  Map<String, Object?> toJson() => {
    ...original,
    'sourceUrl': url,
    'sourceName': name,
  };
  factory RssSource.fromJson(Map<String, Object?> json) {
    final url = (json['sourceUrl'] ?? json['url'])?.toString().trim() ?? '';
    final uri = Uri.tryParse(url);
    if (uri == null ||
        !['http', 'https'].contains(uri.scheme) ||
        uri.host.isEmpty) {
      throw const FormatException('rssInvalidUrl');
    }
    return RssSource(
      url: url,
      name: (json['sourceName'] ?? json['name'] ?? url).toString(),
      original: Map.of(json),
    );
  }
}

@Freezed(fromJson: false, toJson: false)
abstract class RssArticle with _$RssArticle {
  const RssArticle._();
  const factory RssArticle({
    required String id,
    required String title,
    required String link,
    required String content,
    @Default('') String published,
    @Default(false) bool read,
    @Default(false) bool starred,
    @Default(<String, Object?>{}) Map<String, Object?> original,
  }) = _RssArticle;
  Map<String, Object?> toJson() => {
    ...original,
    'id': id,
    'title': title,
    'link': link,
    'content': content,
    'published': published,
    'read': read,
    'starred': starred,
  };
  factory RssArticle.fromJson(Map<String, Object?> json) => RssArticle(
    id: json['id'] as String,
    title: json['title'] as String,
    link: json['link'] as String,
    content: json['content'] as String,
    published: json['published'] as String? ?? '',
    read: json['read'] as bool? ?? false,
    starred: json['starred'] as bool? ?? false,
    original: Map.of(json),
  );
}
