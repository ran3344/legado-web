package io.legado.app.model.shared

import io.legado.app.data.entities.Book

/**
 * 共享书库本地镜像规则（纯逻辑，便于单元测试）
 *
 * 主设备上的书架是权威数据，本机只保留镜像：
 * 主设备有的补到本地、主设备删掉的从本地移除、本机读得更靠后的进度回传主设备。
 */
internal object SharedLibraryMirror {

    data class Plan(
        /** 需要写入本地的书籍，origin 已改写为共享书库地址 */
        val upsert: List<Book>,
        /** 本地存在但主设备已经没有的书籍 */
        val remove: List<Book>,
        /** 本地进度领先主设备，需要回传主设备的书籍 */
        val pushProgress: List<Book>,
    )

    /**
     * 把主设备的书籍改写成本地可用的镜像记录
     */
    fun retag(remote: Book, sourceUrl: String): Book {
        val book = remote.copy()
        book.origin = sourceUrl
        // 目录与正文都由主设备负责更新，本机不做自动更新
        book.canUpdate = false
        return book
    }

    fun plan(
        remoteBooks: List<Book>,
        localBooks: List<Book>,
        sourceUrl: String,
    ): Plan {
        val remoteUrls = remoteBooks.mapTo(HashSet()) { it.bookUrl }
        val localByUrl = localBooks.associateBy { it.bookUrl }
        val upsert = ArrayList<Book>(remoteBooks.size)
        val pushProgress = ArrayList<Book>()
        remoteBooks.forEach { remote ->
            val merged = retag(remote, sourceUrl)
            val local = localByUrl[remote.bookUrl]
            if (local != null) {
                merged.order = local.order
                merged.group = local.group
                if (isProgressAhead(local, merged)) {
                    merged.durChapterIndex = local.durChapterIndex
                    merged.durChapterPos = local.durChapterPos
                    merged.durChapterTime = local.durChapterTime
                    merged.durChapterTitle = local.durChapterTitle
                    pushProgress.add(merged)
                }
            }
            upsert.add(merged)
        }
        val remove = localBooks.filter { it.bookUrl !in remoteUrls }
        return Plan(upsert, remove, pushProgress)
    }

    fun isProgressAhead(local: Book, remote: Book): Boolean =
        local.durChapterIndex > remote.durChapterIndex ||
                (local.durChapterIndex == remote.durChapterIndex &&
                        local.durChapterPos > remote.durChapterPos)

    /**
     * 目录是否需要重新拉取
     */
    fun tocChanged(remote: Book, local: Book?): Boolean =
        local == null ||
                local.totalChapterNum != remote.totalChapterNum ||
                local.latestChapterTitle != remote.latestChapterTitle
}
