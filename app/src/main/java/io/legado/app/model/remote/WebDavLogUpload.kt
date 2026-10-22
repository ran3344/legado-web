package io.legado.app.model.remote

import java.io.File

/**
 * 一个待上传日志文件在同步时的状态
 *
 * 只比较文件名、大小和修改时间, 内容没变时不重复上传
 */
internal data class WebDavLogFile(
    val name: String,
    val size: Long,
    val lastModified: Long,
) {

    val stateKey: String
        get() = "$name=$size=$lastModified"
}

/**
 * 从上次同步记录里保留仍然存在的文件状态
 *
 * 文件被轮转/清理或内容又变了时, 对应的旧状态一起丢掉, 免得状态集合无限增长,
 * 同时保证变化过的文件下一轮会重新上传。
 */
internal fun retainExistingLogStates(
    files: Collection<WebDavLogFile>,
    uploadedStates: Set<String>,
): Set<String> {
    val currentStates = files.mapTo(hashSetOf()) { it.stateKey }
    return uploadedStates.filterTo(hashSetOf()) { it in currentStates }
}

/**
 * 本轮需要上传的日志: 状态不在上次成功记录里的文件
 */
internal fun pendingLogUploads(
    files: Collection<WebDavLogFile>,
    uploadedStates: Set<String>,
): List<WebDavLogFile> = files.filter { it.stateKey !in uploadedStates }

/**
 * 只保留真正有内容的日志文件
 *
 * App 未开启“记录日志”时仍会创建 0 字节的日志文件, 这些文件上传后没有排查价值,
 * 还会让 WebDAV 的 logs 目录看起来像“同步失败”。
 */
internal fun filterNonEmptyLogFiles(files: Collection<File>): List<File> =
    files.filter { it.isFile && it.length() > 0 }
