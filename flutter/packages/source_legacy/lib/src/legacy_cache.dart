/// Explicit native CacheManager facade. Byte identity tags are weak and VM-owned.
const legacyCachePrelude = r'''
(() => {
  const byteArrays = new WeakSet();
  const mark = value => {if(Array.isArray(value)) byteArrays.add(value); return value;};
  const encode = (value,text) => {
    if(value==null) throw new TypeError('legacy.cache_null_value');
    return byteArrays.has(value) ? {kind:'bytes',value:Array.from(value)}
      : text ? {kind:'string',value:String(value)} : {kind:'json',value};
  };
  const decode = value => value==null ? null : value.kind==='bytes' ? mark(value.value) : value.value;
  const cacheKey = value => {if(value==null) throw new TypeError('legacy.cache_null_key'); return String(value);};
  const cache = Object.create(null);
  cache.put=(key,value,...ttl)=>__sourceHostSync('cacheHost.put',[cacheKey(key),encode(value,true),...ttl]);
  cache.putMemory=(key,value)=>__sourceHostSync('cacheHost.putMemory',[cacheKey(key),encode(value,false)]);
  cache.getFromMemory=(...args)=>{if(args.length) args[0]=cacheKey(args[0]);return decode(__sourceHostSync('cacheHost.getFromMemory',args));};
  cache.getByteArray=(...args)=>{if(args.length) args[0]=cacheKey(args[0]);return decode(__sourceHostSync('cacheHost.getByteArray',args));};
  for(const method of ['get','delete','deleteMemory','getInt','getLong','getDouble','getFloat','putFile','getFile']) {
    cache[method]=(...args)=>{if(args.length) args[0]=cacheKey(args[0]);return __sourceHostSync('cacheHost.'+method,args);};
  }
  globalThis.__legacyCacheMarkBytes=mark;
  globalThis.cache=Object.freeze(cache);
})();
''';
