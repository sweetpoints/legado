/// Read-only Jsoup snapshots survive V8 globals without retaining native handles.
const legacyDomPrelude = r'''
(() => {
  const list = (values, shared) => {
    const result = values.map(materialize);
    Object.defineProperties(result, {
      size:{value:()=>result.length}, get:{value:index=>result[index]},
      first:{value:()=>result[0] || null}, last:{value:()=>result[result.length-1] || null},
      text:{value:()=>shared ? __sourceHostSync('javaHost.domCall',[shared,'text',[]]) : result.map(node=>node.text()).join(' ')},
      attr:{value:name=>shared ? __sourceHostSync('javaHost.domCall',[shared,'attr',[name]]) : (result.find(node=>node.hasAttr(name))?.attr(name) || '')},
      html:{value:()=>shared ? __sourceHostSync('javaHost.domCall',[shared,'html',[]]) : result.map(node=>node.html()).join('\n')},
      select:{value:selector=>shared ? materialize(__sourceHostSync('javaHost.domCall',[shared,'select',[selector]])) : list(result.flatMap(node=>node.select(selector)))}
    });
    return result;
  };
  function materialize(value) {
    if (value == null) return value;
    if (Array.isArray(value)) return list(value);
    if (value.__legacyDomList) {
      const state=value.__legacyDomList;
      if(state.schemaVersion!==1 || !Array.isArray(state.indexes)) throw new Error('legacy.invalid_dom_list');
      return list(state.indexes.map(index=>({__legacyDom:{schemaVersion:1,nodes:state.nodes,index}})), value);
    }
    if (!value.__legacyDom) return value;
    const state=value.__legacyDom;
    if(state.schemaVersion!==1 || !Array.isArray(state.nodes) || !Number.isInteger(state.index)) throw new Error('legacy.invalid_dom');
    const node=Object.create(null);
    const invoke=(operation,args)=>materialize(__sourceHostSync('javaHost.domCall',[value,operation,args]));
    for(const operation of ['attr','hasAttr','text','ownText','html','outerHtml','data','tagName','id','className',
      'select','selectFirst','getElementsByTag','getElementsByClass','getElementById','parent','children',
      'nextElementSibling','previousElementSibling']) {
      node[operation]=(...args)=>invoke(operation,args);
    }
    node.toString=()=>node.outerHtml();
    node.toJSON=()=>value;
    return node;
  }
  globalThis.__legacyDomMaterialize=materialize;
  globalThis.__legacyDomList=list;
})();
''';
