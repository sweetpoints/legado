/// Bounded org.jsoup / Packages.org.jsoup adapters, backed by actual native Jsoup.
/// Connections and responses are owner-managed leases, never reconstructed mocks.
const legacyOrgJsoupPrelude = r'''
(() => {
  const rpc=(method,args)=>__sourceHostSync('orgJsoup.'+method,args);
  const Jsoup=Object.freeze({
    parse:(...args)=>__legacyDomMaterialize(rpc('parse',args)),
    parseBodyFragment:(...args)=>__legacyDomMaterialize(rpc('parseBodyFragment',args))
  });
  function Document(...args){return __legacyDomMaterialize(rpc('newDocument',args));}
  Document.createShell=(base)=>__legacyDomMaterialize(rpc('newDocument',[base,'shell']));
  function Element(...args){return __legacyDomMaterialize(rpc('newElement',args));}
  Object.defineProperty(Document,Symbol.hasInstance,{value:value=>__legacyDomIsInstance(value,'document')});
  Object.defineProperty(Element,Symbol.hasInstance,{value:value=>__legacyDomIsInstance(value,'element')});
  const methods=Object.create(null);
  const names=['GET','POST','PUT','DELETE','PATCH','HEAD','OPTIONS','TRACE'];
  names.forEach((name,index)=>methods[name]=Object.freeze({name:()=>name,ordinal:()=>index,toString:()=>name,toJSON:()=>name}));
  Object.defineProperties(methods,{
    valueOf:{value:name=>{if(!Object.prototype.hasOwnProperty.call(methods,name))throw new Error('legacy.invalid_jsoup_method');return methods[name];}},
    values:{value:()=>names.map(name=>methods[name])}
  });
  const Parser=Object.freeze({htmlParser:()=>Object.freeze({__legacyOrgParser:'html'}),xmlParser:()=>Object.freeze({__legacyOrgParser:'xml'})});
  const jsoup=Object.freeze({Jsoup,nodes:Object.freeze({Document,Element}),Connection:Object.freeze({Method:Object.freeze(methods)}),parser:Object.freeze({Parser})});
  function attach(current,key,value){
    if(current===undefined)return Object.freeze({[key]:value});
    if(current===null || !['object','function'].includes(typeof current))throw new TypeError('legacy.org_namespace_conflict');
    const descriptor=Object.getOwnPropertyDescriptor(current,key);
    if(descriptor && !descriptor.configurable){
      if(!descriptor.writable || !Reflect.set(current,key,value))throw new TypeError('legacy.org_namespace_conflict');
    } else Object.defineProperty(current,key,{value,enumerable:true,configurable:true,writable:false});
    return current;
  }
  const previousOrg=globalThis.org ?? globalThis.Packages?.org;
  const org=attach(previousOrg,'jsoup',jsoup);
  globalThis.org=org;
  // Extend only this implemented package, preserving any existing bounded provider.
  globalThis.Packages=attach(globalThis.Packages,'org',org);
})();
''';
