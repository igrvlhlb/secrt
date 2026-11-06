package io.github.igrvlhlb.secrt.backup.webdav

import io.github.igrvlhlb.secrt.backup.BackupAuthException
import io.github.igrvlhlb.secrt.backup.BackupException
import io.github.igrvlhlb.secrt.backup.BackupNetworkException
import io.github.igrvlhlb.secrt.backup.BackupServerException
import io.github.igrvlhlb.secrt.backup.BackupTarget
import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentLength
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.http.protocolWithAuthority
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

/**
 * A folder on a WebDAV server: Nextcloud, ownCloud, a NAS, Apache `mod_dav`, nginx, rclone serve…
 * Uses Basic auth on every request (servers expect it up front), and only the methods every
 * server implements: `PROPFIND` (depth 1), `GET`, `PUT`, `MKCOL` and `DELETE`.
 */
class WebDavTarget(
    private val config: BackupTargetConfig.WebDav,
    engine: HttpClientEngineFactory<*> = defaultHttpEngine(),
) : BackupTarget {

    private val base: String = normalizeFolderUrl(config.url)
    private val client = httpClient(engine)
    private val authorization = basicAuth(config.username, config.password)

    /** Directories known to exist, so each is created at most once per session. */
    private val knownDirectories = mutableSetOf("")

    override val id: String = "webdav:${config.username}@$base"

    override suspend fun list(directory: String): List<String> {
        require(directory.isEmpty() || directory.endsWith("/")) { "Not a directory: $directory" }
        val url = url(directory)
        val response = send(PROPFIND, url) {
            header("Depth", "1")
            setBody(TextContent(PROPFIND_BODY, ContentType.Application.Xml))
        }
        return when (response.status.value) {
            207 -> {
                knownDirectories += directory
                childNames(Url(url).encodedPath.decodeURLPart(), parseMultistatus(response.bodyAsText()))
            }
            404 -> emptyList()
            else -> throw failure(response, "list $directory")
        }
    }

    override suspend fun get(path: String): ByteArray? {
        val response = send(HttpMethod.Get, url(path))
        return when (response.status) {
            HttpStatusCode.OK -> {
                // The server isn't trusted: don't let it make us allocate without bound.
                if ((response.contentLength() ?: 0) > MAX_FILE_BYTES) throw BackupServerException("$path is larger than any backup file")
                response.bodyAsBytes()
            }
            HttpStatusCode.NotFound, HttpStatusCode.Gone -> null
            else -> throw failure(response, "download $path")
        }
    }

    override suspend fun put(path: String, bytes: ByteArray) {
        require(!path.endsWith("/")) { "Not a file: $path" }
        val parent = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        if (parent !in knownDirectories) createDirectories(parent)
        val response = send(HttpMethod.Put, url(path)) { setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream)) }
        if (!response.status.isSuccess()) throw failure(response, "upload $path")
    }

    override suspend fun delete(path: String) {
        val response = send(HttpMethod.Delete, url(path))
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) throw failure(response, "delete $path")
    }

    override fun close() = client.close()

    /** `MKCOL`s each missing level of [directory]; one that already exists answers 405. */
    private suspend fun createDirectories(directory: String) {
        val parts = directory.trimEnd('/').split('/')
        for (i in parts.indices) {
            val dir = parts.take(i + 1).joinToString("/", postfix = "/")
            if (dir in knownDirectories) continue
            val response = send(MKCOL, url(dir))
            when {
                response.status.isSuccess() || response.status == HttpStatusCode.MethodNotAllowed -> knownDirectories += dir
                else -> throw failure(response, "create the folder $dir")
            }
        }
    }

    private fun url(path: String): String {
        require(path.isEmpty() || SAFE_PATH.matches(path)) { "Unsafe backup path: $path" }
        return base + path
    }

    private suspend fun send(method: HttpMethod, url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        client.call(method, url, authorization, block)

    companion object {
        /**
         * Checks that [url] is a WebDAV folder the user may write to, and returns the settings to
         * save. Given only a Nextcloud/ownCloud server address (`https://cloud.example.com`), finds
         * the user's files folder (`/remote.php/dav/files/<user>/`) by itself. A scheme-less
         * address is taken as `https://`.
         *
         * @throws BackupAuthException if the credentials are refused.
         * @throws BackupException if no writable WebDAV folder answers there.
         */
        suspend fun connect(
            url: String,
            username: String,
            password: String,
            engine: HttpClientEngineFactory<*> = defaultHttpEngine(),
        ): BackupTargetConfig.WebDav {
            val folder = try {
                normalizeFolderUrl(url)
            } catch (e: IllegalArgumentException) {
                throw BackupException("That isn't a valid server address")
            }
            val candidates = listOf(folder) +
                if ("/remote.php/" in folder) emptyList() else listOf("${folder}remote.php/dav/files/${username.encodeURLPathPart()}/")
            val client = httpClient(engine)
            val found = try {
                val authorization = basicAuth(username, password)
                candidates.firstOrNull { candidate ->
                    val response = client.call(PROPFIND, candidate, authorization) {
                        header("Depth", "0")
                        setBody(TextContent(PROPFIND_BODY, ContentType.Application.Xml))
                    }
                    when (response.status.value) {
                        207 -> true
                        401 -> throw BackupAuthException()
                        else -> false
                    }
                }
            } finally {
                client.close()
            }
            found ?: throw BackupServerException("No WebDAV folder answers at $folder")
            val config = BackupTargetConfig.WebDav(found, username, password)
            // Writing proves the account may create files there (some shares are read-only).
            WebDavTarget(config, engine).use { target ->
                target.put(PROBE, "secrt".encodeToByteArray())
                target.delete(PROBE)
            }
            return config
        }

        /** Larger than any segment or attachment (attachments are capped at 20 MB). */
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        private const val PROBE = "secrt/connection-test.tmp"
        private val SAFE_PATH = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*/?""")
        private val PROPFIND = HttpMethod("PROPFIND")
        private val MKCOL = HttpMethod("MKCOL")
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
    }
}

/** `https://host/some/folder/`, with a trailing slash and without query or fragment. */
internal fun normalizeFolderUrl(input: String): String {
    val text = input.trim().let { if ("://" in it) it else "https://$it" }
    val url = try {
        Url(text)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid URL: $input", e)
    }
    require(url.protocol.name == "http" || url.protocol.name == "https") { "Only http and https are supported" }
    require(url.host.isNotEmpty()) { "Missing host" }
    val path = url.encodedPath.ifEmpty { "/" }
    return url.protocolWithAuthority + path + if (path.endsWith("/")) "" else "/"
}

/**
 * Names of [requestPath]'s direct children among [entries] (both decoded paths), directories
 * with a trailing `/`. Servers differ in whether hrefs are absolute URLs or paths, and in how
 * they escape them, so everything is compared decoded.
 */
internal fun childNames(requestPath: String, entries: List<DavEntry>): List<String> {
    val parent = requestPath.trimEnd('/') + "/"
    return entries.mapNotNull { entry ->
        val path = entry.path.trimEnd('/')
        if (!path.startsWith(parent)) return@mapNotNull null
        val name = path.removePrefix(parent)
        if (name.isEmpty() || '/' in name) null else if (entry.isCollection) "$name/" else name
    }
}

internal data class DavEntry(val path: String, val isCollection: Boolean)

/**
 * Reads a `207 Multi-Status` body. A full XML parser would be overkill for two properties, and
 * common Kotlin has none: this matches elements whatever their namespace prefix (`d:`, `D:`, a
 * default namespace...), which is all servers vary in.
 */
internal fun parseMultistatus(xml: String): List<DavEntry> =
    RESPONSE.findAll(xml).mapNotNull { response ->
        val block = response.groupValues[1]
        val href = HREF.find(block)?.groupValues?.get(1)?.trim()?.unescapeXml() ?: return@mapNotNull null
        val path = if ("://" in href) "/" + href.substringAfter("://").substringAfter('/', "") else href
        DavEntry(path.decodeURLPart(), COLLECTION.containsMatchIn(block))
    }.toList()

private val RESPONSE = Regex("""<(?:[\w.-]+:)?response\b[^>]*>([\s\S]*?)</(?:[\w.-]+:)?response\s*>""")
private val HREF = Regex("""<(?:[\w.-]+:)?href\b[^>]*>([\s\S]*?)</(?:[\w.-]+:)?href\s*>""")
private val COLLECTION = Regex("""<(?:[\w.-]+:)?collection\b[^>]*/?>""")

private fun String.unescapeXml(): String = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""").replace(this) { m ->
    when (val e = m.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> {
            val code = if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()
            if (code <= 0xFFFF) code.toChar().toString() else m.value
        }
    }
}

private fun basicAuth(username: String, password: String) = "Basic " + Base64.encode("$username:$password".encodeToByteArray())

private fun HttpStatusCode.isSuccess() = value in 200..299

private fun httpClient(engine: HttpClientEngineFactory<*>) = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 60_000
    }
}

/** Sends a request, turning transport failures into [BackupNetworkException]. */
private suspend fun HttpClient.call(
    method: HttpMethod,
    url: String,
    authorization: String,
    block: HttpRequestBuilder.() -> Unit,
): HttpResponse = try {
    request(url) {
        this.method = method
        header(HttpHeaders.Authorization, authorization)
        block()
    }
} catch (e: CancellationException) {
    throw e
} catch (e: BackupException) {
    throw e
} catch (e: Exception) {
    throw BackupNetworkException(e)
}

private suspend fun failure(response: HttpResponse, action: String): BackupException = when (response.status.value) {
    401 -> BackupAuthException()
    403 -> BackupServerException("The server doesn't allow this account to $action")
    507 -> BackupServerException("The server is out of space")
    else -> BackupServerException("The server couldn't $action (HTTP ${response.status.value})")
}

/** The HTTP engine of the platform (OkHttp on Android and the desktop). */
internal expect fun defaultHttpEngine(): HttpClientEngineFactory<*>
