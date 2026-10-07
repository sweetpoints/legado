import 'dart:convert';

/// Adapts the historical positional mainJs entry points to the v1 input object.
/// Each call evaluates the original program in its own lexical environment.
String wrapLegacyMainJs(String script, Map<String, Object?> original) {
  final factoryBody =
      '''
return (function() {
$script
return {
  search: typeof search === 'function' ? search : null,
  explore: typeof explore === 'function' ? explore : null,
  getBookInfo: typeof getBookInfo === 'function' ? getBookInfo : null,
  getChapters: typeof getChapters === 'function' ? getChapters : null,
  getContent: typeof getContent === 'function' ? getContent : null
};
})();
''';
  return '''
const __legacyMainJs = (() => {
  const originalSource = ${jsonEncode(original)};
  async function invoke(operation, input) {
    const bookValue = input.book && typeof input.book === 'object'
      ? {...input.book} : {...input};
    const chapterValue = input.chapter && typeof input.chapter === 'object'
      ? {...input.chapter} : {url:input.chapterUrl, title:input.chapterTitle};
    const pageValue = input.page == null ? 1 : input.page;
    const functions = (new Function('key', 'page', 'url', 'book', 'chapter',
      'nextChapterUrl', 'source', 'sourceApi', ${jsonEncode(factoryBody)}))(
      input.key, pageValue, input.exploreUrl, bookValue, chapterValue,
      input.nextChapterUrl, originalSource, originalSource);
    const fn = functions[operation];
    if (!fn) {
      if (operation === 'getBookInfo') return bookValue;
      throw new Error('Missing legacy function ' + operation);
    }
    let value;
    switch (operation) {
      case 'search': value = await fn(input.key, pageValue); break;
      case 'explore': value = await fn(input.exploreUrl, pageValue); break;
      case 'getBookInfo': value = await fn(bookValue); break;
      case 'getChapters': value = await fn(bookValue); break;
      case 'getContent': value = await fn(chapterValue, bookValue, input.nextChapterUrl); break;
    }
    // Historical content strings are literal text, including JSON-looking text.
    if (operation === 'getContent') {
      return value == null ? '' : typeof value === 'string' ? value : JSON.stringify(value);
    }
    if (operation === 'getBookInfo' && (value == null || value === '')) return bookValue;
    if (typeof value === 'string') value = JSON.parse(value);
    if (operation === 'getBookInfo') {
      if (!value || typeof value !== 'object' || Array.isArray(value))
        throw new Error('Legacy getBookInfo must return an object');
    } else if (!Array.isArray(value)) {
      throw new Error('Legacy ' + operation + ' must return an array');
    }
    return value;
  }
  return {
    search: input => invoke('search', input),
    explore: input => invoke('explore', input),
    getBookInfo: input => invoke('getBookInfo', input),
    getChapters: input => invoke('getChapters', input),
    getContent: input => invoke('getContent', input)
  };
})();
const search = __legacyMainJs.search;
const explore = __legacyMainJs.explore;
const getBookInfo = __legacyMainJs.getBookInfo;
const getChapters = __legacyMainJs.getChapters;
const getContent = __legacyMainJs.getContent;
''';
}
