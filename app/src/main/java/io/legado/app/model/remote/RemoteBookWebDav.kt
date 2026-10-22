package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getArchiveUri
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.update
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.findExactRemoteBook
import io.legado.app.model.localBook.saveWebDavBookFile
import io.legado.app.utils.FileDoc
import io.legado.app.utils.exists
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.runBlocking

/** origin 里记录远端文件名的属性名 */
internal const val REMOTE_FILE_NAME_ATTRIBUTE = "remoteName"

/** origin 里记录上次同步时远端文件大小的属性名 */
internal const val REMOTE_FILE_SIZE_ATTRIBUTE = "remoteSize"

/** origin 里记录上次同步时远端文件修改时间的属性名 */
internal const val REMOTE_FILE_MODIFY_ATTRIBUTE = "remoteModify"

/** origin 里记录上次同步时本地文件修改时间的属性名 */
internal const val LOCAL_FILE_MODIFY_ATTRIBUTE = "localModify"

/** 双向同步时一本书该做的动作 */
internal enum class BookSyncAction { NONE, DOWNLOAD, UPLOAD }

/**
 * 本地和远端都有这本书时, 决定谁覆盖谁
 *
 * 同步时记下远端大小/修改时间和本地文件的修改时间, 用它们判断两边各自有没有变过;
 * 只有一边变了就用变了的那边, 两边都变了(或还没有同步记录)则按修改时间谁新谁赢。
 */
internal fun decideBookSyncAction(
    remoteSize: Long,
    remoteModify: Long,
    localSize: Long,
    localModify: Long,
    recordedRemoteSize: Long?,
    recordedRemoteModify: Long?,
    recordedLocalModify: Long?,
): BookSyncAction {
    if (recordedRemoteModify == null && recordedLocalModify == null) {
        //还没有同步记录(老版本上传的书): 大小和时间都一样才不动, 否则按修改时间谁新谁赢
        //只比大小会漏掉"更新后大小恰好没变"的书, 这类更新也必须覆盖旧文件
        return when {
            localSize == remoteSize && localModify == remoteModify -> BookSyncAction.NONE
            localModify >= remoteModify -> BookSyncAction.UPLOAD
            else -> BookSyncAction.DOWNLOAD
        }
    }
    val remoteChanged = recordedRemoteModify != remoteModify ||
            (recordedRemoteSize != null && recordedRemoteSize != remoteSize)
    val localChanged = recordedLocalModify != localModify
    return when {
        localChanged && remoteChanged ->
            if (localModify >= remoteModify) BookSyncAction.UPLOAD else BookSyncAction.DOWNLOAD

        localChanged -> BookSyncAction.UPLOAD
        remoteChanged -> BookSyncAction.DOWNLOAD
        //两边都没动过但大小对不上, 本地多半是没下完的残文件
        localSize != remoteSize -> BookSyncAction.DOWNLOAD
        else -> BookSyncAction.NONE
    }
}

/** origin 里记录的远端同步状态, 非 WebDAV 书籍或没记录时返回 null */
internal fun Book.remoteSyncState(key: String): Long? {
    if (!origin.startsWith(BookType.webDavTag)) return null
    val attribute = CustomUrl(origin.substring(BookType.webDavTag.length)).getAttr()
    return (attribute[key] as? String)?.toLongOrNull()
}

/** 上次同步时远端文件的大小 */
internal fun Book.recordedRemoteSize(): Long? = remoteSyncState(REMOTE_FILE_SIZE_ATTRIBUTE)

/** 上次同步时远端文件的修改时间 */
internal fun Book.recordedRemoteModify(): Long? = remoteSyncState(REMOTE_FILE_MODIFY_ATTRIBUTE)

/** 上次同步时本地文件的修改时间 */
internal fun Book.recordedLocalModify(): Long? = remoteSyncState(LOCAL_FILE_MODIFY_ATTRIBUTE)

/**
 * 记下这次同步的远端状态, 供下次同步判断两边有没有改动
 *
 * [remoteName] 为 null 时保留原有的远端文件名
 */
internal fun Book.setRemoteSyncState(
    remoteName: String? = null,
    remoteSize: Long? = null,
    remoteModify: Long? = null,
    localModify: Long? = null,
): Book {
    if (!origin.startsWith(BookType.webDavTag)) return this
    val customUrl = CustomUrl(origin.substring(BookType.webDavTag.length))
    if (remoteName != null) {
        customUrl.putAttribute(REMOTE_FILE_NAME_ATTRIBUTE, remoteName)
    }
    customUrl.putAttribute(REMOTE_FILE_SIZE_ATTRIBUTE, remoteSize?.toString())
    customUrl.putAttribute(REMOTE_FILE_MODIFY_ATTRIBUTE, remoteModify?.toString())
    customUrl.putAttribute(LOCAL_FILE_MODIFY_ATTRIBUTE, localModify?.toString())
    origin = BookType.webDavTag + customUrl.toString()
    return this
}

/**
 * 书籍在 WebDAV 上的文件名
 *
 * 远端名可能超出本地文件系统上限, 落地时会被截断, 所以下载过的书把远端名记在 origin 里,
 * 上传/删除/比对才能和远端对上。
 */
internal fun remoteBookUploadFileName(book: Book): String =
    book.remoteFileName()
        ?: if (book.isArchive) book.archiveName else book.originName

/**
 * 阅读进度使用的稳定书籍键：
 * - 已绑定 WebDAV 的书使用远端相对路径/文件名；
 * - 本地尚未上传的书使用自己的 bookUrl，避免同名同作者互相串进度。
 *
 * 书名和作者只用于展示，同名同作者的不同文件必须各自保存进度。
 */
internal val Book.progressKey: String
    get() = remoteFileName()?.trim()?.takeIf { it.isNotEmpty() }
        ?: bookUrl.trim().ifBlank { originName.trim() }

/** origin 中记录的远端文件名, 非 WebDAV 书籍返回 null */
internal fun Book.remoteFileName(): String? {
    if (!origin.startsWith(BookType.webDavTag)) return null
    val attribute = CustomUrl(origin.substring(BookType.webDavTag.length)).getAttr()
    return (attribute[REMOTE_FILE_NAME_ATTRIBUTE] as? String)?.takeIf { it.isNotBlank() }
}

/**
 * 把本地书绑定到云端同名文件
 *
 * 绑定后再写 [setRemoteSyncState] 才会生效, 否则 origin 仍是 local, 下次同步会把
 * 这本书当成"没有同步记录", 既判断不准更新方向, 也不会跟随云端删除。
 */
internal fun Book.applyRemoteBinding(remoteBook: RemoteBook, serverID: Long?) {
    origin = BookType.webDavTag + CustomUrl(remoteBook.path)
        .putAttribute("serverID", serverID)
        .putAttribute(REMOTE_FILE_NAME_ATTRIBUTE, remoteBook.filename)
        .toString()
}

class RemoteBookWebDav(
    val rootBookUrl: String,
    val authorization: Authorization,
    val serverID: Long? = null
) : RemoteBookManager() {

    init {
        runBlocking {
            WebDav(rootBookUrl, authorization).makeAsDir()
        }
    }


    @Throws(Exception::class)
    override suspend fun getRemoteBookList(path: String): MutableList<RemoteBook> {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val remoteBooks = mutableListOf<RemoteBook>()
        //读取文件列表
        val remoteWebDavFileList: List<WebDavFile> = WebDav(path, authorization).listFiles()
        //转化远程文件信息到本地对象
        remoteWebDavFileList.forEach { webDavFile ->
            if (webDavFile.isDir
                || bookFileRegex.matches(webDavFile.displayName)
                || archiveFileRegex.matches(webDavFile.displayName)
            ) {
                //扩展名符合阅读的格式则认为是书籍
                remoteBooks.add(RemoteBook(webDavFile))
            }
        }
        return remoteBooks
    }

    override suspend fun getRemoteBook(path: String): RemoteBook? {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val webDavFile = WebDav(path, authorization).getWebDavFile()
            ?: return null
        return RemoteBook(webDavFile)
    }

    override suspend fun downloadRemoteBook(remoteBook: RemoteBook): Uri {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val webdav = WebDav(remoteBook.path, authorization)
        return webdav.downloadInputStream().let { inputStream ->
            // 落到应用私有缓存目录, 不依赖"书籍保存位置"(SAF), 免得没权限导致整本书打不开
            saveWebDavBookFile(inputStream, remoteBook.filename)
        }
    }

    /**
     * 用远端内容原地覆盖 [book] 已有的文件
     *
     * 书架上已经有这本书时不能另存一份, 否则会出现两条同名记录。
     * @return true 表示覆盖成功
     */
    suspend fun overwriteRemoteBook(remoteBook: RemoteBook, book: Book): Boolean {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val localUri = kotlin.runCatching { book.getLocalUri() }.getOrNull() ?: return false
        if (!FileDoc.fromUri(localUri, false).exists()) return false
        return WebDav(remoteBook.path, authorization).downloadInputStream().use { inputStream ->
            LocalBook.overwriteLocalBookFile(book, localUri, inputStream)
        }
    }

    suspend fun hasRemoteBook(book: Book): Boolean {
        val fileName = remoteBookUploadFileName(book)
        return findExactRemoteBook(getRemoteBookList(rootBookUrl), fileName) != null
    }

    override suspend fun upload(book: Book) {
        upload(book, overwrite = true)
    }

    suspend fun upload(book: Book, overwrite: Boolean) {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val fileName = remoteBookUploadFileName(book)
        val localBookUri = if (book.isArchive) {
            book.getArchiveUri() ?: throw NoStackTraceException("未找到压缩文件: $fileName")
        } else {
            book.getLocalUri()
        }
        val putUrl = "$rootBookUrl$fileName"
        val webDav = WebDav(putUrl, authorization)
        if (localBookUri.isContentScheme()) {
            webDav.upload(localBookUri, overwrite = overwrite)
        } else {
            webDav.upload(localBookUri.path!!, overwrite = overwrite)
        }
        val localDoc = kotlin.runCatching { FileDoc.fromUri(localBookUri, false) }.getOrNull()
        //上传后回读远端大小/修改时间, 下次同步才能判断远端有没有变过
        val remoteFile = kotlin.runCatching {
            WebDav(putUrl, authorization).getWebDavFile()
        }.getOrNull()
        book.origin = BookType.webDavTag + CustomUrl(putUrl)
            .putAttribute("serverID", serverID)
            .putAttribute(REMOTE_FILE_NAME_ATTRIBUTE, fileName)
            .putAttribute(
                REMOTE_FILE_SIZE_ATTRIBUTE,
                (remoteFile?.size ?: localDoc?.size)?.toString()
            )
            .putAttribute(
                REMOTE_FILE_MODIFY_ATTRIBUTE,
                (remoteFile?.lastModify ?: localDoc?.lastModified)?.toString()
            )
            .putAttribute(LOCAL_FILE_MODIFY_ATTRIBUTE, localDoc?.lastModified?.toString())
            .toString()
        book.update()
    }

    suspend fun delete(book: Book): Boolean {
        val fileName = remoteBookUploadFileName(book)
        val remoteBook = findExactRemoteBook(getRemoteBookList(rootBookUrl), fileName)
            ?: return true
        return WebDav(remoteBook.path, authorization).delete()
    }

    override suspend fun delete(remoteBookUrl: String) {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        WebDav(remoteBookUrl, authorization).delete()
    }

}
