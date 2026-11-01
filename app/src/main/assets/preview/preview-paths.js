/* No IO: map absolute remote references into a document-scoped virtual HTTPS route. */
(function(root) {
  function rewrite(value,context) {
    const reference=value.trim();
    const file=/^file:/i.test(reference);
    if(file && !/^file:\//i.test(reference)) return null;
    if(!file && (!reference.startsWith('/') || reference.startsWith('//'))) return value;
    if(/[\x00-\x1f\x7f-\x9f]/u.test(reference)) return null;
    try {
      const page=new URL(context.documentUrl);
      const uri=new URL(reference,file?undefined:page.origin);
      const host=h=>h.replace(/^\[|\]$/g,'').toLowerCase();
      if(file && (uri.username || uri.password || uri.port ||
        (uri.hostname && uri.hostname!=='localhost' && (!context.host || host(uri.hostname)!==host(context.host))))) return null;
      const path=decodeURIComponent(uri.pathname);
      if(!path.startsWith('/') || path.length>8192 || /[\x00-\x1f\x7f-\x9f]/u.test(path)) return null;
      const parts=[];
      for(const part of path.split('/')) {
        if(part==='..') parts.pop();else if(part && part!=='.') parts.push(part);
      }
      const prefix=page.pathname.slice(0,page.pathname.indexOf('/remote/'))+'/remote';
      return page.origin+prefix+'/'+parts.map(encodeURIComponent).join('/')+uri.search+uri.hash;
    } catch {return null;}
  }
  root.PreviewPaths={rewrite};
  if(typeof module!=='undefined') module.exports=root.PreviewPaths;
})(globalThis);
