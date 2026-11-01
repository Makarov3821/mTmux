package dev.mtmux

import dev.mtmux.core.RemoteFilePaths
import java.net.URI
import java.util.Locale

/** Map remote absolute references into this document's virtual origin, never phone file://. */
internal class PreviewResourceUrls(documentUrl:String, private val host:String?) {
    private val page=URI(documentUrl)
    private val prefix=page.rawPath.substringBefore("/remote/")+"/remote"
    private fun hostname(value:String)=value.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
    fun rewrite(value:String):String? {
        val reference=value.trim()
        val file=reference.startsWith("file:",ignoreCase=true)
        if(!file && (!reference.startsWith('/') || reference.startsWith("//"))) return value
        return try {
            val uri=URI(reference.replace(" ","%20"))
            if(file) {
                if(uri.rawUserInfo!=null || uri.port!=-1 || uri.rawAuthority?.contains('@')==true) return null
                val authority=uri.host ?: uri.rawAuthority.orEmpty()
                if(authority.isNotEmpty() && !authority.equals("localhost",true) &&
                    (host==null || hostname(authority)!=hostname(host))) return null
            }
            val path=uri.path ?: return null
            if(!path.startsWith('/')) return null
            val canonical=RemoteFilePaths.resolve(path,"/","/")
            val encoded=URI(null,null,canonical,null).toASCIIString()
            "${page.scheme}://${page.rawAuthority}$prefix$encoded"+
                (uri.rawQuery?.let {"?$it"} ?: "")+(uri.rawFragment?.let {"#$it"} ?: "")
        } catch(_:Exception) {null}
    }
    /** URLs arriving from WebView have already been resolved/encoded by Chromium. */
    fun remotePath(scheme:String?, authority:String?, path:String?):String? {
        if(scheme!=page.scheme || authority!=page.host || path==null) return null
        val decodedPrefix=page.path.substringBefore("/remote/")
        return when {
            path.startsWith("$decodedPrefix/remote/") -> path.removePrefix("$decodedPrefix/remote")
            path.startsWith("/${page.path.split('/')[1]}/") -> null // reserved preview assets/other documents
            path.startsWith('/') -> path // HTML/CSS root-absolute remote references
            else -> null
        }
    }

    /** Narrow URL-attribute rewrite, not an HTML sanitizer. Preserve markup and raw-text verbatim. */
    fun html(source:String,decode:(String)->String={it}):String {
        val out=StringBuilder(source.length)
        var i=0
        val rawTags=setOf("script","style","textarea","title","xmp","iframe","noembed","noframes")
        while(i<source.length) {
            val start=source.indexOf('<',i)
            if(start<0) {out.append(source,i,source.length);break}
            out.append(source,i,start)
            if(source.startsWith("<!--",start)) {
                val end=source.indexOf("-->",start+4).let {if(it<0) source.length else it+3}
                out.append(source,start,end);i=end;continue
            }
            var end=start+1;var quote:Char?=null
            while(end<source.length) {
                val c=source[end]
                if(quote!=null) {if(c==quote) quote=null}
                else if(c=='\'' || c=='"') quote=c
                else if(c=='>') break
                end++
            }
            if(end==source.length) {out.append(source,start,end);break}
            val tag=source.substring(start,end+1)
            var n=1
            while(n<tag.length && (tag[n].isLetterOrDigit() || tag[n] in "-:")) n++
            val name=tag.substring(1,n).lowercase(Locale.ROOT)
            out.append(if(name in setOf("img","a","link","source","video")) rewriteTag(tag,n,decode) else tag)
            i=end+1
            if(name in rawTags) {
                var closing=source.indexOf("</$name",i,ignoreCase=true)
                while(closing>=0 && source.getOrNull(closing+name.length+2)?.let { !it.isWhitespace() && it!='>' && it!='/' }==true)
                    closing=source.indexOf("</$name",closing+2,ignoreCase=true)
                val boundary=if(closing<0) source.length else closing
                out.append(source,i,boundary);i=boundary
            } else if(name=="plaintext") {out.append(source,i,source.length);break}
        }
        return out.toString()
    }
    private fun rewriteTag(tag:String,from:Int,decode:(String)->String):String {
        val out=StringBuilder(tag.length);var copied=0;var i=from
        while(i<tag.length-1) {
            while(i<tag.length-1 && (tag[i].isWhitespace() || tag[i]=='/')) i++
            val nameStart=i
            while(i<tag.length-1 && !tag[i].isWhitespace() && tag[i] !in "=/>\"'") i++
            if(i==nameStart) {i++;continue}
            val name=tag.substring(nameStart,i).lowercase(Locale.ROOT)
            while(i<tag.length-1 && tag[i].isWhitespace()) i++
            if(tag.getOrNull(i)!='=') continue
            i++;while(i<tag.length-1 && tag[i].isWhitespace()) i++
            val valueStart=i
            val quote=tag.getOrNull(i)?.takeIf {it=='\'' || it=='"'}
            if(quote!=null) i++
            val contentStart=i
            while(i<tag.length-1 && if(quote!=null) tag[i]!=quote else !tag[i].isWhitespace()) i++
            val value=tag.substring(contentStart,i)
            if(quote!=null && tag.getOrNull(i)==quote) i++
            if(name in setOf("src","href","poster")) {
                val decoded=decode(value)
                val mapped=rewrite(decoded)
                if(mapped!=decoded) {
                    out.append(tag,copied,valueStart)
                    out.append('"').append(mapped.orEmpty().replace("&","&amp;").replace("\"","&quot;")).append('"')
                    copied=i
                }
            }
        }
        return out.append(tag,copied,tag.length).toString()
    }
}
