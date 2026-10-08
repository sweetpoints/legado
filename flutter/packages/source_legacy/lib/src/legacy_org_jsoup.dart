/// Bounded org.jsoup / Packages.org.jsoup adapters, backed by actual native Jsoup.
/// Connections and responses are owner-managed leases, never reconstructed mocks.
const legacyOrgJsoupPrelude = r'''
(() => {
  const pending=[];
  const wrappers=new Map();
  const leases=new WeakMap();
  const finalizer=typeof FinalizationRegistry==='function' ? new FinalizationRegistry(token=>{
    if(!wrappers.get(token)?.deref()){wrappers.delete(token);pending.push(token);}
  }) : null;
  function flush(){
    while(pending.length){
      const token=pending[0];
      if(!wrappers.get(token)?.deref())__sourceHostSync('orgJsoup.release',[token]);
      pending.shift();
    }
  }
  function rpc(method,args){flush();return __sourceHostSync('orgJsoup.'+method,args);}
  function register(value,token){
    leases.set(value,{token,disposed:false});
    if(typeof WeakRef==='function') wrappers.set(token,new WeakRef(value));
    if(finalizer) finalizer.register(value,token,value);
    Object.defineProperty(value,'dispose',{value:()=>{
      const state=leases.get(value);
      if(state.disposed)return;
      rpc('release',[token]);state.disposed=true;wrappers.delete(token);
      if(finalizer)finalizer.unregister(value);
    }});
  }
  function active(value){if(leases.get(value).disposed)throw new Error('legacy.org_jsoup_disposed');}
  function map(values){
    if(!values || typeof values!=='object') return values;
    const methods={get:key=>values[String(key)]??null,containsKey:key=>Object.prototype.hasOwnProperty.call(values,String(key)),
      size:()=>Object.keys(values).length,keySet:()=>Object.keys(values),values:()=>Object.values(values),toJSON:()=>values};
    return new Proxy(values,{get:(target,key)=>Object.prototype.hasOwnProperty.call(methods,key)?methods[key]:target[key]});
  }
  function response(marker){
    const token=marker.__legacyOrgResponse;
    if(typeof token!=='string')throw new Error('legacy.invalid_org_response');
    const previous=wrappers.get(token)?.deref();if(previous)return previous;
    const result=Object.create(null);register(result,token);
    for(const method of ['body','bodyAsBytes','statusCode','statusMessage','header','headers','cookie','cookies','hasHeader','hasCookie','url','charset','parse']) {
      result[method]=(...args)=>{
        active(result);const value=rpc('responseCall',[token,method,args]);
        if(value && value.__legacyOrgResponse){
          if(value.__legacyOrgResponse!==token)throw new Error('legacy.org_response_changed');
          return result;
        }
        if(method==='bodyAsBytes')return __legacyCacheMarkBytes(value);
        if(method==='headers'||method==='cookies')return map(value);
        if(method==='parse')return __legacyDomMaterialize(value);
        return value;
      };
    }
    return result;
  }
  function connection(marker){
    const token=marker.__legacyOrgConnection;
    if(typeof token!=='string')throw new Error('legacy.invalid_org_connection');
    const previous=wrappers.get(token)?.deref();if(previous)return previous;
    const result=Object.create(null);register(result,token);
    for(const method of ['url','userAgent','timeout','maxBodySize','referrer','header','headers','data','requestBody','cookie','cookies',
      'method','ignoreHttpErrors','ignoreContentType','followRedirects','postDataCharset','get','post','execute','response']) {
      result[method]=(...args)=>{
        active(result);const value=rpc('connectionCall',[token,method,args]);
        if(value && value.__legacyOrgConnection){
          if(value.__legacyOrgConnection!==token)throw new Error('legacy.org_connection_changed');
          return result;
        }
        if(value && value.__legacyOrgResponse)return response(value);
        return __legacyDomMaterialize(value);
      };
    }
    return result;
  }
  const Jsoup=Object.freeze({
    parse:(...args)=>__legacyDomMaterialize(rpc('parse',args)),
    parseBodyFragment:(...args)=>__legacyDomMaterialize(rpc('parseBodyFragment',args)),
    connect:(...args)=>connection(rpc('connect',args))
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
    valueOf:{value:name=>{if(!names.includes(name))throw new Error('legacy.invalid_jsoup_method');return methods[name];}},
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
