package io.legado.app.model.remote

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.archiveName
import io.legado.app.lib.webdav.isWebDavOverwriteConflict
import io.legado.app.model.analyzeRule.CustomUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteBookWebDavUploadTest {

    @Test
    fun `book identity is the file path not name and author`() {
        val first = Book(
            bookUrl = "content://books/first/novel.txt",
            origin = "${BookType.localTag}::novel.txt",
            originName = "novel.txt",
            name = "Same Name",
            author = "Same Author",
        )
        val second = Book(
            bookUrl = "content://books/second/novel.txt",
            origin = "${BookType.localTag}::novel.txt",
            originName = "novel.txt",
            name = "Same Name",
            author = "Same Author",
        )

        assertTrue(first.progressKey != second.progressKey)
        assertEquals("content://books/first/novel.txt", first.progressKey)
        assertEquals("content://books/second/novel.txt", second.progressKey)

        first.applyRemoteBinding(
            RemoteBook("remote/novel.txt", "https://dav/books/remote/novel.txt", 1, 2),
            serverID = 1,
        )
        assertEquals("remote/novel.txt", first.progressKey)

        val migrations = readProjectFile("src/main/java/io/legado/app/data/DatabaseMigrations.kt")
        val entity = readProjectFile("src/main/java/io/legado/app/data/entities/Book.kt")
        assertTrue(migrations.contains("DROP INDEX IF EXISTS index_books_name_author"))
        assertFalse(entity.contains("Index(value = [\"name\", \"author\"], unique = true)"))
    }

    @Test
    fun `archive upload keeps the archive file name`() {
        val archiveBook = Book(
            origin = "${BookType.localTag}::source.zip",
            originName = "chapter.txt",
            type = BookType.text or BookType.local or BookType.archive,
        )

        assertEquals("source.zip", remoteBookUploadFileName(archiveBook))
        assertEquals("chapter.txt", remoteBookUploadFileName(Book(originName = "chapter.txt")))
    }

    @Test
    fun `archive name ignores custom WebDAV attributes`() {
        val archiveBook = Book(
            origin = BookType.webDavTag + CustomUrl("https://example.com/books/source.zip")
                .putAttribute("serverID", 7L)
                .toString(),
            type = BookType.text or BookType.local or BookType.archive,
        )

        assertEquals("source.zip", archiveBook.archiveName)
        assertEquals("source.zip", remoteBookUploadFileName(archiveBook))
    }

    @Test
    fun `conditional upload conflicts require overwrite confirmation`() {
        assertTrue(isWebDavOverwriteConflict(409))
        assertTrue(isWebDavOverwriteConflict(412))
        assertFalse(isWebDavOverwriteConflict(null))
        assertFalse(isWebDavOverwriteConflict(401))
        assertFalse(isWebDavOverwriteConflict(500))
    }

    @Test
    fun `download upload choice is remembered and checks before overwrite`() {
        val appConfig = readProjectFile(
            "src/main/java/io/legado/app/help/config/AppConfig.kt"
        )
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        )
        val remoteBook = readProjectFile(
            "src/main/java/io/legado/app/model/remote/RemoteBookWebDav.kt"
        )
        val webDav = readProjectFile(
            "src/main/java/io/legado/app/lib/webdav/WebDav.kt"
        )

        assertTrue(appConfig.contains("var webDavBookAutoUpload: Boolean"))
        assertTrue(activity.contains("isChecked = AppConfig.webDavBookAutoUpload"))
        assertTrue(activity.contains("AppConfig.webDavBookAutoUpload = isChecked"))
        assertTrue(activity.contains("viewModel.getBook()?.let { confirmAndUploadBook(it) }"))
        assertTrue(activity.contains("confirmAndUploadBook(book, onFinished)"))
        assertTrue(activity.contains("bookWebDav.hasRemoteBook(book)"))
        assertTrue(activity.contains("R.string.webdav_book_exists_confirm"))
        assertTrue(activity.contains("overwrite = false"))
        assertTrue(activity.contains("isWebDavOverwriteConflict(e.responseCode)"))
        assertTrue(activity.contains("showUploadOverwriteConfirm(book, bookWebDav, onFinished)"))
        assertTrue(activity.contains("overwrite = true"))
        assertTrue(remoteBook.contains("findExactRemoteBook(getRemoteBookList(rootBookUrl), fileName)"))
        assertTrue(remoteBook.contains("webDav.upload(localBookUri, overwrite = overwrite)"))
        assertTrue(remoteBook.contains("webDav.upload(localBookUri.path!!, overwrite = overwrite)"))
        val conditionalHeader = "if (!overwrite) addHeader(\"If-None-Match\", \"*\")"
        assertEquals(2, webDav.split(conditionalHeader).size - 1)
        val importedBook = activity.substringAfter("private fun onWebBookImported")
            .substringBefore("private fun showDecompressFileImportAlert")
        assertFalse(importedBook.contains("book.bookUrl ="))
    }

    @Test
    fun `local book deletion resolves the exact WebDAV file before local removal`() {
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        )
        val remoteBook = readProjectFile(
            "src/main/java/io/legado/app/model/remote/RemoteBookWebDav.kt"
        )

        assertTrue(activity.contains("AppWebDav.defaultBookWebDav != null"))
        assertTrue(activity.contains("deleteRemote = deleteRemoteCheckBox?.isChecked == true"))
        val deleteBook = activity.substringAfter(
            "private fun deleteBook(book: Book, deleteOriginal: Boolean, deleteRemote: Boolean)"
        ).substringBefore("private fun finishDeleteBook")
        val remoteDelete = deleteBook.indexOf("AppWebDav.deleteRemoteBook(book)")
        val failedDelete = deleteBook.indexOf("if (!deleted)")
        val failedDeleteReturn = deleteBook.indexOf("return@launch", failedDelete)
        val localDelete = deleteBook.lastIndexOf("finishDeleteBook(book, deleteOriginal)")
        assertTrue(
            remoteDelete >= 0 &&
                failedDelete > remoteDelete &&
                failedDeleteReturn > failedDelete &&
                localDelete > failedDeleteReturn
        )
        assertTrue(remoteBook.contains("val fileName = remoteBookUploadFileName(book)"))
        assertTrue(
            remoteBook.contains(
                "findExactRemoteBook(getRemoteBookList(rootBookUrl), fileName)"
            )
        )
        assertTrue(remoteBook.contains("WebDav(remoteBook.path, authorization).delete()"))
    }

    @Test
    fun `imported local books upload automatically and deletes stay in sync`() {
        val appConfig = readProjectFile(
            "src/main/java/io/legado/app/help/config/AppConfig.kt"
        )
        val localConfig = readProjectFile(
            "src/main/java/io/legado/app/help/config/LocalConfig.kt"
        )
        val appWebDav = readProjectFile(
            "src/main/java/io/legado/app/help/AppWebDav.kt"
        )
        val importViewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBookViewModel.kt"
        )
        val fileAssociation = readProjectFile(
            "src/main/java/io/legado/app/ui/association/FileAssociationViewModel.kt"
        )
        val bookController = readProjectFile(
            "src/main/java/io/legado/app/api/controller/BookController.kt"
        )
        val manageActivity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/manage/BookshelfManageActivity.kt"
        )
        val manageViewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/book/manage/BookshelfManageViewModel.kt"
        )

        // 单一开关,默认开启;旧开关已移除,避免两份口径
        assertTrue(appConfig.contains("PreferKey.webDavBookAutoUpload, true"))
        // 删除远程文件默认开启: 删除即真删, 云端不留残骸
        assertTrue(appConfig.contains("PreferKey.webDavBookAutoDelete, true"))
        assertFalse(localConfig.contains("uploadImportedBookToWebDav"))

        // 所有本地导入入口都接入自动上传
        assertTrue(appWebDav.contains("fun autoUploadImportedBooks(books: Collection<Book>)"))
        assertTrue(importViewModel.contains("AppWebDav.autoUploadImportedBooks(importedBooks)"))
        assertTrue(fileAssociation.contains("AppWebDav.autoUploadImportedBooks(listOf(book))"))
        assertTrue(bookController.contains("AppWebDav.autoUploadImportedBooks(listOf(it))"))

        // 自动上传不覆盖远程已有文件
        val autoUpload = appWebDav.substringAfter("private suspend fun autoUploadImportedBook")
            .substringBefore("suspend fun deleteRemoteBook")
        // 已经列过目录确认云端没有同名文件时用覆盖上传, 免得服务端 If-None-Match 处理有误导致传不上去
        assertTrue(autoUpload.contains("val overwrite = remoteBooks != null"))
        assertTrue(autoUpload.contains("bookWebDav.upload(book, overwrite = overwrite)"))
        assertTrue(autoUpload.contains("isWebDavOverwriteConflict(it.responseCode)"))

        // 删除时同步删除远程文件
        assertTrue(appWebDav.contains("suspend fun deleteRemoteBook(book: Book): Boolean"))
        assertTrue(manageViewModel.contains("AppWebDav.deleteRemoteBook(it)"))
        assertTrue(manageActivity.contains("isChecked = AppConfig.webDavBookAutoDelete"))
        assertTrue(manageActivity.contains("deleteRemoteCheckBox?.isChecked == true"))
    }

    @Test
    fun `local books stay in sync with the WebDAV books directory`() {
        val appConfig = readProjectFile(
            "src/main/java/io/legado/app/help/config/AppConfig.kt"
        )
        val preferKey = readProjectFile(
            "src/main/java/io/legado/app/constant/PreferKey.kt"
        )
        val appWebDav = readProjectFile(
            "src/main/java/io/legado/app/help/AppWebDav.kt"
        )
        val app = readProjectFile(
            "src/main/java/io/legado/app/App.kt"
        )
        val configViewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/config/ConfigViewModel.kt"
        )
        val bookController = readProjectFile(
            "src/main/java/io/legado/app/api/controller/BookController.kt"
        )

        // 开关默认开启, 待删队列有独立的 key
        assertTrue(appConfig.contains("PreferKey.webDavBookAutoSync, true"))
        assertTrue(preferKey.contains("webDavBookPendingDeletes"))

        // 启动时和改完 WebDAV 配置后都会触发同步
        assertTrue(appWebDav.contains("suspend fun syncLocalBooks(): Boolean"))
        assertTrue(app.contains("AppWebDav.syncLocalBooksAsync()"))
        assertTrue(configViewModel.contains("AppWebDav.syncLocalBooksAsync()"))

        // 云端有本地没有 -> 下载; 本地新导入 -> 上传; 云端删除 -> 删除本地
        val sync = appWebDav.substringAfter("suspend fun syncLocalBooks(): Boolean")
            .substringBefore("fun syncLocalBooksAsync()")
        assertTrue(sync.contains("downloadRemoteBookToShelf("))
        assertTrue(sync.contains("localBookMap[remoteBook.filename]"))
        assertFalse(appWebDav.contains("sameIdentity"))
        assertTrue(appWebDav.contains("MD5Utils.md5Encode16(bookKey) + \".json\""))
        assertTrue(appWebDav.contains("it.bookKey == bookKey"))
        // 两边都有这本书时按修改时间谁新谁赢(判定抽在 Book.syncAction() 里)
        assertTrue(sync.contains("localBook.syncAction(remoteBook)"))
        assertTrue(appWebDav.contains("decideBookSyncAction("))
        // 列目录没被截断时不再走条件上传, 免得服务端 If-None-Match 处理有误导致一直传不上去
        assertTrue(sync.contains("val uploadOverwrite = !remoteTruncated"))
        assertTrue(sync.contains("bookWebDav.upload(book, overwrite = uploadOverwrite)"))
        assertTrue(sync.contains("removeLocalBook(book)"))
        assertTrue(sync.contains("flushPendingDeletes(bookWebDav, remoteBookMap, remoteTruncated)"))

        // 列目录被截断或远程为空时不判定"远程已删除", 避免误删本地书籍
        assertTrue(sync.contains("REMOTE_LIST_TRUNCATE_THRESHOLD"))
        assertTrue(sync.contains("if (!remoteTruncated && remoteBooks.isNotEmpty())"))

        // 远程删除失败记入待删队列, 成功后同轮就从远程列表里移除, 不会被重新下回来
        val deleteRemote = appWebDav
            .substringAfter("suspend fun deleteRemoteBook(book: Book): Boolean")
            .substringBefore("suspend fun syncLocalBooks()")
        assertTrue(deleteRemote.contains("enqueuePendingDelete(fileName)"))
        assertTrue(appWebDav.contains("remoteBookMap.remove(fileName)"))
        assertTrue(appWebDav.contains("getPrefStringSet(PreferKey.webDavBookPendingDeletes)"))

        // 还在待删队列里的文件同轮不再下载, 重新导入的同名书籍上传成功后出队
        assertTrue(sync.contains("if (remoteBook.filename in pendingDeletes) return@forEach"))
        assertTrue(sync.contains("removePendingDelete(fileName)"))

        // 网页端删书与手机端共用同一个书库, 要一并清掉本地文件和远程文件
        val webDelete = bookController.substringAfter("suspend fun deleteBook(postData: String?)")
            .substringBefore("suspend fun saveBookProgress")
        assertTrue(webDelete.contains("AppWebDav.deleteRemoteBook(book)"))
        assertTrue(webDelete.contains("LocalBook.deleteBook(book, true)"))
    }

    @Test
    fun `newer modification time wins when both sides changed`() {
        // 两边都变了: 本地更新 -> 上传覆盖云端
        assertEquals(
            BookSyncAction.UPLOAD,
            decideBookSyncAction(
                remoteSize = 200, remoteModify = 1_000, localSize = 300, localModify = 2_000,
                recordedRemoteSize = 100, recordedRemoteModify = 500, recordedLocalModify = 500,
            )
        )
        // 两边都变了: 云端更新 -> 下载覆盖本地
        assertEquals(
            BookSyncAction.DOWNLOAD,
            decideBookSyncAction(
                remoteSize = 200, remoteModify = 3_000, localSize = 300, localModify = 2_000,
                recordedRemoteSize = 100, recordedRemoteModify = 500, recordedLocalModify = 500,
            )
        )
    }

    @Test
    fun `only the changed side is synced`() {
        // 只有本地变了 -> 上传
        assertEquals(
            BookSyncAction.UPLOAD,
            decideBookSyncAction(
                remoteSize = 100, remoteModify = 500, localSize = 300, localModify = 900,
                recordedRemoteSize = 100, recordedRemoteModify = 500, recordedLocalModify = 500,
            )
        )
        // 只有云端变了(大小变了但修改时间没变) -> 下载
        assertEquals(
            BookSyncAction.DOWNLOAD,
            decideBookSyncAction(
                remoteSize = 400, remoteModify = 500, localSize = 100, localModify = 500,
                recordedRemoteSize = 100, recordedRemoteModify = 500, recordedLocalModify = 500,
            )
        )
        // 两边都没变 -> 不动
        assertEquals(
            BookSyncAction.NONE,
            decideBookSyncAction(
                remoteSize = 100, remoteModify = 500, localSize = 100, localModify = 500,
                recordedRemoteSize = 100, recordedRemoteModify = 500, recordedLocalModify = 500,
            )
        )
    }

    @Test
    fun `half downloaded local file is fetched again`() {
        // 两边都没动过但大小对不上, 说明本地是没下完的残文件
        assertEquals(
            BookSyncAction.DOWNLOAD,
            decideBookSyncAction(
                remoteSize = 700_000, remoteModify = 500, localSize = 86, localModify = 500,
                recordedRemoteSize = 700_000, recordedRemoteModify = 500, recordedLocalModify = 500,
            )
        )
    }

    @Test
    fun `books without sync record fall back to modification time`() {
        // 老版本上传的书没有同步记录: 大小相同但本地时间更新, 说明本地换过新版本
        assertEquals(
            BookSyncAction.UPLOAD,
            decideBookSyncAction(
                remoteSize = 100, remoteModify = 500, localSize = 100, localModify = 900,
                recordedRemoteSize = null, recordedRemoteModify = null, recordedLocalModify = null,
            )
        )
        // 大小相同但云端时间更新 -> 下载覆盖本地旧版本
        assertEquals(
            BookSyncAction.DOWNLOAD,
            decideBookSyncAction(
                remoteSize = 100, remoteModify = 900, localSize = 100, localModify = 500,
                recordedRemoteSize = null, recordedRemoteModify = null, recordedLocalModify = null,
            )
        )
        // 大小和时间都一样才认为没有变化
        assertEquals(
            BookSyncAction.NONE,
            decideBookSyncAction(
                remoteSize = 100, remoteModify = 500, localSize = 100, localModify = 500,
                recordedRemoteSize = null, recordedRemoteModify = null, recordedLocalModify = null,
            )
        )
        // 大小不同时同样按修改时间判断
        assertEquals(
            BookSyncAction.UPLOAD,
            decideBookSyncAction(
                remoteSize = 100, remoteModify = 500, localSize = 300, localModify = 900,
                recordedRemoteSize = null, recordedRemoteModify = null, recordedLocalModify = null,
            )
        )
    }

    @Test
    fun `local book bound to remote records sync state`() {
        val book = Book(
            origin = "${BookType.localTag}::book.txt",
            originName = "book.txt",
            name = "book",
        )
        val remote = RemoteBook("book.txt", "https://dav/books/book.txt", 123, 456)

        book.applyRemoteBinding(remote, 9L)
        book.setRemoteSyncState(
            remoteName = remote.filename,
            remoteSize = remote.size,
            remoteModify = remote.lastModify,
            localModify = 789,
        )

        assertTrue(book.origin.startsWith(BookType.webDavTag))
        assertEquals("book.txt", book.remoteFileName())
        assertEquals(123L, book.recordedRemoteSize() ?: -1L)
        assertEquals(456L, book.recordedRemoteModify() ?: -1L)
        assertEquals(789L, book.recordedLocalModify() ?: -1L)
    }

    @Test
    fun `webdav books land in the app private cache directory`() {
        val remoteBook = readProjectFile(
            "src/main/java/io/legado/app/model/remote/RemoteBookWebDav.kt"
        )
        val localBook = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/LocalBook.kt"
        )
        val extensions = readProjectFile(
            "src/main/java/io/legado/app/help/book/BookExtensions.kt"
        )

        // 下载不再依赖"书籍保存位置", 免得目录失效/没有权限时整本书打不开
        assertFalse(remoteBook.contains("AppConfig.defaultBookTreeUri"))
        assertTrue(remoteBook.contains("saveWebDavBookFile(inputStream, remoteBook.filename)"))
        assertTrue(
            localBook.contains("internal val webDavBookCacheDir")
        )
        assertTrue(localBook.contains("File(appCtx.filesDir, \"webdavBooks\")"))
        assertTrue(
            localBook.contains(
                "internal fun saveWebDavBookFile(inputStream: InputStream, fileName: String): Uri"
            )
        )
        // 换设备/清空数据后要能从缓存目录把文件找回来
        assertTrue(extensions.contains("File(webDavBookCacheDir, safeLocalFileName(originName))"))
    }

    @Test
    fun `expected webdav misses are not reported as errors`() {
        val appWebDav = readProjectFile("src/main/java/io/legado/app/help/AppWebDav.kt")
        val webDav = readProjectFile("src/main/java/io/legado/app/lib/webdav/WebDav.kt")
        val exception = readProjectFile(
            "src/main/java/io/legado/app/lib/webdav/WebDavException.kt"
        )

        // 云端还没有这本书的进度文件 -> 404 是正常情况(新导入的书都这样), 不该写错误日志
        val progress = appWebDav
            .substringAfter("suspend fun getBookProgress(book: Book)")
            .substringBefore("suspend fun downloadAllBookProgress")
        assertTrue(progress.contains("if (!isWebDavNotFound(it))"))
        assertTrue(exception.contains("internal fun isWebDavNotFound(error: Throwable)"))

        // 条件上传撞上远端已有同名文件 -> 412 是调用方预期的结果: 不写日志, 报错也换成中文
        val conditionalUpload = webDav.substringAfter("if (!overwrite) addHeader")
            .substringBefore("suspend fun upload(byteArray")
        assertTrue(conditionalUpload.contains("isWebDavOverwriteConflict(code)"))
        assertTrue(conditionalUpload.contains("远端已存在同名文件"))
        assertFalse(conditionalUpload.contains("AppLog.put(\"WebDav上传失败"))

        // 导入后自动上传先看云端有没有同名文件, 有就绑上去按修改时间谁新谁赢
        val autoUpload = appWebDav.substringAfter("fun autoUploadImportedBooks")
            .substringBefore("suspend fun deleteRemoteBook")
        assertTrue(autoUpload.contains("findExactRemoteBook(it, fileName)"))
        assertTrue(autoUpload.contains("bindRemoteBook(bookWebDav, book, remoteBook)"))
        assertTrue(
            appWebDav.contains("private suspend fun bindRemoteBook(")
        )
        assertTrue(appWebDav.contains("private fun Book.syncAction(remoteBook: RemoteBook)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
