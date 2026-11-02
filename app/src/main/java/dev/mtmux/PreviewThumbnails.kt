package dev.mtmux

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import dev.mtmux.core.FileProblem
import dev.mtmux.core.RemoteFileException
import dev.mtmux.core.RemoteFilePaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Keep inline raster images small in memory; a tap still opens the cached original. */
internal object PreviewThumbnails {
    @Suppress("DEPRECATION")
    fun open(file:File):PreviewResource {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,bounds)
        val pixels=bounds.outWidth.toLong()*bounds.outHeight
        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>32768 || bounds.outHeight>32768 || pixels>100_000_000L)
            throw RemoteFileException(FileProblem.UNSUPPORTED)
        val budget=minOf(2_000_000L,Runtime.getRuntime().maxMemory()/64)
        var sample=1
        while(pixels/(sample.toLong()*sample)>budget) sample*=2
        if(sample==1) return PreviewResource(file.inputStream(),RemoteFilePaths.Kind.IMAGE,file.length(),bounds.outMimeType)
        val decoded=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample})
            ?: throw RemoteFileException(FileProblem.UNSUPPORTED)
        var image=decoded
        try {
            val orientation=runCatching {android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
            val matrix=Matrix()
            when(orientation) {
                2 -> matrix.setScale(-1f,1f)
                3 -> matrix.setRotate(180f)
                4 -> matrix.setScale(1f,-1f)
                5 -> {matrix.setRotate(90f);matrix.postScale(-1f,1f)}
                6 -> matrix.setRotate(90f)
                7 -> {matrix.setRotate(270f);matrix.postScale(-1f,1f)}
                8 -> matrix.setRotate(270f)
            }
            if(!matrix.isIdentity) image=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true)
            val bytes=ByteArrayOutputStream().also {check(image.compress(Bitmap.CompressFormat.WEBP,92,it))}.toByteArray()
            return PreviewResource(ByteArrayInputStream(bytes),RemoteFilePaths.Kind.IMAGE,bytes.size.toLong(),"image/webp")
        } finally {if(image!==decoded) image.recycle();decoded.recycle()}
    }
}
