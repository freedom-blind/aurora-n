package com.nous.aurora

import android.app.Application
import android.content.Intent
import com.nous.aurora.data.BookRepository
import com.nous.aurora.data.db.AuroraDatabase
import com.nous.aurora.data.parser.ParserFactory
import com.nous.aurora.ui.debug.DebugService
import com.nous.aurora.ui.debug.ErrorActivity
import com.nous.aurora.util.EasterEgg
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class AuroraApp : Application() {

    lateinit var db: BookRepository
        private set

    lateinit var publicationOpener: PublicationOpener
        private set

    lateinit var assetRetriever: AssetRetriever
        private set

    val easterEgg = EasterEgg.getTheme()

    override fun onCreate() {
        super.onCreate()
        instance = this

        setupGlobalExceptionHandler()
        db = BookRepository(AuroraDatabase.getInstance(this))

        val tmpDir = File(cacheDir, "mobi_temp")
        tmpDir.mkdirs()
        ParserFactory.setTempDir(tmpDir)
        ParserFactory.setPreferences(getSharedPreferences("settings", MODE_PRIVATE))

        // Initialize Readium toolkit
        val httpClient = DefaultHttpClient()
        assetRetriever = AssetRetriever(contentResolver, httpClient)
        publicationOpener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                this,
                httpClient,
                assetRetriever,
                PdfiumDocumentFactory(this),
                emptyList()
            ),
            contentProtections = emptyList()
        )

        if (DebugService.isEnabled()) {
            startService(Intent(this, DebugService::class.java))
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            System.gc()
        }
    }

    private fun setupGlobalExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val trace = sw.toString()
            try { File(getExternalFilesDir(null), "crash.log").writeText(trace) } catch (_: Exception) {}
            android.util.Log.e("Aurora/Crash", "Fatal exception", throwable)
            try {
                startActivity(Intent(this, ErrorActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(ErrorActivity.EXTRA_ERROR, trace)
                })
            } catch (e2: Throwable) {
                try { File(getExternalFilesDir(null), "Aurora/crash.log").writeText(trace) } catch (_: Throwable) {}
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    companion object {
        lateinit var instance: AuroraApp
            private set
    }
}