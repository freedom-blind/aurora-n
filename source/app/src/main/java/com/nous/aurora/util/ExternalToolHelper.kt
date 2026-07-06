package com.nous.aurora.util

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.TimeUnit

object ExternalToolHelper {

    private const val PREFS_NAME = "external_tool_prefs"
    private const val KEY_CALIBRE_PATH = "calibre_path"
    private const val KEY_ASKED_INSTALL = "asked_calibre_install"

    data class ToolResult(val success: Boolean, val outputPath: String? = null, val error: String? = null)

    /**
     * Find ebook-convert binary on the device.
     * Checks: Termux prefix, common Android paths.
     */
    fun findEbookConvert(): String? {
        val candidates = listOf(
            "/data/data/com.termux/files/usr/bin/ebook-convert",
            "/data/data/com.termux/files/usr/bin/calibre/ebook-convert",
            "/system/bin/ebook-convert",
            "/system/xbin/ebook-convert",
            "/su/bin/ebook-convert",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.canExecute()) return path
        }
        return null
    }

    /**
     * Check if ebook-convert is available (cached result).
     */
    fun isEbookConvertAvailable(ctx: Context): Boolean {
        // Check saved path first
        val saved = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CALIBRE_PATH, null)
        if (saved != null && File(saved).canExecute()) return true

        // Try to find it
        val found = findEbookConvert()
        if (found != null) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CALIBRE_PATH, found).apply()
            return true
        }
        return false
    }

    /**
     * Convert a file to EPUB/TXT using ebook-convert.
     * Returns the output file path on success.
     */
    fun convertWithCalibre(inputPath: String, ctx: Context): ToolResult {
        val calibre = findEbookConvert() ?: return ToolResult(false, error = "ebook-convert not found")
        val outputFile = File(ctx.cacheDir, "calibre_output_${System.currentTimeMillis()}.epub")

        return try {
            val process = ProcessBuilder(
                calibre,
                inputPath,
                outputFile.absolutePath,
                "--max-toc-links", "0",
                "--no-default-epub-cover"
            )
                .directory(ctx.cacheDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(120, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                return ToolResult(false, error = "转换超时（120秒）")
            }

            if (process.exitValue() != 0) {
                return ToolResult(false, error = "转换失败: ${output.take(200)}")
            }

            if (outputFile.exists() && outputFile.length() > 100) {
                ToolResult(true, outputPath = outputFile.absolutePath)
            } else {
                ToolResult(false, error = "输出文件为空或过小")
            }
        } catch (e: Exception) {
            ToolResult(false, error = e.message ?: "未知错误")
        }
    }

    /**
     * Has the user been asked about installing calibre before?
     */
    fun hasAskedAboutInstall(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED_INSTALL, false)
    }

    /**
     * Mark that we've asked the user about installing.
     */
    fun markAskedAboutInstall(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED_INSTALL, true).apply()
    }

    /**
     * Get install instructions for Termux users.
     */
    fun getInstallInstructions(): String {
        return "在 Termux 中运行以下命令安装 Calibre：\n\n" +
            "pkg install python\n" +
            "pip install calibre\n\n" +
            "安装完成后重启 Aurora 即可使用。"
    }
}
