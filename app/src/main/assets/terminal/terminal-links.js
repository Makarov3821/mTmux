/* Public xterm APIs only. This module never sends input or opens a browser itself. */
(function(root) {
  function leadingWrapper(text) {
    let unmatched=(text.match(/\(/g)||[]).length-(text.match(/\)/g)||[]).length,start=0;
    while(text[start]==='(' && unmatched>0) {start++;unmatched--;}
    return start;
  }
  function candidates(text) {
    const result=[];
    const pattern=/https?:\/\/[^\s<>"`]+|file:\/\/[^\s<>"`]+|(?:"([^"\r\n]+\.(?:md|markdown|html?|png|jpe?g|webp|svg)(?::\d+(?::\d+)?)?)"|'([^'\r\n]+\.(?:md|markdown|html?|png|jpe?g|webp|svg)(?::\d+(?::\d+)?)?)')|(?:\/?(?:[\p{L}\p{N}_~.@$()+-]+\/)*[\p{L}\p{N}_@.$()+-]+\.(?:md|markdown|html?|png|jpe?g|webp|svg)(?::\d+(?::\d+)?)?)/giu;
    for (const match of text.matchAll(pattern)) {
      let value=match[1] || match[2] || match[0];
      const quoted=!!(match[1] || match[2]);
      const wrapper=quoted ? 0 : leadingWrapper(value);
      value=value.slice(wrapper);
      if (!quoted && /^https?:/i.test(value)) {
        value=value.replace(/[.,;!，。；！]+$/u,'');
        // Only remove an unmatched closing Markdown wrapper, not balanced URL parentheses.
        while(value.endsWith(')') && (value.match(/\)/g)||[]).length>(value.match(/\(/g)||[]).length) value=value.slice(0,-1);
      }
      const start=match.index+(quoted?1:0)+wrapper;
      result.push({text:value,start,end:start+value.length});
    }
    return result;
  }
  function readRow(terminal,row) {
    const line=terminal.buffer.active.getLine(row);
    let text='',cells=[],used=0;
    for(let col=0;line && col<terminal.cols;col++) {
      const cell=line.getCell(col);
      if(!cell || cell.getWidth()===0) continue;
      const chars=cell.getChars();
      for(let i=0;i<(chars || ' ').length;i++) cells.push({x:col+1,y:row+1});
      text+=chars || ' ';
      if(chars) used=text.length;
    }
    // A wide glyph wrapping at the last column leaves a null cell, not a path space.
    return {text:text.slice(0,used),cells:cells.slice(0,used),wrapped:line?.isWrapped,row};
  }
  function continuation(group,next,cols) {
    if(!group.text || !next.text) return null;
    const left=group.text.replace(/[ │┃║]+$/u,'');
    const last=group.cells[left.length-1];
    const tail=left.match(/(?:https?:\/\/|file:\/\/)[^\s<>"`]*$|[^\s<>"`]*\/[^\s<>"`]*$/iu)?.[0];
    if(!last || !tail || /[\]),;!，。；！]$/u.test(tail)) return null;
    const enclosed=leadingWrapper(tail)>0;
    if(!enclosed && cols-last.x>4) return null;
    const indent=next.text.match(/^[ │┃║]*/u)[0].length;
    if(indent>12) return null;
    if(!enclosed && indent===0 && last.x!==cols) return null;
    const right=next.text.slice(indent);
    if(!right || /^(?:https?|file):\/\//i.test(right) || !/^[\p{L}\p{N}_~.@$()+%/?&#=:-]/u.test(right)) return null;
    // TUI applications paint CR/LF inside a prose margin, sometimes with no indent.
    // An unfinished parenthesized path can continue there; ordinary text still
    // needs the right-edge/indent evidence rather than merging whole paragraphs.
    if(!/^(?:https?|file):/i.test(tail) && /\.(?:md|markdown|html?|png|jpe?g|webp|svg)(?::\d+(?::\d+)?)?$/i.test(tail)) return null;
    return {left:left.length,indent};
  }
  function linksForRow(terminal,y) {
      const buffer=terminal.buffer.active,groups=[];
      for(let row=Math.max(0,y-9);row<Math.min(buffer.length,y+8);row++) {
        const next=readRow(terminal,row),group=groups.at(-1);
        const hard=group && !next.wrapped ? continuation(group,next,terminal.cols) : null;
        if(group && (next.wrapped || hard) && group.text.length+next.text.length<=8192) {
          const left=hard?.left ?? group.text.length,indent=hard?.indent ?? 0;
          group.text=group.text.slice(0,left)+next.text.slice(indent);
          group.cells=group.cells.slice(0,left).concat(next.cells.slice(indent));
          group.last=row;
        } else groups.push({text:next.text,cells:next.cells,first:row,last:row});
      }
      const {text,cells}=groups.find(g=>g.first<=y-1 && g.last>=y-1) || {text:'',cells:[]};
      return candidates(text).flatMap(item=>{
        const a=cells[item.start],b=cells[item.end-1];
        if(!a || !b) return [];
        const last=buffer.getLine(b.y-1)?.getCell(b.x-1);
        const range={start:a,end:{x:b.x+Math.max(0,(last?.getWidth()||1)-1),y:b.y}};
        return range.start.y<=y && range.end.y>=y ? [{text:item.text,range}] : [];
      });
  }
  function install(terminal,screen,onOpen) {
    let osc=null;
    let painted=true;
    terminal.onRender(()=>{painted=true;});
    terminal.options.linkHandler={allowNonHttpProtocols:true,
      activate:(_e,uri)=>onOpen(uri),hover:(_e,uri,range)=>{osc={text:uri,range};},leave:()=>{osc=null;}};
    const links=y=>linksForRow(terminal,y).map(link=>({...link,activate:()=>onOpen(link.text)}));
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
  root.TerminalLinks={candidates,linksForRow,install};
  if(typeof module!=='undefined') module.exports=root.TerminalLinks;
})(globalThis);
