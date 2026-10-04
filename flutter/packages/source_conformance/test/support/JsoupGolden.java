import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;

/** Reproduces getResultLast semantics from AnalyzeByJSoup.kt for fixed inputs. */
class JsoupGolden {
  static final String HTML = "<section class='root'><div class='book'><p class='title'>  Alpha\n <b>Beta</b><br> Gamma </p><a href='/one'>one</a><a href='/one'>duplicate</a><a href=' '>empty</a></div><div class='book'><p class='title'>Delta</p><a href='/two'>two</a></div><div class='book'><p class='title'>Third</p></div><div class='book'><p class='title'>Fourth</p></div></section>";
  static String quote(String s) { return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\""; }
  static String json(List<String> items) { return "["+String.join(",",items.stream().map(JsoupGolden::quote).toList())+"]"; }
  static List<String> texts(Elements nodes) { return nodes.stream().map(Element::text).filter(s->!s.isEmpty()).toList(); }
  public static void main(String[] args) {
    Element root=Jsoup.parse(HTML);
    Elements books=root.getElementsByClass("book");
    LinkedHashMap<String,List<String>> cases=new LinkedHashMap<>();
    cases.put("class.book@tag.p@text",texts(root.getElementsByClass("book").select("p")));
    cases.put("class.book.0@tag.p@ownText",List.of(books.get(0).selectFirst("p").ownText()));
    cases.put("class.book.0@tag.p@textNodes",List.of(String.join("\n",books.get(0).selectFirst("p").textNodes().stream().map(n->n.text().trim()).filter(s->!s.isEmpty()).toList())));
    LinkedHashSet<String> attrs=new LinkedHashSet<>();for(Element a:root.select("a")){String value=a.attr("href");if(!value.isBlank())attrs.add(value);}
    cases.put("tag.a@href",new ArrayList<>(attrs));
    cases.put("class.book[-1,0,2]@tag.p@text",texts(new Elements(books.get(3),books.get(0),books.get(2)).select("p")));
    cases.put("class.book[0:2]@tag.p@text",texts(new Elements(books.get(0),books.get(1),books.get(2)).select("p")));
    cases.put("class.book[-1:0:2]@tag.p@text",texts(new Elements(books.get(3),books.get(1)).select("p")));
    cases.put("class.book!0:2@tag.p@text",texts(new Elements(books.get(1),books.get(3)).select("p")));
    cases.put("class.book[!0,-1]@tag.p@text",texts(new Elements(books.get(1),books.get(2)).select("p")));
    System.out.println("{\"html\":"+quote(HTML)+",\"cases\":{");
    int count=0;for(var entry:cases.entrySet())System.out.println((count++>0?",":"")+quote("@legacy:"+entry.getKey())+":"+json(entry.getValue()));
    System.out.println("}}");
  }
}
