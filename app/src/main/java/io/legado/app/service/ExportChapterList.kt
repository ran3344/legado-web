package io.legado.app.service

/**
 * 导出前是否需要重新解析本地书籍目录
 *
 * 本地书籍导入时只记录文件时间, 不会解析目录, 若导入后一直没打开过,
 * 章节表是空的而文件本身又没有变化, 只按"文件是否变化"判断就会跳过解析,
 * 导出时一章都写不出来, 只剩下书名/作者/简介书头
 */
internal fun shouldRefreshChapterList(chapterCount: Int, localModified: Boolean): Boolean {
    return chapterCount == 0 || localModified
}
