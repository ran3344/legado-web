package io.legado.app.lib.webdav

open class WebDavException(
    msg: String,
    val responseCode: Int? = null,
) : Exception(msg) {

    override fun fillInStackTrace(): Throwable {
        return this
    }

}

class ObjectNotFoundException(
    msg: String,
    responseCode: Int? = null,
) : WebDavException(msg, responseCode)

internal fun isWebDavOverwriteConflict(responseCode: Int?): Boolean =
    responseCode == 409 || responseCode == 412

/** 远端没有这个文件, 是正常情况而不是出错 */
internal fun isWebDavNotFound(error: Throwable): Boolean =
    (error as? WebDavException)?.responseCode == 404
