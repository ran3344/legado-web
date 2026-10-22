package io.legado.app.service

internal fun resolveExportChapterContent(content: String?, isVolume: Boolean): String? {
    return when {
        content != null -> content
        isVolume -> ""
        else -> null
    }
}

/**
 * 导出时只有真正取到正文的章节才计数。
 *
 * 卷名、空章节或只有章节标题的内容不算有效正文, 否则整本书没有正文时也会被当成导出成功。
 */
internal fun hasExportChapterBody(content: String?): Boolean = !content.isNullOrBlank()
