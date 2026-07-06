package com.nous.aurora.data.parser

object MobiNative {
    private var loaded = false

    init {
        try {
            System.loadLibrary("mobi_native")
            loaded = true
            android.util.Log.i("Aurora/MobiNative", "Library loaded OK")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("Aurora/MobiNative", "FAILED to load library: ${e.message}", e)
        } catch (e: Throwable) {
            android.util.Log.e("Aurora/MobiNative", "FAILED: ${e.message}", e)
        }
    }

    external fun extractText(filePath: String): String?

    fun isLoaded(): Boolean = loaded
}
