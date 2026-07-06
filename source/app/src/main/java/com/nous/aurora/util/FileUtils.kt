package com.nous.aurora.util

import java.io.File

object FileUtils {

    fun findBooks(rootDirs: List<String>): List<File> {
        val books = mutableListOf<File>()
        val extensions = setOf("epub", "pdf", "mobi", "azw", "azw3", "txt", "md", "markdown", "fb2")

        for (root in rootDirs) {
            val dir = File(root)
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown().maxDepth(10).forEach { file ->
                if (file.isFile && file.extension.lowercase() in extensions) {
                    books.add(file)
                }
            }
        }
        return books
    }
}
