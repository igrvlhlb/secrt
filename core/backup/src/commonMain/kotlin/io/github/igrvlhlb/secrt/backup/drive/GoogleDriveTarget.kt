package io.github.igrvlhlb.secrt.backup.drive

import io.github.igrvlhlb.secrt.backup.BackupException
import io.github.igrvlhlb.secrt.backup.BackupServerException
import io.github.igrvlhlb.secrt.backup.BackupTarget
import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import io.github.igrvlhlb.secrt.backup.webdav.defaultHttpEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentLength
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * The hidden app data folder (`appDataFolder`) of a Google Drive, through the Drive v3 REST API.
 * The user sees it only as "secrt" with its size under Drive's Settings › Manage apps, where they
 * can also delete it; other apps can't read it.
 *
 * Drive has no paths: files have ids, names and parent folders, and two files may share a name.
 * Each backup file is stored flat in `appDataFolder` under its whole path as its name
 * (`secrt/<vaultId>/log/<deviceId>-<n>.seg`), and folders are derived from the names. The list of
 * files is read once per target (CloudBackup opens one per operation) and kept up to date here.
 *
 * Two devices creating the same shared file at the same moment (`vault.key`, say) leave two
 * copies: reads take the most recently modified one, and the next write removes the others.
 */
class GoogleDriveTarget(
    private val config: BackupTargetConfig.GoogleDrive,
    private val google: GoogleSignIn,
    engine: HttpClientEngineFactory<*> = defaultHttpEngine(),
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
    /** Waits before retrying when Drive asks to slow down or fails for a moment. */
    private val retryDelaysMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000),
) : BackupTarget {

    override val id: String = "gdrive:${config.account}"

    private val client = googleHttpClient(engine)
    private var token: String? = null
    private val indexLock = Mutex()

    /** Path → its copies on Drive, newest first. Loaded on first use. */
    private var index: MutableMap<String, List<DriveFile>>? = null

    override suspend fun list(directory: String): List<String> {
        require(directory.isEmpty() || directory.endsWith("/")) { "Not a directory: $directory" }
        return files().keys
            .filter { it.startsWith(directory) }
            .map { it.removePrefix(directory).let { rest -> if ('/' in rest) rest.substringBefore('/') + "/" else rest } }
            .distinct()
    }

    override suspend fun get(path: String): ByteArray? {
        checkPath(path)
        val file = files()[path]?.firstOrNull() ?: return null
        val response = send("download $path", allow404 = true) {
            method = HttpMethod.Get
            url("${endpoints.api}/drive/v3/files/${file.id}")
            parameter("alt", "media")
        }
        if (response.status.value == 404) {
            files().remove(path)
            return null
        }
        // Drive isn't trusted more than any server: don't let it make us allocate without bound.
        if ((response.contentLength() ?: 0) > MAX_FILE_BYTES) throw BackupServerException("$path is larger than any backup file")
        return response.bodyAsBytes()
    }

    override suspend fun put(path: String, bytes: ByteArray) {
        checkPath(path)
        require(!path.endsWith("/")) { "Not a file: $path" }
        val copies = files()[path].orEmpty()
        val uploaded = copies.firstOrNull()?.let { update(it.id, path, bytes) } ?: create(path, bytes)
        copies.drop(1).forEach { deleteFile(it.id, path) }
        files()[path] = listOf(uploaded)
    }

    override suspend fun delete(path: String) {
        checkPath(path)
        files().remove(path)?.forEach { deleteFile(it.id, path) }
    }

    override fun close() = client.close()

    private suspend fun files(): MutableMap<String, List<DriveFile>> = indexLock.withLock {
        index ?: loadIndex().also { index = it }
    }

    private suspend fun loadIndex(): MutableMap<String, List<DriveFile>> {
        val all = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val response = send("list the backup's files") {
                method = HttpMethod.Get
                url("${endpoints.api}/drive/v3/files")
                parameter("spaces", "appDataFolder")
                parameter("pageSize", 1000)
                parameter("fields", "nextPageToken,files($FILE_FIELDS)")
                pageToken?.let { parameter("pageToken", it) }
            }
            val page = lenientJson.decodeFromString(FileList.serializer(), response.bodyAsText())
            all += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return all.filter { SAFE_PATH.matches(it.name) && !it.name.endsWith("/") }
            .groupBy { it.name }
            .mapValuesTo(mutableMapOf()) { (_, copies) -> copies.sortedWith(NEWEST_FIRST) }
    }

    private suspend fun create(path: String, bytes: ByteArray): DriveFile {
        val metadata = JsonObject(mapOf("name" to JsonPrimitive(path), "parents" to JsonArray(listOf(JsonPrimitive("appDataFolder")))))
        if (bytes.size > SIMPLE_UPLOAD_MAX_BYTES) return resumable("upload $path", HttpMethod.Post, "${endpoints.api}/upload/drive/v3/files", metadata, bytes)
        val boundary = "secrt-" + Random.nextBytes(12).toHex()
        val body = (
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n"
            ).encodeToByteArray() + bytes + "\r\n--$boundary--\r\n".encodeToByteArray()
        val response = send("upload $path") {
            method = HttpMethod.Post
            url("${endpoints.api}/upload/drive/v3/files")
            parameter("uploadType", "multipart")
            parameter("fields", FILE_FIELDS)
            setBody(ByteArrayContent(body, ContentType.parse("multipart/related; boundary=$boundary")))
        }
        return lenientJson.decodeFromString(DriveFile.serializer(), response.bodyAsText())
    }

    private suspend fun update(id: String, path: String, bytes: ByteArray): DriveFile {
        if (bytes.size > SIMPLE_UPLOAD_MAX_BYTES) {
            return resumable("upload $path", HttpMethod.Patch, "${endpoints.api}/upload/drive/v3/files/$id", JsonObject(emptyMap()), bytes)
        }
        val response = send("upload $path") {
            method = HttpMethod.Patch
            url("${endpoints.api}/upload/drive/v3/files/$id")
            parameter("uploadType", "media")
            parameter("fields", FILE_FIELDS)
            setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
        }
        return lenientJson.decodeFromString(DriveFile.serializer(), response.bodyAsText())
    }

    /** Drive's resumable upload, sent in one piece: the way to upload files above 5 MB. */
    private suspend fun resumable(action: String, method: HttpMethod, url: String, metadata: JsonObject, bytes: ByteArray): DriveFile {
        val start = send(action) {
            this.method = method
            url(url)
            parameter("uploadType", "resumable")
            parameter("fields", FILE_FIELDS)
            header("X-Upload-Content-Type", "application/octet-stream")
            header("X-Upload-Content-Length", bytes.size.toString())
            setBody(TextContent(metadata.toString(), ContentType.Application.Json))
        }
        val session = start.headers[HttpHeaders.Location] ?: throw BackupServerException("Google Drive didn't start the upload")
        val response = send(action) {
            this.method = HttpMethod.Put
            url(session)
            setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
        }
        return lenientJson.decodeFromString(DriveFile.serializer(), response.bodyAsText())
    }

    private suspend fun deleteFile(id: String, path: String) {
        send("delete $path", allow404 = true) {
            method = HttpMethod.Delete
            url("${endpoints.api}/drive/v3/files/$id")
        }
    }

    /**
     * Sends a request with a current access token: gets a new one once if Drive refuses it, and
     * retries with growing waits when Drive asks to slow down or fails for a moment. Returns
     * successful responses (and 404s with [allow404]); throws a [BackupException] otherwise.
     */
    private suspend fun send(action: String, allow404: Boolean = false, block: HttpRequestBuilder.() -> Unit): HttpResponse {
        var newToken = false
        var retries = 0
        while (true) {
            val current = token ?: google.accessToken(config, stale = null).also { token = it }
            val response = call {
                client.request {
                    block()
                    header(HttpHeaders.Authorization, "Bearer $current")
                }
            }
            val status = response.status.value
            if (status in 200..299 || (allow404 && status == 404)) return response
            if (status == 401 && !newToken) {
                newToken = true
                token = google.accessToken(config, stale = current)
                continue
            }
            val error = DriveError.parse(status, response.bodyAsText())
            if (error.isTemporary && retries < retryDelaysMillis.size) {
                delay(retryDelaysMillis[retries++])
                continue
            }
            throw error.toException(action)
        }
    }

    private fun checkPath(path: String) = require(path.isEmpty() || SAFE_PATH.matches(path)) { "Unsafe backup path: $path" }

    @Serializable
    private data class DriveFile(val id: String, val name: String = "", val modifiedTime: String = "")

    @Serializable
    private data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

    private class DriveError(val status: Int, val reasons: Set<String>, val message: String?) {
        /** Too many requests, or a passing failure on Google's side: worth another try. */
        val isTemporary: Boolean
            get() = status == 429 || status in 500..504 || (status == 403 && reasons.any { it in RATE_LIMITED })

        fun toException(action: String): BackupException = when {
            status == 401 -> GoogleAuthException("Google refused secrt access to your Drive. Connect your Google account again.")
            "storageQuotaExceeded" in reasons -> BackupServerException("Your Google storage is full")
            reasons.any { it == "accessNotConfigured" || it == "SERVICE_DISABLED" } ->
                BackupServerException("The Google Drive API isn't enabled for this build of secrt")
            status == 403 || status == 429 -> BackupServerException("Google Drive doesn't let secrt $action" + (message?.let { ": $it" } ?: ""))
            else -> BackupServerException("Google Drive couldn't $action (HTTP $status)")
        }

        companion object {
            private val RATE_LIMITED = setOf("rateLimitExceeded", "userRateLimitExceeded", "RATE_LIMIT_EXCEEDED")

            /** `{"error": {"message": …, "errors": [{"reason": …}], "details": [{"reason": …}]}}`, read leniently. */
            fun parse(status: Int, body: String): DriveError {
                val error = runCatching { lenientJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
                    ?: return DriveError(status, emptySet(), null)
                val reasons = listOf("errors", "details").flatMap { key ->
                    (error[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull }
                }.toSet()
                return DriveError(status, reasons, error["message"]?.jsonPrimitive?.contentOrNull)
            }
        }
    }

    private companion object {
        const val FILE_FIELDS = "id,name,modifiedTime"

        /** Drive's limit for single-request (multipart and media) uploads. */
        const val SIMPLE_UPLOAD_MAX_BYTES = 5 * 1024 * 1024

        /** Larger than any segment or attachment (attachments are capped at 20 MB). */
        const val MAX_FILE_BYTES = 64L * 1024 * 1024

        val SAFE_PATH = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*/?""")

        /** Drive's `modifiedTime` is RFC 3339 in UTC with milliseconds, so it sorts as text. */
        val NEWEST_FIRST: Comparator<DriveFile> = compareByDescending<DriveFile> { it.modifiedTime }.thenBy { it.id }

        fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
