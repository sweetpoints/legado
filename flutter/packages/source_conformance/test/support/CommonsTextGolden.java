import org.apache.commons.text.StringEscapeUtils;
/** Outputs from the original pinned JVM dependency; no Dart-derived expectations. */
class CommonsTextGolden {
  static String q(String s) {
    StringBuilder b=new StringBuilder("\"");
    for(char c:s.toCharArray()) { if(c=='"'||c=='\\')b.append('\\').append(c); else if(c<32)b.append(String.format("\\u%04x",(int)c)); else b.append(c); }
    return b.append('"').toString();
  }
  public static void main(String[] args) {
    String[] inputs={"&quot;&amp;&lt;&gt;","&nbsp;&copy;&reg;&eacute;","&Alpha;&alpha;&euro;&trade;","&apos;","&copy &amp","&#169; &#xA9; &#X1F600;","&#128; &#x80;","&#0; &#9; &#10;","&#169 &#xA9","&unknown; &#xZZ; &#;","&amp;copy;"};
    System.out.print("{\"dependency\":\"org.apache.commons:commons-text:1.13.1\",\"cases\":[");
    for(int i=0;i<inputs.length;i++){if(i>0)System.out.print(","); System.out.print("{\"input\":"+q(inputs[i])+",\"expected\":"+q(StringEscapeUtils.unescapeHtml4(inputs[i]))+"}");}
    System.out.println("]}");
  }
}
