package io.legado.app.help

import android.net.Uri
import android.os.Build
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.lib.webdav.isWebDavNotFound
import io.legado.app.lib.webdav.isWebDavOverwriteConflict
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.findExactRemoteBook
import io.legado.app.model.localBook.localFileState
import io.legado.app.model.localBook.safeLocalFileName
import io.legado.app.model.remote.RemoteBook
import io.legado.app.model.remote.BookSyncAction
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.model.remote.WebDavLogFile
import io.legado.app.model.remote.applyRemoteBinding
import io.legado.app.model.remote.decideBookSyncAction
import io.legado.app.model.remote.filterNonEmptyLogFiles
import io.legado.app.model.remote.pendingLogUploads
import io.legado.app.model.remote.progressKey
import io.legado.app.model.remote.recordedLocalModify
import io.legado.app.model.remote.recordedRemoteModify
import io.legado.app.model.remote.recordedRemoteSize
import io.legado.app.model.remote.retainExistingLogStates
import io.legado.app.model.remote.setRemoteSyncState
import io.legado.app.model.remote.remoteBookUploadFileName
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.removePref
import io.legado.app.utils.runCatchingCancellable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * webDav初始化会访问网络,不要放到主线程
 */
object AppWebDav {
    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"

    /**
     * 单次列目录有条目上限(坚果云等), 达到该数量时只补齐, 不判定"远程已删除"
     */
    private const val REMOTE_LIST_TRUNCATE_THRESHOLD = 700

    /** 定时同步书库和日志的间隔 */
    private const val PERIODIC_SYNC_INTERVAL_MS = 5 * 60 * 1000L

    /** 启动后稍等再传日志, 避免和启动初始化抢网络 */
    private const val PERIODIC_SYNC_START_DELAY_MS = 10 * 1000L

    /** 启动时没网导致 WebDAV 没初始化成功时, 定时重试配置的间隔 */
    private const val CONFIG_RETRY_INTERVAL_MS = 30 * 60 * 1000L

    /**
     * 一次书库同步的结果, 用于给下拉刷新等手动入口提示具体原因
     *
     * 以前只返回 Boolean, 分不清"没配置/开关关了/没网/正在同步中", 出问题时界面上毫无反应
     */
    enum class SyncResult {
        SUCCESS, NOT_CONFIGURED, DISABLED, OFFLINE, BUSY, FAILED
    }

    private val syncLock = Mutex()
    private val logSyncLock = Mutex()
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"
    private val logsWebDavUrl get() = "${rootWebDavUrl}logs/"

    @Volatile
    private var periodicSyncStarted = false

    @Volatile
    private var lastConfigRetryAt = 0L

    var authorization: Authorization? = null
        private set

    var defaultBookWebDav: RemoteBookWebDav? = null

    /**
     * 最近一次同步的结果, 供手动同步入口提示原因
     */
    @Volatile
    var lastSyncResult: SyncResult = SyncResult.FAILED
        private set

    /**
     * 最近一次同步时云端 books 目录里的文件数
     *
     * 用于识别"WebDAV 地址/子目录配错, 指到了空的目录"这种情况
     */
    @Volatile
    var lastRemoteBookCount: Int = 0
        private set

    /**
     * 最近一次同步里下载失败的书籍数
     *
     * 以前单本下载失败只写日志, 外面依然提示"同步完成", 用户看到书架有书却打不开
     */
    @Volatile
    var lastSyncFailedCount: Int = 0
        private set

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    init {
        runBlocking {
            upConfig()
        }
    }

    private val rootWebDavUrl: String
        get() {
            val configUrl = appCtx.getPrefString(PreferKey.webDavUrl)?.trim()
            var url = if (configUrl.isNullOrEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            AppConfig.webDavDir?.trim()?.let {
                if (it.isNotEmpty()) {
                    url = "${url}${it}/"
                }
            }
            return url
        }

    suspend fun upConfig() {
        kotlin.runCatching {
            authorization = null
            defaultBookWebDav = null
            val account = appCtx.getPrefString(PreferKey.webDavAccount)
            val password = appCtx.getPrefString(PreferKey.webDavPassword)
            if (!account.isNullOrEmpty() && !password.isNullOrEmpty()) {
                val mAuthorization = Authorization(account, password)
                checkAuthorization(mAuthorization)
                WebDav(rootWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bookProgressUrl, mAuthorization).makeAsDir()
                WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bgWebDavUrl, mAuthorization).makeAsDir()
                WebDav(logsWebDavUrl, mAuthorization).makeAsDir()
                val rootBooksUrl = "${rootWebDavUrl}books/"
                defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, mAuthorization)
                authorization = mAuthorization
            }
        }
    }

    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization) {
        if (!WebDav(rootWebDavUrl, authorization).check()) {
            appCtx.removePref(PreferKey.webDavPassword)
            appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let {
            var files = WebDav(rootWebDavUrl, it).listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String) {
        authorization?.let {
            val webDav = WebDav(rootWebDavUrl + name, it)
            val archive = File.createTempFile("webdav-restore-", ".zip", appCtx.cacheDir)
            try {
                webDav.downloadTo(archive.absolutePath, true)
                Restore.restoreOrThrow(appCtx, Uri.fromFile(archive))
            } finally {
                archive.delete()
            }
        }
    }

    suspend fun hasBackUp(backUpName: String): Boolean {
        authorization?.let {
            val url = "$rootWebDavUrl${backUpName}"
            return WebDav(url, it).exists()
        }
        return false
    }

    suspend fun lastBackUp(): Result<WebDavFile?> {
        return runCatchingCancellable {
            authorization?.let {
                var lastBackupFile: WebDavFile? = null
                WebDav(rootWebDavUrl, it).listFiles().reversed().forEach { webDavFile ->
                    if (webDavFile.displayName.startsWith("backup")) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            }
        }
    }

    /**
     * webDav备份
     * @param fileName 备份文件名
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(fileName: String) {
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置或授权失败")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val putUrl = "$rootWebDavUrl$fileName"
        WebDav(putUrl, authorization).upload(Backup.zipFilePath)
    }

    /**
     * 获取云端所有背景名称
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("网络未连接")
            authorization.let {
                it ?: throw NoStackTraceException("webDav未配置")
                WebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片
     */
    suspend fun upBgs(files: Array<File>) {
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置或授权失败")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                WebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
    }

    /**
     * 下载背景图片
     */
    suspend fun downBgs() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(byteArray, FileUtils.getMimeType(fileName))
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun exportWebDav(uri: Uri, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(uri, FileUtils.getMimeType(fileName))
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val authorization = authorization ?: return
        if (!AppConfig.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        try {
            val bookProgress = BookProgress(book)
            val bookKey = bookProgress.bookKey ?: return
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookKey)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        try {
            val authorization = authorization ?: return
            if (!AppConfig.syncBookProgress) return
            if (!NetworkUtils.isAvailable()) return
            val bookKey = bookProgress.bookKey ?: return
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookKey)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e)
        }
    }

    private fun getProgressUrl(bookKey: String): String {
        return bookProgressUrl + getProgressFileName(bookKey)
    }

    /**
     * 进度文件名只由书库里的文件路径决定，书名/作者变化或增删章节都不会影响它。
     */
    private fun getProgressFileName(bookKey: String): String {
        return MD5Utils.md5Encode16(bookKey) + ".json"
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        val bookKey = book.progressKey
        val url = getProgressUrl(bookKey)
        kotlin.runCatching {
            val authorization = authorization ?: return null
            WebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()
                        ?.takeIf { it.bookKey == bookKey }
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            //云端还没有这本书的进度文件是正常情况(新导入的书都是这样), 不该当成错误
            if (!isWebDavNotFound(it)) {
                AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
            }
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookProgressFiles = WebDav(bookProgressUrl, authorization).listFiles()
        val map = hashMapOf<String, WebDavFile>()
        bookProgressFiles.forEach {
            map[it.displayName] = it
        }
        appDb.bookDao.all.forEach { book ->
            val bookKey = book.progressKey
            val progressFileName = getProgressFileName(bookKey)
            val webDavFile = map[progressFileName] ?: return@forEach
            if (webDavFile.lastModify <= book.syncTime) {
                //本地同步时间大于上传时间不用同步
                return@forEach
            }
            getBookProgress(book)?.let { bookProgress ->
                val newer = bookProgress.durChapterTime > book.durChapterTime ||
                        (bookProgress.durChapterTime == book.durChapterTime
                                && (bookProgress.durChapterIndex > book.durChapterIndex
                                || (bookProgress.durChapterIndex == book.durChapterIndex
                                && bookProgress.durChapterPos > book.durChapterPos)))
                if (newer) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    book.update()
                }
            }
        }
    }

    /**
     * 导入本地书籍后按配置自动上传到 WebDAV
     * 开关: [AppConfig.webDavBookAutoUpload], 不阻塞导入流程
     */
    fun autoUploadImportedBooks(books: Collection<Book>) {
        if (!AppConfig.webDavBookAutoUpload) return
        if (books.isEmpty()) return
        if (defaultBookWebDav == null) return
        Coroutine.async {
            ensureConfigured()
            val bookWebDav = defaultBookWebDav ?: return@async
            val localBooks = books.filter { it.isLocal }
            if (localBooks.isEmpty()) return@async
            // 和定时双向同步串行, 避免刚导入的书被"云端没有"判定后重复下载/删除
            syncLock.withLock {
                // 先把云端目录列一次: 云端已有同一本书时不能再走条件上传, 那样必然 412
                val remoteBooks = kotlin.runCatching {
                    withContext(IO) { bookWebDav.getRemoteBookList(bookWebDav.rootBookUrl) }
                }.getOrNull()
                localBooks.forEach { autoUploadImportedBook(bookWebDav, it, remoteBooks) }
            }
        }
    }

    private suspend fun autoUploadImportedBook(
        bookWebDav: RemoteBookWebDav,
        book: Book,
        remoteBooks: List<RemoteBook>?,
    ) {
        if (!NetworkUtils.isAvailable()) return
        val fileName = remoteBookUploadFileName(book)
        val remoteBook = remoteBooks?.let { findExactRemoteBook(it, fileName) }
        if (remoteBook != null) {
            //云端已有同一本书: 绑定过去并按修改时间谁新谁赢, 而不是当成上传失败
            bindRemoteBook(bookWebDav, book, remoteBook)
            removePendingDelete(fileName)
            return
        }
        // 已经列过目录、确认云端没有同名文件时直接用覆盖上传:
        // 有的服务端对不存在的文件也会因为 If-None-Match: * 回 412(局域网共享那套就是这样),
        // 只肯走条件上传的话新书永远传不上去
        val overwrite = remoteBooks != null
        kotlin.runCatching {
            withContext(IO) { bookWebDav.upload(book, overwrite = overwrite) }
        }.onSuccess {
            //重新导入的同名书籍不该再被待删队列删掉
            removePendingDelete(fileName)
        }.onFailure {
            currentCoroutineContext().ensureActive()
            if (it is WebDavException && isWebDavOverwriteConflict(it.responseCode)) {
                //远程已存在同名文件,不覆盖
                return@onFailure
            }
            AppLog.put("自动上传书籍<${book.name}>到WebDAV失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 把本地书绑定到云端同名文件, 并按修改时间谁新谁赢决定要不要覆盖云端
     */
    private suspend fun bindRemoteBook(
        bookWebDav: RemoteBookWebDav,
        book: Book,
        remoteBook: RemoteBook,
    ) {
        //要先判定再写同步状态, 否则刚写下的状态会让这次比较变成"没有变化"
        val action = if (book.isArchive) BookSyncAction.NONE else book.syncAction(remoteBook)
        book.applyRemoteBinding(remoteBook, bookWebDav.serverID)
        book.setRemoteSyncState(
            remoteName = remoteBook.filename,
            remoteSize = remoteBook.size,
            remoteModify = remoteBook.lastModify,
            localModify = book.localFileState()?.second,
        )
        book.update()
        if (action == BookSyncAction.UPLOAD) {
            withContext(IO) { bookWebDav.upload(book, overwrite = true) }
        }
    }

    /**
     * 删除 WebDAV 上与 [book] 对应的文件
     *
     * 删除失败(离线或远程报错)时把文件名记入待删队列, 下次同步补做,
     * 否则这本书会被 [syncLocalBooks] 当成"云端有本地没有"重新下回来。
     * @return true 表示删除已完成或已排入待删队列
     */
    suspend fun deleteRemoteBook(book: Book): Boolean {
        if (!book.isLocal) return true
        val bookWebDav = defaultBookWebDav ?: return true
        val fileName = remoteBookUploadFileName(book)
        if (!NetworkUtils.isAvailable()) {
            enqueuePendingDelete(fileName)
            return true
        }
        val deleted = withContext(IO) {
            kotlin.runCatching {
                bookWebDav.delete(book)
            }.onFailure {
                currentCoroutineContext().ensureActive()
                AppLog.put("删除WebDAV书籍<${book.name}>失败\n${it.localizedMessage}", it)
            }.getOrDefault(false)
        }
        if (deleted) {
            removePendingDelete(fileName)
        } else {
            enqueuePendingDelete(fileName)
        }
        return true
    }

    /**
     * 本地书库与 WebDAV books 目录双向同步
     * - 云端有本地没有 -> 下载入库并绑定 webDav 来源
     * - 本地新导入未上传 -> 上传(不覆盖远程同名文件)
     * - 云端已删除且本地来源为 webDav -> 删除本地记录与文件
     * - 待删队列补做
     *
     * @return true 表示本轮同步已执行
     */
    suspend fun syncLocalBooks(): Boolean {
        if (!AppConfig.webDavBookAutoSync) {
            lastSyncResult = SyncResult.DISABLED
            return false
        }
        val bookWebDav = defaultBookWebDav ?: run {
            lastSyncResult = SyncResult.NOT_CONFIGURED
            return false
        }
        if (!NetworkUtils.isAvailable()) {
            lastSyncResult = SyncResult.OFFLINE
            return false
        }
        if (!syncLock.tryLock()) {
            lastSyncResult = SyncResult.BUSY
            return false
        }
        try {
            return withContext(IO) {
                val remoteBooks = bookWebDav.getRemoteBookList(bookWebDav.rootBookUrl)
                    .filter { !it.isDir }
                lastRemoteBookCount = remoteBooks.size
                lastSyncFailedCount = 0
                // 单次列目录有条目上限, 接近上限时只补齐, 不判定"远程已删除"
                val remoteTruncated = remoteBooks.size >= REMOTE_LIST_TRUNCATE_THRESHOLD
                val remoteBookMap = remoteBooks.associateByTo(mutableMapOf()) { it.filename }
                val pendingDeletes =
                    flushPendingDeletes(bookWebDav, remoteBookMap, remoteTruncated)
                // 远端名超长时本地存的是截断名, 用截断名建一份索引供上传/删除比对
                val remoteLocalNames = remoteBookMap.keys
                    .mapTo(hashSetOf()) { safeLocalFileName(it) }
                val localBooks = appDb.bookDao.all.filter { it.isLocal }
                // 书库里有记录不代表文件还在(从备份恢复的书库只有记录没有文件), 需要按文件名分组后核对文件
                val localBookMap = localBooks
                    .mapNotNull { book -> book.syncFileName()?.let { it to book } }
                    .groupBy({ it.first }, { it.second })
                    .mapValuesTo(mutableMapOf()) { it.value.toMutableList() }
                // 1. 云端有本地没有 -> 下载; 两边都有 -> 按修改时间谁新谁赢
                remoteBookMap.values.forEach { remoteBook ->
                    if (remoteBook.filename in pendingDeletes) return@forEach
                    // 远端名超长时本地存的是截断名, 两种键都查一遍
                    val matchedBooks = localBookMap[remoteBook.filename]
                        ?: localBookMap[safeLocalFileName(remoteBook.filename)]
                    val localBook = matchedBooks?.firstOrNull { it.localFileExists() }
                    if (localBook != null) {
                        // 压缩包书籍本地存的是解压出来的章节, 大小/时间对不上, 不做自动覆盖
                        val action = if (localBook.isArchive) {
                            BookSyncAction.NONE
                        } else {
                            localBook.syncAction(remoteBook)
                        }
                        when (action) {
                            BookSyncAction.NONE -> return@forEach
                            BookSyncAction.DOWNLOAD -> Unit
                            BookSyncAction.UPLOAD -> {
                                kotlin.runCatching {
                                    bookWebDav.upload(localBook, overwrite = true)
                                }.onFailure {
                                    currentCoroutineContext().ensureActive()
                                    lastSyncFailedCount++
                                    AppLog.put(
                                        "自动上传书籍<${localBook.name}>到WebDAV失败\n${it.localizedMessage}",
                                        it
                                    )
                                }
                                return@forEach
                            }
                        }
                    }
                    kotlin.runCatching {
                        downloadRemoteBookToShelf(
                            bookWebDav,
                            remoteBook,
                            localBook,
                        )
                    }.onSuccess {
                        // 同名但路径已失效的旧记录要清掉, 否则书架出现重复条目并反复重新下载
                        matchedBooks?.filterNot { it.localFileExists() }
                            ?.forEach { removeStaleLocalBookRecord(it) }
                    }.onFailure {
                        currentCoroutineContext().ensureActive()
                        lastSyncFailedCount++
                        AppLog.put(
                            "自动下载WebDAV书籍<${remoteBook.filename}>失败\n${it.localizedMessage}",
                            it
                        )
                    }
                }
                // 2. 本地有云端没有 -> 上传
                // 列目录被截断时无法确定云端真的没有, 只在这种情况下才用条件上传
                val uploadOverwrite = !remoteTruncated
                localBooks.forEach { book ->
                    val fileName = book.syncFileName() ?: return@forEach
                    if (book.origin != BookType.localTag) return@forEach
                    if (fileName in remoteBookMap || fileName in remoteLocalNames) return@forEach
                    kotlin.runCatching {
                        bookWebDav.upload(book, overwrite = uploadOverwrite)
                    }.onSuccess {
                        //重新导入的同名书籍不该再被待删队列删掉
                        removePendingDelete(fileName)
                    }.onFailure {
                        currentCoroutineContext().ensureActive()
                        if (it is WebDavException && isWebDavOverwriteConflict(it.responseCode)) {
                            return@onFailure
                        }
                        AppLog.put("自动上传书籍<${book.name}>到WebDAV失败\n${it.localizedMessage}", it)
                    }
                }
                // 3. 云端已删除 -> 删除本地
                if (!remoteTruncated && remoteBooks.isNotEmpty()) {
                    localBooks.forEach { book ->
                        if (!book.origin.startsWith(BookType.webDavTag)) return@forEach
                        val fileName = book.syncFileName() ?: return@forEach
                        if (fileName in remoteBookMap || fileName in remoteLocalNames) {
                            return@forEach
                        }
                        removeLocalBook(book)
                    }
                }
                lastSyncResult = SyncResult.SUCCESS
                true
            }
        } finally {
            syncLock.unlock()
        }
    }

    /**
     * 异步执行双向同步, 不阻塞调用方
     */
    fun syncLocalBooksAsync() {
        if (!AppConfig.webDavBookAutoSync) return
        Coroutine.async {
            kotlin.runCatching {
                ensureConfigured()
                syncLocalBooks()
            }.onFailure {
                lastSyncResult = SyncResult.FAILED
                AppLog.put("自动同步WebDAV书库失败\n${it.localizedMessage}", it)
            }
        }
    }

    /**
     * 配置只在进程启动时初始化一次, 那时没网就会一直是 null, 之后再也不重试
     *
     * 同步前补一次, 否则用户"退出重进"也永远同步不了
     */
    private suspend fun ensureConfigured() {
        if (defaultBookWebDav != null) return
        upConfig()
    }

    /**
     * 手动触发一次书库同步(下拉刷新等入口), 完成后回调具体结果
     */
    fun syncLocalBooksManually(onFinished: (SyncResult) -> Unit) {
        Coroutine.async {
            val synced = kotlin.runCatching {
                ensureConfigured()
                syncLocalBooks()
            }.onFailure {
                lastSyncResult = SyncResult.FAILED
                AppLog.put("手动同步WebDAV书库失败\n${it.localizedMessage}", it)
            }.getOrDefault(false)
            val result = if (synced) SyncResult.SUCCESS else lastSyncResult
            withContext(Main) {
                onFinished(result)
            }
        }
    }

    private fun Book.syncFileName(): String? =
        remoteBookUploadFileName(this).takeIf { it.isNotBlank() }

    /**
     * 书库记录对应的文件是否真的还在
     *
     * 从备份恢复的书库只有记录没有文件, 只看记录会把这批书当成"本地已有"而跳过下载,
     * 结果书架上能看到书却打不开。
     */
    private fun Book.localFileExists(): Boolean = localFileState() != null

    /**
     * 本地和远端都有这本书时该做什么: 只有一边变了用变的那边, 两边都变了按修改时间谁新谁赢
     */
    private fun Book.syncAction(remoteBook: RemoteBook): BookSyncAction {
        val localState = localFileState() ?: return BookSyncAction.DOWNLOAD
        return decideBookSyncAction(
            remoteSize = remoteBook.size,
            remoteModify = remoteBook.lastModify,
            localSize = localState.first,
            localModify = localState.second,
            recordedRemoteSize = recordedRemoteSize(),
            recordedRemoteModify = recordedRemoteModify(),
            recordedLocalModify = recordedLocalModify(),
        )
    }

    private suspend fun downloadRemoteBookToShelf(
        bookWebDav: RemoteBookWebDav,
        remoteBook: RemoteBook,
        existing: Book?,
    ) {
        // 书架上已经有这本书时原地覆盖, 另存一份会多出一条同名记录
        if (existing != null && bookWebDav.overwriteRemoteBook(remoteBook, existing)) {
            resetLocalBookAfterOverwrite(existing)
            // 本地导入的同名书也要绑定到 WebDAV, 否则下面的同步状态写不进去
            existing.applyRemoteBinding(remoteBook, bookWebDav.serverID)
            existing.setRemoteSyncState(
                remoteName = remoteBook.filename,
                remoteSize = remoteBook.size,
                remoteModify = remoteBook.lastModify,
                localModify = existing.localFileState()?.second,
            )
            existing.update()
            return
        }
        val uri = bookWebDav.downloadRemoteBook(remoteBook)
        // 云端文件路径就是身份：文件名不同就是不同的书，即使书名、作者相同。
        // 只有远端文件名和本地一致（上面的 existing）才原地覆盖。
        LocalBook.importFiles(uri).forEach { book ->
            book.applyRemoteBinding(remoteBook, bookWebDav.serverID)
            book.setRemoteSyncState(
                remoteName = remoteBook.filename,
                remoteSize = remoteBook.size,
                remoteModify = remoteBook.lastModify,
                localModify = book.localFileState()?.second,
            )
            book.update()
        }
    }

    /**
     * 覆盖文件后清掉解析缓存, 让目录和正文按新内容重新解析
     */
    private fun resetLocalBookAfterOverwrite(book: Book) {
        LocalBook.withParserCacheInvalidated(book) {
            LocalBook.deleteBook(book, false)
            book.latestChapterTime = 0
            appDb.bookChapterDao.delByBook(book.bookUrl)
        }
    }

    private fun removeLocalBook(book: Book) {
        LocalBook.deleteBook(book, true)
        book.delete()
    }

    /**
     * 清掉同名的失效记录, 文件本身已经不在了所以不删文件
     */
    private fun removeStaleLocalBookRecord(book: Book) {
        LocalBook.deleteBook(book, false)
        book.delete()
    }

    /**
     * 补做待删队列, 成功删除的从队列出队并从 [remoteBookMap] 移除, 避免同轮被重新下回来
     */
    private suspend fun flushPendingDeletes(
        bookWebDav: RemoteBookWebDav,
        remoteBookMap: MutableMap<String, RemoteBook>,
        remoteTruncated: Boolean,
    ): Set<String> {
        val pending = appCtx.getPrefStringSet(PreferKey.webDavBookPendingDeletes)
            ?.toMutableSet() ?: return emptySet()
        if (pending.isEmpty()) return emptySet()
        val remaining = mutableSetOf<String>()
        pending.forEach { fileName ->
            // 待删队列里存的可能是本地截断名, 按截断名再找一遍
            val remoteBook = remoteBookMap[fileName]
                ?: remoteBookMap.values.firstOrNull { safeLocalFileName(it.filename) == fileName }
            if (remoteBook == null) {
                // 列目录被截断时无法断定远程已删除, 保留待删记录
                if (remoteTruncated) remaining.add(fileName)
                return@forEach
            }
            val deleted = kotlin.runCatching {
                WebDav(remoteBook.path, bookWebDav.authorization).delete()
            }.getOrDefault(false)
            if (deleted) {
                remoteBookMap.remove(fileName)
                remoteBookMap.remove(remoteBook.filename)
                removeLocalBookByFileName(fileName)
            } else {
                remaining.add(fileName)
            }
        }
        appCtx.putPrefStringSet(PreferKey.webDavBookPendingDeletes, remaining)
        return remaining
    }

    private fun removeLocalBookByFileName(fileName: String) {
        appDb.bookDao.getBookByFileName(fileName)?.let {
            removeLocalBook(it)
            return
        }
        // 队列里存的是远端名, 本地记录用的是截断后的名字
        val localName = safeLocalFileName(fileName)
        if (localName != fileName) {
            appDb.bookDao.getBookByFileName(localName)?.let { removeLocalBook(it) }
        }
    }

    private fun enqueuePendingDelete(fileName: String) {
        if (fileName.isBlank()) return
        val pending = appCtx.getPrefStringSet(PreferKey.webDavBookPendingDeletes)
            ?.toMutableSet() ?: mutableSetOf()
        if (pending.add(fileName)) {
            appCtx.putPrefStringSet(PreferKey.webDavBookPendingDeletes, pending)
        }
    }

    private fun removePendingDelete(fileName: String) {
        val pending = appCtx.getPrefStringSet(PreferKey.webDavBookPendingDeletes)
            ?.toMutableSet() ?: return
        if (pending.remove(fileName)) {
            appCtx.putPrefStringSet(PreferKey.webDavBookPendingDeletes, pending)
        }
    }

    /**
     * 启动周期同步: 每 5 分钟同步一次书库和日志
     *
     * 书库只在已经配置成功时执行, 避免授权失败时每轮都弹 toast;
     * 配置失败由启动同步和手动下拉刷新负责重试。
     */
    fun startPeriodicSync() {
        if (periodicSyncStarted) return
        periodicSyncStarted = true
        Coroutine.async {
            delay(PERIODIC_SYNC_START_DELAY_MS)
            while (isActive) {
                if (NetworkUtils.isAvailable() && authorization == null) {
                    val now = System.currentTimeMillis()
                    if (now - lastConfigRetryAt >= CONFIG_RETRY_INTERVAL_MS) {
                        lastConfigRetryAt = now
                        runCatching { upConfig() }
                    }
                }
                runCatching { syncLogs() }
                    .onFailure {
                        currentCoroutineContext().ensureActive()
                        AppLog.put("定时上传日志到WebDAV失败\n${it.localizedMessage}", it)
                    }
                if (AppConfig.webDavBookAutoSync && defaultBookWebDav != null) {
                    runCatching { syncLocalBooks() }
                        .onFailure {
                            currentCoroutineContext().ensureActive()
                            AppLog.put("定时同步WebDAV书库失败\n${it.localizedMessage}", it)
                        }
                }
                delay(PERIODIC_SYNC_INTERVAL_MS)
            }
        }
    }

    /**
     * 上传应用日志和崩溃日志到 WebDAV 的 logs/<设备>/ 目录
     *
     * 只传新增或变化过的文件, 传完记下大小/修改时间, 避免每轮重复上传整份日志。
     */
    suspend fun syncLogs(): Boolean {
        if (!appCtx.getPrefBoolean(PreferKey.webDavLogAutoSync, true)) return false
        if (!NetworkUtils.isAvailable()) return false
        if (!logSyncLock.tryLock()) return false
        try {
            val auth = authorization ?: return false
            val files = collectLogFiles()
            if (files.isEmpty()) return true
            val logFiles = files.map {
                WebDavLogFile(it.name, it.length(), it.lastModified())
            }
            return withContext(IO) {
                WebDav(logsWebDavUrl, auth).makeAsDir()
                val deviceUrl = deviceLogsUrl()
                WebDav(deviceUrl, auth).makeAsDir()
                val uploaded = appCtx.getPrefStringSet(PreferKey.webDavLogFileStates)
                    ?.toSet() ?: emptySet()
                val states = retainExistingLogStates(logFiles, uploaded).toMutableSet()
                val byName = files.associateBy { it.name }
                pendingLogUploads(logFiles, uploaded).forEach { logFile ->
                    val file = byName[logFile.name] ?: return@forEach
                    kotlin.runCatching {
                        WebDav(deviceUrl + UrlUtil.replaceReservedChar(file.name), auth)
                            .upload(file, "text/plain; charset=utf-8")
                    }.onSuccess {
                        states += logFile.stateKey
                    }.onFailure {
                        currentCoroutineContext().ensureActive()
                        AppLog.put("上传日志<${file.name}>到WebDAV失败\n${it.localizedMessage}", it)
                    }
                }
                appCtx.putPrefStringSet(PreferKey.webDavLogFileStates, states)
                true
            }
        } finally {
            logSyncLock.unlock()
        }
    }

    private fun collectLogFiles(): List<File> {
        val root = appCtx.externalCacheDir ?: return emptyList()
        val appLogs = File(root, "logs").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            .orEmpty()
        val crashLogs = File(root, "crash").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            .orEmpty()
        return filterNonEmptyLogFiles(appLogs + crashLogs).sortedBy { it.name }
    }

    /**
     * 每台设备一个子目录, 避免多台设备互相覆盖同名日志
     *
     * 设备名可能重复, 追加 Android ID 的哈希做区分。
     */
    private fun deviceLogsUrl(): String {
        val name = AppConfig.webDavDeviceName
            ?.normalizeFileName()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL
        val deviceHash = MD5Utils.md5Encode16(AppConst.androidId).take(8)
        val folder = UrlUtil.replaceReservedChar("${name.ifBlank { "device" }}-$deviceHash")
        return "$logsWebDavUrl$folder/"
    }

}
