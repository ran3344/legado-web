package io.legado.app.model.shared

import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.reflect.Type

/**
 * 主设备阅读服务返回的数据结构, 对应 io.legado.app.api.ReturnData
 */
internal data class SharedApiResponse<T>(
    val isSuccess: Boolean = false,
    val errorMsg: String? = null,
    val data: T? = null,
)

/**
 * 共享书库: 访问主设备阅读服务的 HTTP 客户端
 */
internal class SharedLibraryApi(
    serverUrl: String,
    private val token: String,
) {
    private val base = serverUrl.trim().trimEnd('/') + "/"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val bookshelfType: Type =
        object : TypeToken<SharedApiResponse<List<Book>>>() {}.type
    private val chapterListType: Type =
        object : TypeToken<SharedApiResponse<List<BookChapter>>>() {}.type
    private val stringType: Type =
        object : TypeToken<SharedApiResponse<String>>() {}.type

    /**
     * 主设备书架
     */
    fun getBookshelf(): List<Book> =
        parse<List<Book>>(execute(get(buildUrl("getBookshelf"))), bookshelfType).orEmpty()

    /**
     * 主设备书籍目录
     */
    fun getChapterList(bookUrl: String): List<BookChapter> =
        parse<List<BookChapter>>(
            execute(get(buildUrl("getChapterList", "url" to bookUrl))),
            chapterListType,
        ).orEmpty()

    /**
     * 主设备章节正文
     */
    fun getContent(bookUrl: String, index: Int): String =
        parse<String>(
            execute(
                get(
                    buildUrl(
                        "getBookContent",
                        "url" to bookUrl,
                        "index" to index.toString(),
                    )
                )
            ),
            stringType,
        ).orEmpty()

    /**
     * 回传阅读进度
     */
    fun saveProgress(progress: BookProgress) {
        execute(
            post(
                buildUrl("saveBookProgress"),
                GSON.toJson(progress),
            )
        )
    }

    /**
     * 通知主设备删除书籍
     */
    fun deleteBook(book: Book) {
        execute(
            post(
                buildUrl("deleteBook"),
                GSON.toJson(book),
            )
        )
    }

    private fun buildUrl(path: String, vararg params: Pair<String, String>): String {
        val url = base.toHttpUrlOrNull()
            ?: throw NoStackTraceException("共享书库地址无效: $base")
        val builder = url.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        params.forEach { builder.addQueryParameter(it.first, it.second) }
        return builder.build().toString()
    }

    private fun get(url: String): Request = requestBuilder(url).get().build()

    private fun post(url: String, body: String): Request =
        requestBuilder(url)
            .post(body.toRequestBody(jsonMediaType))
            .build()

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder().url(url).apply {
            if (token.isNotEmpty()) {
                addHeader(TOKEN_HEADER, token)
            }
        }

    private fun execute(request: Request): String {
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw NoStackTraceException(
                    "主设备返回 ${response.code}: ${body.take(200)}"
                )
            }
            return body
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> parse(json: String, type: Type): T? {
        val response = runCatching {
            GSON.fromJson<Any>(json, type) as? SharedApiResponse<T>
        }.getOrNull() ?: throw NoStackTraceException("主设备返回内容无法解析")
        if (!response.isSuccess) {
            val message = response.errorMsg
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?: "主设备返回失败"
            throw NoStackTraceException(message)
        }
        return response.data
    }

    companion object {
        private const val TOKEN_HEADER = "X-Legado-Token"
    }
}
