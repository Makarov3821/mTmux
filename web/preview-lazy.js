/* Trusted viewport image loader. No terminal bridge, remote scripts, or background polling. */
(function(root) {
  const blobPaths=new Map();
  function priority(rect,view,direction=1) {
    if(rect.right<=view.left || rect.left>=view.left+view.width || rect.bottom<=view.top-view.height || rect.top>=view.top+2*view.height) return null;
    const visible=rect.bottom>view.top && rect.top<view.top+view.height;
    const below=rect.top>=view.top+view.height;
    return (visible?0:(below===(direction>=0)?1:2))*1e9+Math.abs((rect.top+rect.bottom)/2-(view.top+view.height/2));
  }
  class Motion {
    constructor() {this.positions=new WeakMap();this.until=0;this.direction=1;}
    sample(target,y,now,height) {
      const previous=this.positions.get(target);this.positions.set(target,{y,time:now});
      if(!previous) return false;
      const delta=y-previous.y;
      if(delta) this.direction=Math.sign(delta);
      const speed=Math.abs(delta)/Math.max(16,now-previous.time)*1000/Math.max(1,height);
      const fast=Math.abs(delta)>.8*height || speed>1.5;
      if(fast) this.until=now+180;
      return fast;
    }
    ready(now) {return now>=this.until;}
  }
  function create(context,container,sources) {
    const groups=new Map(),records=[],motion=new Motion(),nearby=new Set(),displayed=new Set(),nodes=new WeakMap();
    const page=new URL(context.documentUrl),windowUrl=context.windowUrl;
    let disposed=false,active=null,serial=0,plan=0,timer=0,settle=0,visibility=null;
    const view=()=>({top:root.visualViewport?.offsetTop||0,left:root.visualViewport?.offsetLeft||0,
      height:root.visualViewport?.height||root.innerHeight,width:root.visualViewport?.width||root.innerWidth});
    function remote(value) {
      try {
        const mapped=root.PreviewPaths.rewrite(value,context);
        if(mapped===null) return null;
        const url=new URL(mapped,context.documentUrl);url.hash='';
        return url.origin===page.origin && /\.(?:png|jpe?g|webp|svg)$/i.test(decodeURIComponent(url.pathname))?url.href:null;
      } catch {return null;}
    }
    function add(node,value,background=null) {
      const url=remote(value);
      if(!url) return;
      let group=groups.get(url);
      if(!group) {group={url,records:[],blob:null,failed:false};groups.set(url,group);}
      const record={node,group,background,near:false,visible:false,alt:node.getAttribute('alt')||'',errorNode:null};group.records.push(record);records.push(record);
      const related=nodes.get(node)||[];related.push(record);nodes.set(node,related);visibility?.observe(node);
      if(!background) {
        node.classList.add('preview-pending');node.dataset.previewState='waiting';
        if(node.nodeName.toLowerCase()==='img') {
          node.addEventListener('load',()=>{
            if(!node.naturalWidth) return;
            if(!node.hasAttribute('width')) node.setAttribute('width',node.naturalWidth);
            if(!node.hasAttribute('height')) node.setAttribute('height',node.naturalHeight);
            node.setAttribute('alt',record.alt);node.classList.remove('preview-pending');node.dataset.previewState='loaded';schedule();
          });
          node.addEventListener('error',()=>{if(record.near && group.blob) failed(group,context.messages.failed);});
          if(context.kind==='markdown' && !node.closest('a')) {const link=document.createElement('a');link.href=url;node.replaceWith(link);link.append(node);}
          node.addEventListener('click',event=>{
            if(!group.failed) return;
            event.preventDefault();event.stopPropagation();retry(group);
          });
        }
      }
    }
    function setImage(record,blob) {
      const node=record.node;
      if(record.background) {
        const definition=record.background;
        const css=definition.original.replace(/url\((?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([^)]*))\)/g,(match,a,b,c)=> {
          const url=remote((a??b??c).trim());
          const group=groups.get(url);
          return group?.blob && definition.records.some(r=>r.near)?`url("${group.blob}")`:'none';
        });
        node.style.setProperty('background-image',css,'important');return;
      }
      const attribute=node.nodeName.toLowerCase()==='image'?'href':'src';
      if(blob) {
        if(node.getAttribute(attribute)!==blob) {node.dataset.previewState='decoding';node.setAttribute(attribute,blob);}
      } else {
        node.removeAttribute(attribute);
        if(!record.group.failed) node.dataset.previewState='waiting';
      }
    }
    async function publish(id) {
      const url=new URL(windowUrl);url.searchParams.set('sequence',++plan);url.searchParams.set('id',id);
      const response=await fetch(url,{cache:'no-store'});
      if(!response.ok) throw new Error('preview closed');
    }
    function cancel() {
      if(!active) return;
      const previous=active;active=null;previous.controller.abort();
      void publish(0).catch(()=>{});
    }
    function failed(group,message) {
      group.failed=true;
      if(group.blob) {blobPaths.delete(group.blob);URL.revokeObjectURL(group.blob);group.blob=null;}
      for(const record of group.records) if(!record.background) {
        setImage(record,null);
        record.node.dataset.previewState='error';record.node.title=message+' — '+context.messages.retry;
        record.node.classList.add('preview-pending');
        if(!record.errorNode) {
          const button=document.createElement('button');button.type='button';button.className='preview-image-retry';
          button.addEventListener('click',event=>{event.preventDefault();event.stopPropagation();retry(group);});
          (record.node.closest('a')||record.node).after(button);record.errorNode=button;
        }
        record.errorNode.textContent=message+' · '+context.messages.retry;
      }
    }
    function retry(group) {
      group.failed=false;
      for(const record of group.records) {record.errorNode?.remove();record.errorNode=null;record.node.title='';}
      schedule();
    }
    function scan() {
      const viewport=view(),working=new Set([...nearby,...displayed]),considered=new Set([...working].map(record=>record.group));
      for(const group of considered) group.priority=null;
      for(const record of working) {
        const rect=record.node.getBoundingClientRect();
        // display:none, collapsed <details>, and zero-sized backgrounds do not download.
        const score=nearby.has(record) && rect.width>0 && rect.height>0?priority(rect,viewport,motion.direction):null;
        record.near=score!==null;
        record.visible=record.near && score<1e9;
        if(score!==null) record.group.priority=record.group.priority===null?score:Math.min(score,record.group.priority);
        if(record.near) displayed.add(record);else {displayed.delete(record);setImage(record,null);}
      }
      for(const group of considered) {
        if(group.priority===null && group.blob) {blobPaths.delete(group.blob);URL.revokeObjectURL(group.blob);group.blob=null;}
        else if(group.blob) for(const record of group.records) if(record.near) setImage(record,group.blob);
      }
      return [...considered].filter(g=>g.priority!==null && !g.blob && !g.failed).sort((a,b)=>a.priority-b.priority);
    }
    async function load(group) {
      const job={group,id:++serial,controller:new AbortController()};active=job;
      for(const record of group.records) if(record.near && !record.background) record.node.dataset.previewState='loading';
      try {
        await publish(job.id);
        if(disposed || active!==job) return;
        const url=new URL(group.url);url.searchParams.set('_mtmux_image',job.id);
        const response=await fetch(url,{signal:job.controller.signal,cache:'no-store'});
        if(!response.ok) {
          failed(group,response.status===413?context.messages.limit:context.messages.failed);
          return;
        }
        const blob=await response.blob();
        if(disposed || active!==job) return;
        group.blob=URL.createObjectURL(blob);blobPaths.set(group.blob,group.url);
        for(const record of group.records) if(record.near) setImage(record,group.blob);
      } catch(error) {if(!job.controller.signal.aborted && !disposed) failed(group,context.messages.failed);}
      finally {if(active===job) {active=null;schedule();}}
    }
    function pump() {
      timer=0;if(disposed) return;
      const candidates=scan();
      if(document.hidden || !motion.ready(performance.now())) {cancel();return;}
      const first=candidates[0];
      if(active && (active.group.priority===null || (first?.priority<1e9 && active.group.priority>=1e9))) cancel();
      if(!active && first) void load(first);
    }
    function schedule(delay=0) {if(!disposed && !timer) timer=setTimeout(pump,delay);}
    function scroll(event) {
      const target=event?.target===document || event?.target===root.visualViewport?document.scrollingElement:(event?.target||document.scrollingElement);
      const height=Math.min(view().height,target?.clientHeight||view().height);
      const fast=motion.sample(target,target?.scrollTop||root.scrollY,performance.now(),height);
      clearTimeout(settle);
      settle=setTimeout(()=>{motion.until=0;schedule();},180);
      if(fast) {clearTimeout(timer);timer=0;cancel();scan();}
      else schedule(90);
    }
    // Seed the position so the very first large jump is also classified as fast.
    motion.sample(document.scrollingElement,root.scrollY,performance.now(),view().height);
    for(const [node,value] of sources) if(container.contains(node)) add(node,value);
    function backgrounds() {
      const nodes=new Set([document.body,container,...container.querySelectorAll('[style]')]);
      function rules(sheet) {
        try {for(const rule of sheet.cssRules) {
          if(rule.style?.backgroundImage && rule.selectorText) {try {document.querySelectorAll(rule.selectorText).forEach(node=>nodes.add(node));} catch {}}
          if(rule.styleSheet) rules(rule.styleSheet);else if(rule.cssRules) rules(rule);
        }} catch {}
      }
      for(const sheet of document.styleSheets) rules(sheet);
      for(const node of nodes) {
        if(node.dataset.previewBackground) continue;
        const original=getComputedStyle(node).backgroundImage;
        const urls=[...original.matchAll(/url\((?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([^)]*))\)/g)].map(m=>(m[1]??m[2]??m[3]).trim()).filter(value=>remote(value));
        if(!urls.length) continue;
        const definition={original,records:[]};node.dataset.previewBackground='true';
        node.style.setProperty('background-image','none','important');
        for(const url of urls) {add(node,url,definition);definition.records.push(records.at(-1));}
      }
      schedule();
    }
    document.addEventListener('scroll',scroll,{capture:true,passive:true});
    root.visualViewport?.addEventListener('scroll',scroll,{passive:true});
    function observeViewport() {
      visibility?.disconnect();nearby.clear();
      visibility=new IntersectionObserver(entries=>{
        for(const entry of entries) for(const record of nodes.get(entry.target)||[]) {
          if(entry.isIntersecting) nearby.add(record);else nearby.delete(record);
        }
        schedule();
      },{rootMargin:`${Math.ceil(view().height)}px 0px`,threshold:0});
      for(const record of records) visibility.observe(record.node);
      schedule();
    }
    root.addEventListener('resize',observeViewport,{passive:true});
    root.visualViewport?.addEventListener('resize',observeViewport,{passive:true});
    document.addEventListener('toggle',()=>schedule(),true);
    document.addEventListener('visibilitychange',()=>{if(document.hidden) cancel();else schedule();});
    const observer=new ResizeObserver(()=>schedule());observer.observe(container);
    for(const link of document.querySelectorAll('link[rel=stylesheet]')) link.addEventListener('load',backgrounds);
    backgrounds();observeViewport();schedule();
    root.addEventListener('pagehide',()=>{
      disposed=true;clearTimeout(timer);clearTimeout(settle);cancel();observer.disconnect();visibility?.disconnect();
      for(const group of groups.values()) if(group.blob) {blobPaths.delete(group.blob);URL.revokeObjectURL(group.blob);}
    },{once:true});
    return {refresh:()=>schedule(),groups};
  }
  root.PreviewLazy={priority,Motion,create,imagePath:source=>blobPaths.get(source)||null};
  if(typeof module!=='undefined') module.exports=root.PreviewLazy;
})(globalThis);
