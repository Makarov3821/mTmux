package dev.mtmux

/** MiB, persisted independently of SSH/terminal settings. */
internal data class PreviewLimits(val fileMiB:Int=10,val cacheMiB:Int=50) {
    val fileBytes:Long get()=fileMiB*MIB
    val cacheBytes:Long get()=cacheMiB*MIB
    companion object {
        const val MIB=1024L*1024
        const val FILE_KEY="previewFileMiB"
        const val CACHE_KEY="previewCacheMiB"
        fun normalized(file:Int,cache:Int):PreviewLimits {
            val single=file.coerceIn(1,100)
            return PreviewLimits(single,cache.coerceIn(single,1024))
        }
    }
}
