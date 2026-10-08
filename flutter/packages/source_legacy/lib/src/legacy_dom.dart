/// Jsoup JSON forests preserve shared aliases without native object registries.
const legacyDomPrelude = r'''
(() => {
  const documents=new Map(), refs=new WeakMap();
  const documentCleanup=new FinalizationRegistry(record=>{
    if(documents.get(record.id)===record.ref) documents.delete(record.id);
  });
  const wrapperCleanup=new FinalizationRegistry(record=>{
    const state=documents.get(record.documentId)?.deref();
    if(state) {const live=current(state);if(live.wrappers.get(record.id)===record.ref)live.wrappers.delete(record.id);}
  });
  let serial=0;
  const identity=()=> 'dom-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2)+'-'+(++serial);
  const current=state=> {while(state.redirect) state=state.redirect;return state;};
  const remember=(state,id=state.documentId)=>{
    if(documents.get(id)?.deref()===state)return;
    const ref=new WeakRef(state);documents.set(id,ref);
    documentCleanup.register(state,{id,ref});
  };
  const find=id=>documents.get(id)?.deref();
  function documentState(value) {
    if(!value || ![1,2].includes(value.schemaVersion) || !Array.isArray(value.nodes) || !value.nodes.length) throw new Error('legacy.invalid_dom');
    if(value.schemaVersion===2) {
      const existing=find(value.documentId);
      if(existing) return current(existing);
      if(typeof value.documentId!=='string' || !Array.isArray(value.ids) || value.ids.length!==value.nodes.length || !Array.isArray(value.roots)) throw new Error('legacy.invalid_dom');
    }
    const state={documentId:value.documentId||identity(),nodes:value.nodes,
      ids:value.ids||value.nodes.map(()=>identity()), roots:value.roots||[0],
      modified:value.schemaVersion===2, wrappers:new Map()};
    remember(state);
    return state;
  }
  function table(state) {
    state=current(state);
    return {schemaVersion:2,documentId:state.documentId,nodes:state.nodes,ids:state.ids,roots:state.roots};
  }
  function marker(ref, internal=false) {
    const state=current(ref.state), positions=ref.ids.map(id=>state.ids.indexOf(id));
    if(positions.some(index=>index<0)) throw new Error('legacy.invalid_dom_identity');
    const base=internal||state.modified?table(state):{schemaVersion:1,nodes:state.nodes};
    return ref.list?{__legacyDomList:{...base,indexes:positions}}:{__legacyDom:{...base,index:positions[0]}};
  }
  function apply(update) {
    if(update.schemaVersion!==2 || !Array.isArray(update.mergedDocumentIds)) throw new Error('legacy.invalid_dom_update');
    let state=find(update.documentId);
    if(!state) state=documentState(update);
    state=current(state);
    for(const id of update.mergedDocumentIds) {
      const other=find(id);
      if(other && current(other)!==state) {
        const old=current(other);
        state.modified=state.modified||old.modified;
        for(const [id,wrapper] of old.wrappers) state.wrappers.set(id,wrapper);
        old.redirect=state;
      }
      remember(state,id);
    }
    state.nodes=update.nodes;state.ids=update.ids;state.roots=update.roots;
    state.modified=state.modified||update.mutated===true||update.mergedDocumentIds.length>1;
    remember(state);
  }
  function invoke(ref, operation, args) {
    const encoded=args.map(value=>refs.has(value)?marker(refs.get(value),true):value);
    const reply=__sourceHostSync('javaHost.domCall',[marker(ref,true),operation,encoded]);
    if(reply && reply.__legacyDomUpdate) {apply(reply.__legacyDomUpdate);return materialize(reply.value);}
    return materialize(reply);
  }
  function node(ref) {
    const state=current(ref.state),id=ref.ids[0],cached=state.wrappers.get(id)?.deref();
    if(cached) return cached;
    const value=Object.create(null);refs.set(value,ref);
    const weak=new WeakRef(value);state.wrappers.set(id,weak);
    wrapperCleanup.register(value,{documentId:state.documentId,id,ref:weak});
    for(const operation of ['attr','hasAttr','text','ownText','html','outerHtml','data','tagName','id','className',
      'select','selectFirst','getElementsByTag','getElementsByClass','getElementById','parent','children',
      'nextElementSibling','previousElementSibling','body','head','title','createElement','remove','empty',
      'appendChild','appendElement','append']) value[operation]=(...args)=>invoke(ref,operation,args);
    value.toString=()=>value.outerHtml();value.toJSON=()=>marker(ref);
    return value;
  }
  function list(values, shared) {
    const result=values.map(materialize);
    let ref;
    if(shared) {
      const raw=shared.__legacyDomList,state=documentState(raw);
      ref={state,ids:raw.indexes.map(index=>(raw.ids||state.ids)[index]),list:true};refs.set(result,ref);
    }
    const call=(op,args,fallback)=>{
      if(!ref)return fallback();
      const value=invoke(ref,op,args);
      return ['remove','append','appendChild','empty'].includes(op)||(['attr','html'].includes(op)&&args.length>(op==='attr'?1:0)) ? result : value;
    };
    Object.defineProperties(result,{
      size:{value:()=>result.length},get:{value:index=>result[index]},
      toArray:{value:(...args)=>{if(args.length) throw new Error('legacy.unsupported_dom_overload: toArray');return result.slice();}},
      first:{value:()=>result[0]||null},last:{value:()=>result[result.length-1]||null},
      text:{value:()=>call('text',[],()=>result.map(value=>value.text()).join(' '))},
      attr:{value:(...args)=>call('attr',args,()=>args.length===1?(result.find(value=>value.hasAttr(args[0]))?.attr(args[0])||''):(result.forEach(value=>value.attr(...args)),result))},
      html:{value:(...args)=>call('html',args,()=>args.length?(result.forEach(value=>value.html(...args)),result):result.map(value=>value.html()).join('\n'))},
      select:{value:selector=>call('select',[selector],()=>list(result.flatMap(value=>value.select(selector))))},
      remove:{value:(...args)=>{if(args.length)throw new Error('legacy.unsupported_dom_overload: remove');return call('remove',[],()=>{result.forEach(value=>value.remove());return result;});}},
      append:{value:html=>call('append',[html],()=>{result.forEach(value=>value.append(html));return result;})},
      appendChild:{value:child=>call('appendChild',[child],()=>{result.forEach(value=>value.appendChild(child));return result;})},
      empty:{value:()=>call('empty',[],()=>{result.forEach(value=>value.empty());return result;})}
    });
    if(ref) Object.defineProperties(result,{toString:{value:()=>invoke(ref,'toString',[])},toJSON:{value:()=>marker(ref)}});
    return result;
  }
  function materialize(value) {
    if(value==null) return value;
    if(Array.isArray(value)) return list(value);
    if(value.__legacyDomList) {
      const raw=value.__legacyDomList,state=documentState(raw);
      if(!Array.isArray(raw.indexes)) throw new Error('legacy.invalid_dom_list');
      const positions=raw.indexes.map(index=>state.ids.indexOf((raw.ids||state.ids)[index]));
      if(positions.some(index=>index<0))throw new Error('legacy.invalid_dom_identity');
      const values=positions.map(index=>({__legacyDom:{...table(state),index}}));
      return list(values,{__legacyDomList:{...table(state),indexes:positions}});
    }
    if(!value.__legacyDom) return value;
    const raw=value.__legacyDom,state=documentState(raw);
    if(!Number.isInteger(raw.index)||raw.index<0||raw.index>=state.ids.length)throw new Error('legacy.invalid_dom');
    const id=(raw.ids||state.ids)[raw.index];
    if(!state.ids.includes(id))throw new Error('legacy.invalid_dom_identity');
    return node({state,ids:[id],list:false});
  }
  globalThis.__legacyDomMaterialize=materialize;
  globalThis.__legacyDomList=list;
  globalThis.__legacyDomIsInstance=(value,kind)=>{
    const ref=refs.get(value);if(!ref||ref.list)return false;
    const state=current(ref.state),row=state.nodes[state.ids.indexOf(ref.ids[0])];
    return kind==='document'?row.kind==='document':kind==='element'?['document','element'].includes(row.kind):false;
  };
})();
''';
