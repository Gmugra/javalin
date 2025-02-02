package io.javalin.jetty

import io.javalin.core.util.Header
import io.javalin.core.util.JavalinLogger
import io.javalin.core.util.OptionalDependency
import io.javalin.core.util.Util
import io.javalin.http.LeveledBrotliStream
import io.javalin.http.LeveledGzipStream
import org.eclipse.jetty.http.MimeTypes
import org.eclipse.jetty.util.resource.Resource
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


object JettyPrecompressingResourceHandler {

    val compressedFiles = ConcurrentHashMap<String, ByteArray>()

    @JvmField
    var resourceMaxSize: Int = 2 * 1024 * 1024 // the unit of resourceMaxSize is byte

    val excludedMimeTypes = setOf(
        "image/",
        "audio/",
        "video/",
        "application/compress",
        "application/zip",
        "application/gzip",
        "application/bzip2",
        "application/brotli",
        "application/x-xz",
        "application/x-rar-compressed"
    )

    fun handle(resource: Resource, req: HttpServletRequest, res: HttpServletResponse): Boolean {
        if (resource.exists() && !resource.isDirectory) {
            val target = req.getAttribute("jetty-target") as String
            var acceptCompressType = CompressType.getByAcceptEncoding(req.getHeader(Header.ACCEPT_ENCODING) ?: "")
            val contentType = MimeTypes.getDefaultMimeByExtension(target) // get content type by file extension
            if (contentType == null || excludedMimeType(contentType)) {
                acceptCompressType = CompressType.NONE
            }
            val resultByteArray = getStaticResourceByteArray(resource, target, acceptCompressType) ?: return false
            res.setContentLength(resultByteArray.size)
            res.setHeader(Header.CONTENT_TYPE, contentType)

            if (acceptCompressType != CompressType.NONE)
                res.setHeader(Header.CONTENT_ENCODING, acceptCompressType.typeName)

            val weakETag = resource.weakETag // jetty resource use weakETag too
            req.getHeader(Header.IF_NONE_MATCH)?.let { etag ->
                if (etag == weakETag) {
                    res.status = 304
                    return true
                }
            }
            res.setHeader(Header.ETAG, weakETag)
            resultByteArray.inputStream().copyTo(res.outputStream)
            res.outputStream.close()
            return true
        }
        return false
    }

    private fun getStaticResourceByteArray(resource: Resource, target: String, type: CompressType): ByteArray? {
        if (resource.length() > resourceMaxSize) {
            JavalinLogger.warn(
                "Static file '$target' is larger than configured max size for pre-compression ($resourceMaxSize bytes).\n" +
                        "You can configure the max size with `JettyPrecompressingResourceHandler.resourceMaxSize = newMaxSize`."
            )
            return null
        }
        return compressedFiles.computeIfAbsent(target + type.extension) { getCompressedByteArray(resource, type) }
    }

    private fun getCompressedByteArray(resource: Resource, type: CompressType): ByteArray {
        val fileInput = resource.inputStream
        val byteArrayOutputStream = ByteArrayOutputStream()
        val outputStream: OutputStream = when {
            type == CompressType.GZIP -> {
                LeveledGzipStream(byteArrayOutputStream, 9) // use max-level compression
            }
            type == CompressType.BR && Util.dependencyIsPresent(OptionalDependency.JVMBROTLI) -> {
                LeveledBrotliStream(byteArrayOutputStream, 11) // use max-level compression
            }
            else -> byteArrayOutputStream
        }
        fileInput.copyTo(outputStream)
        fileInput.close()
        outputStream.close()
        return byteArrayOutputStream.toByteArray()
    }

    private fun excludedMimeType(mimeType: String) =
        if (mimeType == "") false else excludedMimeTypes.any { excluded -> mimeType.contains(excluded, ignoreCase = true) }

    enum class CompressType(val typeName: String, val extension: String) {
        GZIP("gzip", ".gz"),
        BR("br", ".br"),
        NONE("", "");

        fun acceptEncoding(acceptEncoding: String): Boolean {
            return acceptEncoding.contains(typeName, ignoreCase = true)
        }

        companion object {
            fun getByAcceptEncoding(acceptEncoding: String): CompressType {
                return values().find { it.acceptEncoding(acceptEncoding) } ?: NONE
            }
        }
    }
}
