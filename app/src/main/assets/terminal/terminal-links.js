/* Public xterm APIs only. This module never sends input or opens a browser itself. */
(function(root) {
  function candidates(text) {
    const result=[];
    const pattern=/https?:\/\/[^\s<>"`]+|file:\/\/[^\s<>"`]+|(?:"([^"\r\n]+\.(?:md|markdown|html?|png|jpe?g|webp)(?::\d+(?::\d+)?)?)"|'([^'\r\n]+\.(?:md|markdown|html?|png|jpe?g|webp)(?::\d+(?::\d+)?)?)')|(?:\/?(?:[\p{L}\p{N}_~.@$()+-]+\/)*[\p{L}\p{N}_@.$()+-]+\.(?:md|markdown|html?|png|jpe?g|webp)(?::\d+(?::\d+)?)?)/giu;
    for (const match of text.matchAll(pattern)) {
      let value=match[1] || match[2] || match[0];
      const quoted=!!(match[1] || match[2]);
      if (!quoted && /^https?:/i.test(value)) {
        value=value.replace(/[.,;!，。；！]+$/u,'');
        // Only remove an unmatched closing Markdown wrapper, not balanced URL parentheses.
        while(value.endsWith(')') && (value.match(/\)/g)||[]).length>(value.match(/\(/g)||[]).length) value=value.slice(0,-1);
      }
      result.push({text:value,start:match.index+(quoted?1:0),end:match.index+(quoted?1:0)+value.length});
    }
    return result;
  }
  function install(terminal,screen,onOpen) {
    let osc=null;
    let painted=true;
    terminal.onRender(()=>{painted=true;});
    terminal.options.linkHandler={allowNonHttpProtocols:true,
      activate:(_e,uri)=>onOpen(uri),hover:(_e,uri,range)=>{osc={text:uri,range};},leave:()=>{osc=null;}};
    function links(y) {
      const buffer=terminal.buffer.active;
      let start=y-1,end=y-1;
      while(start>0 && y-1-start<8 && buffer.getLine(start)?.isWrapped) start--;
      while(end+1<buffer.length && end-start<8 && buffer.getLine(end+1)?.isWrapped) end++;
      let text='',cells=[];
      for(let row=start;row<=end;row++) {
        const line=buffer.getLine(row);
        if(!line) break;
        for(let col=0;col<terminal.cols;col++) {
          const cell=line.getCell(col);
          if(!cell || cell.getWidth()===0) continue;
          const chars=cell.getChars() || ' ';
          for(let i=0;i<chars.length;i++) cells.push({x:col+1,y:row+1});
          text+=chars;
          if(text.length>8192) return [];
        }
      }
      return candidates(text).flatMap(item=>{
        const a=cells[item.start],b=cells[item.end-1];
        if(!a || !b) return [];
        const last=buffer.getLine(b.y-1)?.getCell(b.x-1);
        const range={start:a,end:{x:b.x+Math.max(0,(last?.getWidth()||1)-1),y:b.y}};
        return [{text:item.text,range,activate:()=>onOpen(item.text)}];
      });
    }
    terminal.registerLinkProvider({provideLinks:(y,cb)=>cb(links(y))});
    function at(range,col,row) {
      const p=row*terminal.cols+col;
      return p>=range.start.y*terminal.cols+range.start.x && p<=range.end.y*terminal.cols+range.end.x;
    }
    return {
      invalidate() {
        painted=false;osc=null;
        // Control-only writes need not cause a render event, so do not leave clicks disabled.
        requestAnimationFrame(()=>requestAnimationFrame(()=>{painted=true;}));
      },
      hit(x,y) {
        if(!painted) return {uri:null,pending:true};
        const box=screen.getBoundingClientRect();
        if(x<box.left || x>=box.right || y<box.top || y>=box.bottom) return null;
        const col=Math.floor((x-box.left)/(box.width/terminal.cols))+1;
        const row=Math.floor((y-box.top)/(box.height/terminal.rows))+terminal.buffer.active.viewportY+1;
        osc=null;
        screen.dispatchEvent(new MouseEvent('mouseleave',{bubbles:false}));
        // No down/up: xterm's MOVE reporting ignores events with a held button; no drag was begun.
        // Linkifier caches the last cell even after mouseleave. Move off it first so
        // a second probe of the same touch cell invokes the public hover callback.
        const otherX=col===1 ? x+box.width/terminal.cols : x-box.width/terminal.cols;
        screen.dispatchEvent(new MouseEvent('mousemove',{bubbles:true,clientX:otherX,clientY:y,buttons:1}));
        screen.dispatchEvent(new MouseEvent('mousemove',{bubbles:true,clientX:x,clientY:y,buttons:1}));
        const link=osc && at(osc.range,col,row) ? osc : links(row).find(l=>at(l.range,col,row));
        return link ? {uri:link.text,line:terminal.buffer.active.getLine(row-1)?.translateToString(false),row} : null;
      }
    };
  }
  root.TerminalLinks={candidates,install};
  if(typeof module!=='undefined') module.exports=root.TerminalLinks;
})(globalThis);
