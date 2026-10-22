package io.legado.app.data.entities

import io.legado.app.model.remote.progressKey

data class BookProgress(
    val name: String,
    val author: String,
    val durChapterIndex: Int,
    val durChapterPos: Int,
    val durChapterTime: Long,
    val durChapterTitle: String?,
    /** 书库里的相对路径/文件名，跨设备同步进度的唯一键。 */
    val bookKey: String? = null,
    /** 当前客户端自己的书籍地址，仅用于本机 Web 接口按 bookUrl 定位。 */
    val bookUrl: String? = null,
) {

    constructor(book: Book) : this(
        name = book.name,
        author = book.author,
        durChapterIndex = book.durChapterIndex,
        durChapterPos = book.durChapterPos,
        durChapterTime = book.durChapterTime,
        durChapterTitle = book.durChapterTitle,
        bookKey = book.progressKey,
        bookUrl = book.bookUrl,
    )

}
