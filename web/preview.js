/* Trusted renderer for Markdown and inert HTML; the source is never executable markup. */
(()=>{
  const decode=id=>new TextDecoder('utf-8',{fatal:true}).decode(Uint8Array.from(atob(document.getElementById(id).textContent),c=>c.charCodeAt(0)));
  const source=decode('preview-source'),context=JSON.parse(decode('preview-context'));
  const markdown=context.kind==='markdown',container=document.getElementById('document'),sources=new Map();
  DOMPurify.addHook('uponSanitizeAttribute',(node,data)=>{
    const tag=node.nodeName.toLowerCase(),name=data.attrName.toLowerCase();
    if((tag==='img' && name==='src') || (tag==='image' && (name==='href' || name==='xlink:href'))) {
      const value=PreviewPaths.rewrite(data.attrValue,context);
      if(value!==null) sources.set(node,value);
      data.keepAttr=false;
    } else if((tag==='a' || tag==='link') && name==='href') {
      const mapped=PreviewPaths.rewrite(data.attrValue,context);
      if(mapped===null) data.keepAttr=false;else data.attrValue=mapped;
    }
  });
  DOMPurify.addHook('uponSanitizeElement',node=>{
    if(node.nodeName.toLowerCase()==='link' && node.getAttribute('rel')?.toLowerCase()!=='stylesheet') node.remove();
  });
  const clean=DOMPurify.sanitize(markdown?marked.parse(source):source,{
    WHOLE_DOCUMENT:true,RETURN_DOM:true,USE_PROFILES:{html:true,svg:!markdown,svgFilters:!markdown},
    ADD_TAGS:markdown?[]:['link'],
    FORBID_TAGS:['script','meta','base','iframe','object','embed','form','input','button','textarea','video','audio','source','foreignobject',...(markdown?['style','link']:[])],
    FORBID_ATTR:['srcset','name','nonce',...(markdown?['style']:[])],ALLOW_DATA_ATTR:false
  });
  const head=clean.querySelector('head'),body=clean.querySelector('body');
  if(!markdown && head) {
    const title=head.querySelector('title');if(title) document.title=title.textContent;
    for(const node of [...head.children]) if(node.nodeName.toLowerCase()==='style' || node.nodeName.toLowerCase()==='link') document.head.append(node);
  }
  if(!markdown) {
    for(const name of ['class','style','dir','lang','id']) if(body?.hasAttribute(name)) document.body.setAttribute(name,body.getAttribute(name));
    for(const name of ['class','style','dir','lang']) if(clean.hasAttribute(name)) document.documentElement.setAttribute(name,clean.getAttribute(name));
  }
  if(body) {
    const fragment=document.createDocumentFragment();
    while(body.firstChild) fragment.appendChild(body.firstChild);
    container.appendChild(fragment);
  }
  for(const heading of container.querySelectorAll('h1,h2,h3,h4,h5,h6')) {
    if(!heading.id) heading.id=heading.textContent.trim().toLowerCase().replace(/\s+/gu,'-');
  }
  PreviewLazy.create(context,container,sources);
  document.documentElement.dataset.previewReady='true';
})();
