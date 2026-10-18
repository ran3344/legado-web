package io.legado.app.web

import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.BookSourceCheckController
import io.legado.app.api.controller.HttpLogController
import io.legado.app.api.controller.ReplaceRuleController
import io.legado.app.api.controller.ReviewController
import io.legado.app.api.controller.RssSourceController
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.utils.AssetsWeb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okio.Pipe
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI

class HttpServer(port: Int) : NanoHTTPD(port) {
    private val assetsWeb = AssetsWeb("web")
    private val chapterResponseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET && session.uri == "/getBookContent") {
            return streamBookContent(session)
        }
        var returnData: ReturnData? = null
        var shouldCloseConnection = false
        val ct = ContentType(session.headers["content-type"]).tryUTF8()
        session.headers["content-type"] = ct.contentTypeHeader
        var uri = session.uri
        val logQuery = if (uri == "/legacyReviewPage") "<redacted>" else session.queryParameterString

        val startAt = System.currentTimeMillis()
        LogUtils.d(TAG) {
            "${session.method.name} - $uri - $logQuery - Start($startAt)"
        }

        try {
            when (session.method) {
                Method.OPTIONS -> {
                    val response = newFixedLengthResponse(
                        Response.Status.OK,
                        "text/plain; charset=utf-8",
                        ""
                    )
                    response.addHeader("Access-Control-Allow-Methods", "GET, POST")
                    response.addHeader("Access-Control-Allow-Headers", "content-type, x-legado-token")
                    response.addWebHeaders(session.headers["origin"], uri)
                    //response.addHeader("Access-Control-Max-Age", "3600");
                    return response
                }

                Method.POST -> {
                    if (uri in POST_API_ROUTES) {
                        val requestError = when {
                            uri == "/saveJsSource" -> {
                                BookSourceController.validateJsSourceRequest(session.headers)
                            }

                            uri in PROTECTED_SOURCE_WRITE_ROUTES &&
                                !BookSourceController.hasValidJsSourceApiToken(session.headers) -> {
                                ReturnData().setErrorMsg("Web 书源访问令牌未配置或不正确")
                            }

                            else -> null
                        }
                        if (requestError != null) {
                            returnData = requestError
                            shouldCloseConnection = true
                        } else {
                            val files = HashMap<String, String>()
                            session.parseBody(files)
                            return streamPostResponse(
                                session = session,
                                uri = uri,
                                postData = files["postData"],
                                parameters = session.parameters.toMap(),
                                files = files.toMap(),
                            )
                        }
                    }
                }

                Method.GET -> {
                    val parameters = session.parameters
                    if (uri == "/legacyReviewPage") {
                        return legacyReviewPageResponse(
                            ReviewController.getLegacyReviewPage(parameters),
                            session.headers["origin"],
                        )
                    }
                    val requestError = if (
                        uri in PROTECTED_HTTP_LOG_READ_ROUTES &&
                        !BookSourceController.hasValidJsSourceApiToken(session.headers)
                    ) {
                        ReturnData().setErrorMsg("Web 书源访问令牌未配置或不正确")
                    } else {
                        null
                    }
                    if (requestError != null) {
                        returnData = requestError
                        shouldCloseConnection = true
                    } else {
                        returnData = when (uri) {
                            "/getBookSource" -> BookSourceController.getSource(parameters)
                            "/getBookSources" -> BookSourceController.sources
                            "/getBookSourcesForManagement" -> BookSourceCheckController.sources(parameters)
                            "/getBookSourceCheckStates" -> BookSourceCheckController.states()
                            "/getJsSourceApiTokenRequired" ->
                                BookSourceController.isJsSourceApiTokenRequired
                            "/getHttpLogs" -> HttpLogController.getLogs(parameters)
                            "/getHttpLog" -> HttpLogController.getLog(parameters)
                            "/getBookshelf" -> BookController.bookshelf
                            "/getChapterList" -> BookController.getChapterList(parameters)
                            "/refreshToc" -> BookController.refreshToc(parameters)
                            "/getReviewSummary" -> ReviewController.getSummary(parameters)
                            "/getReviewDetail" -> ReviewController.getDetail(parameters)
                            "/getReviewReplies" -> ReviewController.getReplies(parameters)
                            "/cover" -> BookController.getCover(parameters)
                            "/image" -> BookController.getImg(parameters)
                            "/getReadConfig" -> BookController.getWebReadConfig()
                            "/getRssSource" -> RssSourceController.getSource(parameters)
                            "/getRssSources" -> RssSourceController.sources
                            "/getReplaceRules" -> ReplaceRuleController.allRules
                            else -> null
                        }
                    }
                }

                else -> Unit
            }

            if (returnData == null) {
                if (uri.endsWith("/"))
                    uri += "index.html"
                return assetsWeb.getResponse(uri).apply {
                    addWebHeaders(session.headers["origin"], uri)
                }
            }

            val response = if (returnData.data is Bitmap) {
                val outputStream = ByteArrayOutputStream()
                (returnData.data as Bitmap).compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val byteArray = outputStream.toByteArray()
                outputStream.close()
                val inputStream = ByteArrayInputStream(byteArray)
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/png",
                    inputStream,
                    byteArray.size.toLong()
                )
            } else {
                val data = returnData.data
                if (data is List<*> && data.size > 3000) {
                    val pipe = Pipe(16 * 1024)
                    Coroutine.async {
                        pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                            GSON.toJson(returnData, it)
                        }
                    }
                    newChunkedResponse(
                        Response.Status.OK,
                        JSON_MIME,
                        pipe.source.buffer().inputStream()
                    )
                } else {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        JSON_MIME,
                        GSON.toJson(returnData)
                    )
                }
            }
            response.addHeader("Access-Control-Allow-Methods", "GET, POST")
            response.addWebHeaders(session.headers["origin"], uri)
            if (shouldCloseConnection) {
                response.closeConnection(true)
            }
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - $logQuery - End($startAt)"
            }
            return response
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - $logQuery - Error End($startAt)\n$e\n${e.stackTraceStr}"
            }
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                e.message ?: "Internal server error"
            ).apply {
                addWebHeaders(session.headers["origin"], uri)
                closeConnection(true)
            }
        }

    }

    override fun stop() {
        chapterResponseScope.cancel()
        super.stop()
    }

    private fun streamBookContent(session: IHTTPSession): Response {
        val pipe = Pipe(64 * 1024)
        val parameters = session.parameters.toMap()
        chapterResponseScope.launch {
            try {
                val data = BookController.getBookContent(parameters)
                pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                    GSON.toJson(data, it)
                }
            } catch (error: Throwable) {
                runCatching {
                    pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                        GSON.toJson(
                            ReturnData().setErrorMsg(
                                error.message ?: "章节内容获取失败",
                            ),
                            it,
                        )
                    }
                }
            } finally {
                runCatching { pipe.sink.close() }
            }
        }
        return newChunkedResponse(
            Response.Status.OK,
            JSON_MIME,
            pipe.source.buffer().inputStream(),
        ).apply {
            addHeader("Access-Control-Allow-Methods", "GET, POST")
            addHeader("Access-Control-Allow-Headers", "content-type, x-legado-token")
            addWebHeaders(session.headers["origin"], session.uri)
        }
    }

    private fun streamPostResponse(
        session: IHTTPSession,
        uri: String,
        postData: String?,
        parameters: Map<String, List<String>>,
        files: Map<String, String>,
    ): Response {
        val pipe = Pipe(16 * 1024)
        val origin = session.headers["origin"]
        val openedSourceUrl = parameters["openedSourceUrl"]?.firstOrNull()
        chapterResponseScope.launch {
            try {
                val result = when (uri) {
                    "/saveBookSource" -> BookSourceController.saveSource(postData)
                    "/startBookSourceCheck" -> BookSourceCheckController.start(postData)
                    "/stopBookSourceCheck" -> BookSourceCheckController.stop(postData)
                    "/saveBookSources" -> BookSourceController.saveSources(postData)
                    "/saveJsSource" -> BookSourceController.saveJsSource(postData, openedSourceUrl)
                    "/deleteBookSources" -> BookSourceController.deleteSources(postData)
                    "/saveBook" -> BookController.saveBook(postData)
                    "/deleteBook" -> BookController.deleteBook(postData)
                    "/saveBookProgress" -> BookController.saveBookProgress(postData)
                    "/addLocalBook" -> BookController.addLocalBook(parameters, files)
                    "/saveReadConfig" -> BookController.saveWebReadConfig(postData)
                    "/openLegacyReview" -> ReviewController.openLegacyReview(postData, origin)
                    "/runLegacyReview" -> ReviewController.runLegacyReview(postData)
                    "/saveRssSource" -> RssSourceController.saveSource(postData)
                    "/saveRssSources" -> RssSourceController.saveSources(postData)
                    "/deleteRssSources" -> RssSourceController.deleteSources(postData)
                    "/saveReplaceRule" -> ReplaceRuleController.saveRule(postData)
                    "/deleteReplaceRule" -> ReplaceRuleController.delete(postData)
                    "/testReplaceRule" -> ReplaceRuleController.testRule(postData)
                    else -> null
                }
                pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                    GSON.toJson(result, it)
                }
            } catch (error: Throwable) {
                runCatching {
                    pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                        GSON.toJson(
                            ReturnData().setErrorMsg(error.message ?: "Request failed"),
                            it,
                        )
                    }
                }
            } finally {
                runCatching { pipe.sink.close() }
            }
        }
        return newChunkedResponse(
            Response.Status.OK,
            JSON_MIME,
            pipe.source.buffer().inputStream(),
        ).apply {
            addHeader("Access-Control-Allow-Methods", "GET, POST")
            addHeader("Access-Control-Allow-Headers", "content-type, x-legado-token")
            addWebHeaders(origin, uri)
        }
    }

    companion object {
        private const val TAG = "HttpServer"
        private const val JSON_MIME = "application/json; charset=utf-8"
        private const val VUE_CONTENT_SECURITY_POLICY =
            "default-src 'self' data: blob:; " +
                "script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src * data: blob:; font-src 'self' data: http: https:; " +
                "connect-src * ws: wss:; frame-src 'self' http: https:; " +
                "object-src 'none'; base-uri 'self'"
        private const val LEGACY_REVIEW_RESOURCE_POLICY =
            "default-src 'none'; " +
                "script-src 'unsafe-inline' 'unsafe-eval'; " +
                "style-src 'unsafe-inline'; img-src http: https: data: blob:; " +
                "media-src http: https: data: blob:; font-src http: https: data:; " +
                "connect-src 'none'; object-src 'none'; base-uri http: https:; " +
                "form-action 'none'"
        private val PROTECTED_SOURCE_WRITE_ROUTES = setOf(
            "/startBookSourceCheck",
            "/stopBookSourceCheck",
            "/saveBookSource",
            "/saveBookSources",
            "/deleteBookSources",
            "/saveRssSource",
            "/saveRssSources",
            "/deleteRssSources",
            "/saveReplaceRule",
            "/deleteReplaceRule",
            "/testReplaceRule",
            "/openLegacyReview",
            "/runLegacyReview",
        )
        private val PROTECTED_HTTP_LOG_READ_ROUTES = setOf(
            "/getBookSourceCheckStates",
            "/getBookSourcesForManagement",
            "/getHttpLogs",
            "/getHttpLog",
        )
        private val POST_API_ROUTES = setOf(
            "/saveBookSource",
            "/saveBookSources",
            "/startBookSourceCheck",
            "/stopBookSourceCheck",
            "/saveJsSource",
            "/deleteBookSources",
            "/saveBook",
            "/deleteBook",
            "/saveBookProgress",
            "/addLocalBook",
            "/saveReadConfig",
            "/openLegacyReview",
            "/runLegacyReview",
            "/saveRssSource",
            "/saveRssSources",
            "/deleteRssSources",
            "/saveReplaceRule",
            "/deleteReplaceRule",
            "/testReplaceRule",
        )
    }

    private fun Response.addWebHeaders(origin: String?, uri: String) {
        addHeader("X-Content-Type-Options", "nosniff")
        origin?.let { addHeader("Access-Control-Allow-Origin", it) }
        if (
            uri in PROTECTED_HTTP_LOG_READ_ROUTES ||
            uri == "/getJsSourceApiTokenRequired"
        ) {
            addHeader("Cache-Control", "no-store")
        }
        if (uri.startsWith("/vue/") && uri.endsWith(".html")) {
            addHeader("Cache-Control", "no-cache")
            addHeader("Content-Security-Policy", VUE_CONTENT_SECURITY_POLICY)
        }
        if (uri.startsWith("/vue/assets/")) {
            addHeader("Cache-Control", "public, max-age=31536000, immutable")
        }
    }

    private fun legacyReviewPageResponse(
        page: ReviewController.LegacyReviewWebPage?,
        origin: String?,
    ): Response {
        val protectedHtml = page?.html?.let {
            val meta = "<meta http-equiv=\"Content-Security-Policy\" " +
                "content=\"$LEGACY_REVIEW_RESOURCE_POLICY\">"
            val head = it.indexOf("<head", ignoreCase = true)
            val headEnd = if (head >= 0) it.indexOf('>', head) else -1
            if (headEnd >= 0) it.substring(0, headEnd + 1) + meta + it.substring(headEnd + 1)
            else meta + it
        }
        return if (protectedHtml == null) {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain; charset=utf-8",
                "旧评论会话无效或已过期"
            )
        } else {
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", protectedHtml)
        }.apply {
            addWebHeaders(origin, "/legacyReviewPage")
            addHeader("Cache-Control", "no-store")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader(
                "Content-Security-Policy",
                "sandbox allow-scripts allow-modals; $LEGACY_REVIEW_RESOURCE_POLICY; " +
                    "frame-ancestors ${allowedFrameAncestor(page?.frameOrigin)}"
            )
        }
    }

    private fun allowedFrameAncestor(origin: String?): String {
        val uri = origin?.let { runCatching { URI(it) }.getOrNull() } ?: return "'self'"
        if (uri.scheme !in setOf("http", "https") ||
            uri.rawAuthority.isNullOrBlank() ||
            !uri.rawUserInfo.isNullOrBlank() ||
            !uri.rawPath.isNullOrBlank() ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            return "'self'"
        }
        return "${uri.scheme}://${uri.rawAuthority}"
    }

}
