package io.legado.app.model.shared

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isShared
import io.legado.app.help.book.sharedServerUrl
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SharedSyncReport(
    val synced: Int,
    val removed: Int,
    val tocSynced: Int,
    val pushed: Int,
) {
    val message: String
        get() = "共享书库同步完成：书籍 $synced，移除 $removed，目录 $tocSynced，回传进度 $pushed"
}

/**
 * 共享书库：把局域网内主设备（另一台运行阅读 Web 服务的设备）的书架当作自己的书架。
 *
 * - 书架：主设备为权威，本地只保留镜像
 * - 目录/正文：按需从主设备拉取，拉到后写入本地缓存，可离线阅读
 * - 进度/删除：本机改动回传主设备，其它设备同步后可见
 */
object SharedLibrary {
    const val SOURCE_NAME = "共享书库"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isEnabled: Boolean
        get() = AppConfig.sharedLibraryEnabled && AppConfig.sharedLibraryUrl.isNotBlank()

    fun sourceUrl(serverUrl: String = AppConfig.sharedLibraryUrl): String =
        BookType.sharedTag + serverUrl.trim().trimEnd('/')

    private fun requireServer(): String {
        if (!AppConfig.sharedLibraryEnabled) {
            throw NoStackTraceException("共享书库未启用")
        }
        val server = AppConfig.sharedLibraryUrl
        if (server.isBlank()) {
            throw NoStackTraceException("共享书库未设置主设备地址")
        }
        return server
    }

    private fun api(serverUrl: String) =
        SharedLibraryApi(serverUrl, AppConfig.sharedLibraryToken)

    private fun serverOf(book: Book): String {
        if (!book.isShared) throw NoStackTraceException("不是共享书库书籍")
        return book.sharedServerUrl.ifBlank { requireServer() }
    }

    /**
     * 保证本地存在共享书库的虚拟书源，目录、正文、进度等依赖书源的流程才可用
     */
    suspend fun ensureSource(serverUrl: String = requireServer()) {
        val url = sourceUrl(serverUrl)
        if (appDb.bookSourceDao.getBookSource(url) != null) return
        appDb.bookSourceDao.insert(
            BookSource(
                bookSourceUrl = url,
                bookSourceName = SOURCE_NAME,
                bookSourceGroup = SOURCE_NAME,
                bookSourceType = BookSourceType.default,
                enabled = false,
                enabledExplore = false,
                bookSourceComment = "由共享书库自动创建，书籍内容来自主设备 $serverUrl",
            )
        )
    }

    /**
     * 同步书架：拉取主设备书架、补/删本地镜像、更新目录、回传更靠前的进度
     */
    suspend fun syncLibrary(): Result<SharedSyncReport> = withContext(Dispatchers.IO) {
        runCatching {
            val server = requireServer()
            ensureSource(server)
            val source = sourceUrl(server)
            val client = api(server)
            val remoteBooks = client.getBookshelf()
            val localBooks = appDb.bookDao.getBooksByOrigin(source)
            val localByUrl = localBooks.associateBy { it.bookUrl }
            val plan = SharedLibraryMirror.plan(remoteBooks, localBooks, source)
            if (plan.upsert.isNotEmpty()) {
                appDb.bookDao.insert(*plan.upsert.toTypedArray())
            }
            plan.remove.forEach { appDb.bookDao.delete(it) }
            var tocSynced = 0
            plan.upsert.forEach { book ->
                if (!SharedLibraryMirror.tocChanged(book, localByUrl[book.bookUrl])) {
                    return@forEach
                }
                val chapters = runCatching { client.getChapterList(book.bookUrl) }
                    .onFailure { AppLog.put("共享书库：获取《${book.name}》目录失败", it) }
                    .getOrNull()
                if (chapters.isNullOrEmpty()) return@forEach
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
                tocSynced++
            }
            plan.pushProgress.forEach { book ->
                runCatching { client.saveProgress(BookProgress(book)) }
                    .onFailure { AppLog.put("共享书库：回传《${book.name}》进度失败", it) }
            }
            AppConfig.sharedLibraryLastSyncTime = System.currentTimeMillis()
            SharedSyncReport(
                synced = plan.upsert.size,
                removed = plan.remove.size,
                tocSynced = tocSynced,
                pushed = plan.pushProgress.size,
            )
        }.onFailure { it.printOnDebug() }
    }

    fun syncLibraryAsync(onResult: ((Result<SharedSyncReport>) -> Unit)? = null) {
        if (!isEnabled) return
        scope.launch {
            onResult?.invoke(syncLibrary())
        }
    }

    /**
     * 从主设备拉取目录（用于刷新目录）
     */
    suspend fun getChapterList(book: Book): List<BookChapter> =
        withContext(Dispatchers.IO) {
            val chapters = api(serverOf(book)).getChapterList(book.bookUrl)
            if (chapters.isEmpty()) {
                throw NoStackTraceException("主设备未返回目录")
            }
            chapters
        }

    /**
     * 从主设备拉取章节正文
     */
    suspend fun getContent(book: Book, chapter: BookChapter): String =
        withContext(Dispatchers.IO) {
            val content = api(serverOf(book)).getContent(book.bookUrl, chapter.index)
            if (content.isBlank()) {
                throw NoStackTraceException("主设备未返回正文")
            }
            content
        }

    /**
     * 回传阅读进度到主设备
     */
    suspend fun pushProgress(book: Book) {
        if (!book.isShared) return
        runCatching { api(serverOf(book)).saveProgress(BookProgress(book)) }
            .onFailure { AppLog.put("共享书库：回传《${book.name}》进度失败", it) }
    }

    fun pushProgressAsync(book: Book) {
        if (!AppConfig.sharedLibraryEnabled || !book.isShared) return
        scope.launch { pushProgress(book) }
    }

    /**
     * 通知主设备删除书籍，让其它设备同步后一并消失
     */
    suspend fun pushDelete(book: Book) {
        if (!book.isShared) return
        runCatching { api(serverOf(book)).deleteBook(book) }
            .onFailure { AppLog.put("共享书库：通知主设备删除《${book.name}》失败", it) }
    }

    fun pushDeleteAsync(book: Book) {
        if (!AppConfig.sharedLibraryEnabled || !book.isShared) return
        scope.launch { pushDelete(book) }
    }
}
