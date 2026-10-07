import com.google.gson.GsonBuilder;
import kotlin.text.Regex;
import org.jsoup.Jsoup;
import org.apache.commons.text.StringEscapeUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Regenerate with Jsoup 1.23.2, Kotlin stdlib 2.4.10 and Gson 2.13.2 on the classpath.
 * Mirrors AnalyzeByJSoup text extraction and AnalyzeRule.replaceRegex's successful
 * Regex branch plus getString final HTML4 unescaping. String lists do not unescape.
 * The old scalar path joins extracted values before replacement; the list path
 * replaces each extracted item. The checked fixtures test both independently.
 */
public class LegacyLiteralReplacementGolden {
  public static void main(String[] args) {
    var cases = new ArrayList<Object>();
    add(cases, "chinese repeated", "<h2>广告正文广告</h2><h2>广告第二章</h2>", "广告", "书");
    add(cases, "ASCII non-overlap", "<h2>aaaa aa</h2><h2>baaa</h2>", "aa", "X");
    add(cases, "empty replacement", "<h2>REMOVE keep REMOVE</h2><h2>REMOVE</h2>", "REMOVE", "");
    add(cases, "no match", "<h2>第一章</h2><h2>第二章</h2>", "不存在", "保留");
    add(cases, "mixed unicode", "<h2>中文A中中文 A</h2><h2>中文😀中文</h2>", "中文", "书");
    add(cases, "empty extraction", "<h2> </h2>", "广告", "书");
    add(cases, "double entity extracted", "<h2>广告&amp;amp;正文</h2><h2>&amp;lt;第二章</h2>", "广告", "");
    add(cases, "replacement introduces entity", "<h2>广告正文广告</h2>", "广告", "&amp;");
    add(cases, "replacement matches extracted entity", "<h2>&amp;amp;正文</h2>", "&amp;", "&lt;");
    System.out.println(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(cases));
  }
  private static void add(List<Object> cases, String name, String html, String pattern, String replacement) {
    var extracted = new ArrayList<String>();
    for (var element : Jsoup.parse(html).getElementsByTag("h2")) {
      var value = element.text();
      if (!value.isEmpty()) extracted.add(value);
    }
    var regex = new Regex(pattern);
    var expectedList = new ArrayList<String>();
    for (var value : extracted) expectedList.add(regex.replace(value, replacement));
    var result = new LinkedHashMap<String, Object>();
    result.put("name", name); result.put("html", html);
    result.put("pattern", pattern); result.put("replacement", replacement);
    result.put("extracted", extracted); result.put("expectedList", expectedList);
    var expectedFields = new ArrayList<String>();
    for (var value : expectedList) expectedFields.add(StringEscapeUtils.unescapeHtml4(value));
    result.put("expectedFields", expectedFields);
    result.put("expectedString", StringEscapeUtils.unescapeHtml4(regex.replace(String.join("\n", extracted), replacement)));
    cases.add(result);
  }
}
