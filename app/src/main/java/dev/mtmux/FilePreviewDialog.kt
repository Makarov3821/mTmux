package dev.mtmux

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.mtmux.core.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors

@Composable internal fun FilePreviewDialog(request:PreviewRequest,onClose:()->Unit,onExternal:(String)->Unit) {
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val dispatcher=remember {Executors.newSingleThreadExecutor().asCoroutineDispatcher()}
    var base by remember {mutableStateOf("")}
    var target by remember {mutableStateOf(request.target)}
    var pathText by remember {mutableStateOf(request.target)}
    var document by remember {mutableStateOf<PreviewDocument?>(null)}
    var error by remember {mutableStateOf<Throwable?>(null)}
    var loading by remember {mutableStateOf(true)}
    var blocked by remember {mutableStateOf(false)}
    var editPath by remember {mutableStateOf(false)}
    var awaitingPath by remember {mutableStateOf(request.binding==null && !request.target.startsWith('/') && !request.target.startsWith("~/"))}
    var retry by remember {mutableIntStateOf(0)}
    var loadGeneration by remember {mutableIntStateOf(0)}
    val history=remember {mutableStateListOf<String>()}
    val holder=remember {java.util.concurrent.atomic.AtomicReference<FilePreviewSession?>()}
    val sessionRetry=remember {java.util.concurrent.atomic.AtomicInteger(-1)}
    val alive=remember {java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(Unit) {onDispose {
        // Closing the channel unblocks IO; never wait for it on the main thread.
        alive.set(false)
        val owned=holder.getAndSet(null)
        CoroutineScope(Dispatchers.IO).launch {owned?.close();dispatcher.close()}
    }}
    LaunchedEffect(target,retry) {
        withContext(Dispatchers.Main.immediate) {
        val generation=++loadGeneration
        val requestedTarget=target
        val requestedBase=base
        val needsPath=awaitingPath
        loading=true;error=null;blocked=false;document=null
        try {
            val loaded=withContext(dispatcher) {
                if(sessionRetry.get()!=retry) {
                    holder.getAndSet(null)?.close();sessionRetry.set(retry)
                }
                val owner=holder.get() ?: FilePreviewSession(context,request).also {
                    holder.set(it)
                    if(!alive.get()) {holder.getAndSet(null)?.close();throw CancellationException()}
                }
                val initialBase=requestedBase.ifBlank {if(requestedTarget.startsWith('/')) requestedTarget.substringBeforeLast('/').ifEmpty {"/"} else owner.base()}
                val resolved=owner.resolve(requestedTarget,initialBase)
                val root=if(RemoteFilePaths.inside(resolved,initialBase)) initialBase else resolved.substringBeforeLast('/').ifEmpty {"/"}
                withContext(Dispatchers.Main) {if(generation==loadGeneration) {pathText=resolved;base=root}}
                if(needsPath) null else owner.open(resolved,root)
            }
            if(generation==loadGeneration) {
                if(awaitingPath) editPath=true
                else {document=loaded;loaded?.let {pathText=it.path}}
            }
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {if(generation==loadGeneration) {error=e;DebugLog.event(DebugLog.Event.PREVIEW_FAILED,holder.get()?.id?.hashCode() ?: 0)}}
        finally {if(generation==loadGeneration) loading=false}
        }
    }
    fun back() {if(history.isNotEmpty()) target=history.removeAt(history.lastIndex) else onClose()}
    fun openPath() {
        if(loading) return
        focus.clearFocus(force=true);keyboard?.hide()
        editPath=false;awaitingPath=false;target=pathText.trim();retry++
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,
        dismissOnClickOutside=false,securePolicy=SecureFlagPolicy.Inherit)) {
        Surface(Modifier.widthIn(max=1000.dp).fillMaxWidth().fillMaxHeight(.94f).padding(horizontal=10.dp)
            .testTag("file-preview"),shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=12.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    AppIconButton(UiSymbol.BACK,stringResource(R.string.common_back),{back()})
                    Column(Modifier.weight(1f)) {
                        Text(document?.path?.substringAfterLast('/') ?: stringResource(R.string.preview_title),
                            style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(request.server,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                    }
                    if(document!=null && error==null && !loading)
                        AppIconButton(UiSymbol.FOLDER,stringResource(R.string.preview_edit_path),{editPath=!editPath},Modifier.testTag("preview-edit-path"))
                    AppIconButton(UiSymbol.CLOSE,stringResource(R.string.common_close),onClose)
                }
                if(editPath || error!=null || awaitingPath) Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pathText,{pathText=it},label={Text(stringResource(R.string.preview_path))},minLines=2,maxLines=2,
                        enabled=!loading,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={openPath()}),
                        trailingIcon={AppIconButton(UiSymbol.COPY,stringResource(R.string.preview_copy_path),{
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.preview_path),pathText))
                        })},shape=appFieldShape,colors=appFieldColors(),modifier=Modifier.fillMaxWidth().testTag("preview-path"))
                    Button(onClick={openPath()},enabled=!loading && pathText.isNotBlank(),shape=RoundedCornerShape(14.dp),modifier=Modifier.testTag("preview-open-path")) {Text(stringResource(R.string.preview_retry))}
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(),contentAlignment=Alignment.Center) {
                    when {
                        loading -> Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
                            CircularProgressIndicator(Modifier.size(28.dp),strokeWidth=2.dp);Text(stringResource(R.string.preview_loading),style=MaterialTheme.typography.bodyMedium)
                        }
                        error!=null -> {
                            Text(stringResource(previewError(error)),Modifier.padding(24.dp),color=MaterialTheme.colorScheme.error)
                        }
                        document!=null -> {
                            val doc=document!!
                            if(doc.kind==RemoteFilePaths.Kind.IMAGE) key(doc.url) {PreviewImage(doc)}
                            else key(doc.url,MaterialTheme.colorScheme.surface,MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.onSurface) {
                                val colors=MaterialTheme.colorScheme
                                AndroidView(factory={ FilePreviewWebView(it,checkNotNull(holder.get()),doc,colors,{link ->
                    if(link.startsWith("https://") || link.startsWith("http://")) onExternal(link)
                                    else if(link!=doc.path) {if(history.size>=10) history.removeAt(0);history.add(doc.path);editPath=false;target=link}
                                },{blocked=true},{error=RemoteFileException(FileProblem.UNAVAILABLE);document=null}) },modifier=Modifier.fillMaxSize().testTag(if(doc.kind==RemoteFilePaths.Kind.SVG) "preview-svg" else "preview-webview"),
                                    onRelease={it.stopLoading();it.destroy()})
                            }
                        }
                    }
                }
                if(blocked) Text(stringResource(R.string.preview_blocked),Modifier.padding(horizontal=16.dp,vertical=6.dp),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BackHandler {back()}
    }
}

internal fun previewError(error:Throwable?)=when((error as? RemoteFileException)?.problem) {
    FileProblem.TOO_LARGE -> R.string.preview_limit
    FileProblem.UNSUPPORTED -> R.string.preview_unsupported
    FileProblem.TIMEOUT -> R.string.preview_timeout
    FileProblem.ENCODING -> R.string.preview_encoding
    FileProblem.NOT_FOUND -> R.string.preview_not_found
    FileProblem.PERMISSION -> R.string.preview_permission
    FileProblem.NOT_FILE -> R.string.preview_not_file
    FileProblem.INVALID_PATH -> R.string.preview_invalid_path
    else -> R.string.preview_error
}


@Composable private fun PreviewImage(document:PreviewDocument) {
    var bitmap by remember(document.url) {mutableStateOf<Bitmap?>(null)}
    var failed by remember {mutableStateOf(false)}
    var scale by remember {mutableFloatStateOf(1f)}
    var pan by remember {mutableStateOf(Offset.Zero)}
    var size by remember {mutableStateOf(IntSize.Zero)}
    val owned=remember {java.util.concurrent.atomic.AtomicReference<Bitmap?>()}
    val alive=remember {java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(Unit) {onDispose {alive.set(false);owned.getAndSet(null)?.recycle()}}
    LaunchedEffect(document.url) {
        withContext(Dispatchers.Main.immediate) {
        try {
            val loaded=withContext(Dispatchers.IO) {
                val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(document.file.path,options)
                if(options.outWidth<=0 || options.outHeight<=0 || options.outWidth>32768 || options.outHeight>32768 || options.outWidth.toLong()*options.outHeight>100_000_000L)
                    throw RemoteFileException(FileProblem.TOO_LARGE)
                var sample=1
                val budget=minOf(8_000_000L,Runtime.getRuntime().maxMemory()/32)
                while(options.outWidth.toLong()*options.outHeight/(sample.toLong()*sample)>budget) sample*=2
                val decoded=BitmapFactory.decodeFile(document.file.path,BitmapFactory.Options().apply {inSampleSize=sample})
                    ?: throw RemoteFileException(FileProblem.UNSUPPORTED)
                val matrix=android.graphics.Matrix()
                val orientation=runCatching {android.media.ExifInterface(document.file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
                when(orientation) {
                    2 -> matrix.setScale(-1f,1f)
                    3 -> matrix.setRotate(180f)
                    4 -> matrix.setScale(1f,-1f)
                    5 -> {matrix.setRotate(90f);matrix.postScale(-1f,1f)}
                    6 -> matrix.setRotate(90f)
                    7 -> {matrix.setRotate(270f);matrix.postScale(-1f,1f)}
                    8 -> matrix.setRotate(270f)
                }
                val image=if(matrix.isIdentity) decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also {if(it!==decoded) decoded.recycle()}
                owned.set(image)
                if(!alive.get()) {owned.getAndSet(null)?.recycle();throw CancellationException()}
                image
            }
            bitmap=loaded
        } catch(e:CancellationException) {throw e} catch(_:Exception) {failed=true}
        }
    }
    fun bounded(offset:Offset):Offset {
        val image=bitmap ?: return Offset.Zero
        val fit=minOf(size.width.toFloat()/image.width,size.height.toFloat()/image.height)
        val x=((image.width*fit*scale-size.width)/2).coerceAtLeast(0f)
        val y=((image.height*fit*scale-size.height)/2).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-x,x),offset.y.coerceIn(-y,y))
    }
    Box(Modifier.fillMaxSize().onSizeChanged {size=it},contentAlignment=Alignment.Center) {
        bitmap?.let {image -> Image(image.asImageBitmap(),document.path.substringAfterLast('/'),
            Modifier.fillMaxSize().testTag("preview-image").pointerInput(image) {detectTransformGestures {_,delta,zoom,_->
                scale=(scale*zoom).coerceIn(1f,10f);pan=bounded(pan+delta)
            }}.pointerInput(image) {detectTapGestures(onDoubleTap={scale=if(scale>1f) 1f else 2.5f;pan=Offset.Zero})}
                .graphicsLayer {scaleX=scale;scaleY=scale;translationX=pan.x;translationY=pan.y}) }
        if(failed) Text(stringResource(R.string.preview_unsupported))
        else if(bitmap==null) CircularProgressIndicator()
    }
}
