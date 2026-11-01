/* Trusted Markdown renderer. No Android/SSH bridge is present in this WebView. */
const encoded=document.getElementById('markdown-source').textContent;
const text=new TextDecoder('utf-8',{fatal:true}).decode(Uint8Array.from(atob(encoded),c=>c.charCodeAt(0)));
const clean=DOMPurify.sanitize(marked.parse(text),{
  USE_PROFILES:{html:true},
  FORBID_TAGS:['script','style','link','meta','base','iframe','object','embed','form','input','button','textarea','video','audio'],
  FORBID_ATTR:['style','srcset','name']
});
document.getElementById('document').innerHTML=clean;
for(const heading of document.querySelectorAll('#document h1,#document h2,#document h3,#document h4,#document h5,#document h6')) {
  if(!heading.id) heading.id=heading.textContent.trim().toLowerCase().replace(/\s+/gu,'-');
}
// Give standalone images a real navigation target; mobile WebView hit-test metadata
// may lag a short tap. Native routing opens the same local resource in the zoom viewer.
for(const image of document.querySelectorAll('#document img')) {
  if(image.closest('a')) continue;
  const link=document.createElement('a');link.href=image.src;
  image.replaceWith(link);link.appendChild(image);
}
