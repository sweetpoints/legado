import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

/** Golden outputs from the pinned original Jsoup dependency, not Dart helpers. */
class JsoupWhitespaceGolden {
  static String q(String s) {return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}
  static void emit(String html,String selector,String method) {
    Element node=Jsoup.parse(html).selectFirst(selector);
    String expected=method.equals("text")?node.text():method.equals("ownText")?node.ownText():String.join("\n",node.textNodes().stream().map(n->n.text().trim()).filter(s->!s.isEmpty()).toList());
    System.out.print("{\"html\":"+q(html)+",\"rule\":"+q("@legacy:"+selector+"@"+method)+",\"expected\":"+q(expected)+"}");
  }
  public static void main(String[] args) {
    String[][] cases={
      {"<pre>a  b\n c</pre>","pre","text"},
      {"<pre> a  <b>b\n c</b>  d </pre>","pre","text"},
      {"<pre>a\n<br>b</pre>","pre","text"},
      {"<pre>a <span><div>b  c</div></span>d</pre>","pre","text"},
      {"<div> before   <pre>a  b\n c</pre> after   text </div>","div","text"},
      {"<textarea>a  b\n c</textarea>","textarea","text"},
      {"<title>a  b\n c</title>","title","text"},
      {"<plaintext>a  b\n c", "plaintext","text"},
      {"<pre>a  <b>other</b>\n c<br>d</pre>","pre","ownText"},
      {"<textarea>a  b\n c</textarea>","textarea","ownText"},
      {"<pre>a  b\n c</pre>","pre","textNodes"},
      {"<pre>&nbsp;a&nbsp;  b&nbsp;</pre>","pre","text"},
      {"<div>before<textarea>a  b\n c</textarea>after</div>","div","text"},
      {"<pre><span><i><b><em><strong>x  y</strong></em></b></i></span></pre>","pre","text"},
      {"<pre><span><i><b><em><strong><u>x  y</u></strong></em></b></i></span></pre>","pre","text"},
      {"<p>a  b\n c</p>","p","text"}
    };
    System.out.print("{\"dependency\":\"org.jsoup:jsoup:1.23.2\",\"cases\":[");
    for(int i=0;i<cases.length;i++){if(i>0)System.out.print(",");emit(cases[i][0],cases[i][1],cases[i][2]);}
    System.out.println("]}");
  }
}
