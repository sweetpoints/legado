/// The old CookieStore namespace is injected only by the legacy runtime recipe.
const legacyCookiePrelude = r'''
(() => {
  function cookieMap(values) {
    const data=Object.assign(Object.create(null), values);
    const methods={
      get:key=>Object.prototype.hasOwnProperty.call(data,String(key)) ? data[String(key)] : null,
      put:(key,value)=>{const previous=methods.get(key);data[String(key)]=String(value);return previous;},
      remove:key=>{const previous=methods.get(key);delete data[String(key)];return previous;},
      containsKey:key=>Object.prototype.hasOwnProperty.call(data,String(key)),
      size:()=>Object.keys(data).length,
      toJSON:()=>data
    };
    return new Proxy(data,{get:(target,key)=>Object.prototype.hasOwnProperty.call(methods,key) ? methods[key] : target[key]});
  }
  const cookie=Object.create(null);
  for(const method of ['setCookie','setWebCookie','replaceCookie','getCookie','getKey','removeCookie','mapToCookie','clear']) {
    cookie[method]=(...args)=>__sourceHostSync('cookieHost.'+method,args);
  }
  cookie.cookieToMap=(...args)=>cookieMap(__sourceHostSync('cookieHost.cookieToMap',args));
  globalThis.cookie=Object.freeze(cookie);
})();
''';
