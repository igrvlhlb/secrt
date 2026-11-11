package io.github.igrvlhlb.secrt.backup.drive

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small in-process stand-in for the parts of Google's APIs secrt uses: Drive v3 files in
 * `appDataFolder` (list with paging, download, multipart / media / resumable uploads, delete) and
 * the userinfo endpoint. Every request needs a bearer token from [validTokens].
 */
class FakeGoogleDrive(private val pageLimit: Int = 1000) : AutoCloseable {

    class File(val id: String, val name: String, var bytes: ByteArray, var modifiedTime: String)

    val files: MutableMap<String, File> = ConcurrentHashMap()
    val validTokens: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add("token-1") }
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Statuses (and JSON bodies) to answer the next requests with, before handling them. */
    val failNext = ConcurrentLinkedQueue<Pair<Int, String>>()

    private val ids = AtomicInteger()
    private val sessionIds = AtomicInteger()
    private val clock = AtomicInteger()
    private val sessions = ConcurrentHashMap<String, Pair<String?, String?>>() // session → (file id to update, name to create)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                try {
                    handle(it)
                } catch (e: TokenRefused) {
                    it.respond(400, """{"error":"invalid_grant","error_description":"Bad Request"}""")
                }
            }
        }
        start()
    }

    val origin = "http://127.0.0.1:${server.address.port}"
    val endpoints = GoogleEndpoints(accounts = origin, oauth = origin, api = origin)

    /** Files by name, as secrt sees them. */
    val byName: Map<String, ByteArray> get() = files.values.associate { it.name to it.bytes }

    /** Adds a file directly, as another device would have. */
    fun addFile(name: String, bytes: ByteArray): File = newFile(name, bytes).also { files[it.id] = it }

    override fun close() = server.stop(0)

    private fun newFile(name: String, bytes: ByteArray) =
        File("id${ids.incrementAndGet()}", name, bytes, now())

    private fun now() = "2026-11-09T10:00:00.%03dZ".format(clock.incrementAndGet())

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.rawPath
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }
        requests += "$method $path" + (query["uploadType"]?.let { " $it" } ?: "")
        val body = exchange.requestBody.readBytes()
        if (method == "POST" && path == "/token") return exchange.respond(200, tokenResponse(body.decodeToString()))
        val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        if (token !in validTokens) return exchange.respond(401, error(401, "Invalid Credentials", "authError"))
        failNext.poll()?.let { (status, json) -> return exchange.respond(status, json) }

        when {
            path == "/oauth2/v3/userinfo" -> exchange.respond(200, """{"sub":"1","email":"alice@gmail.com"}""")
            method == "GET" && path == "/drive/v3/files" -> {
                check(query["spaces"] == "appDataFolder")
                val all = files.values.sortedBy { it.id.removePrefix("id").toInt() }
                val start = query["pageToken"]?.toInt() ?: 0
                val size = minOf(query["pageSize"]?.toInt() ?: 100, pageLimit)
                val page = all.drop(start).take(size)
                val next = if (start + size < all.size) ",\"nextPageToken\":\"${start + size}\"" else ""
                exchange.respond(200, """{"files":${JsonArray(page.map(::resource))}$next}""")
            }
            method == "GET" && path.startsWith("/drive/v3/files/") -> {
                check(query["alt"] == "media")
                val file = files[path.removePrefix("/drive/v3/files/")] ?: return exchange.respond(404, error(404, "File not found", "notFound"))
                exchange.respond(200, file.bytes)
            }
            method == "DELETE" && path.startsWith("/drive/v3/files/") ->
                if (files.remove(path.removePrefix("/drive/v3/files/")) == null) exchange.respond(404, error(404, "File not found", "notFound")) else exchange.respond(204)
            path == "/upload/drive/v3/files" && method == "POST" -> when (query["uploadType"]) {
                "multipart" -> {
                    val (metadata, content) = multipart(exchange.requestHeaders.getFirst("Content-Type"), body)
                    check(metadata["parents"].toString() == """["appDataFolder"]""")
                    val file = addFile(metadata["name"]!!.jsonPrimitive.content, content)
                    exchange.respond(200, resource(file).toString())
                }
                "resumable" -> startSession(exchange, fileId = null, name = Json.parseToJsonElement(body.decodeToString()).jsonObject["name"]!!.jsonPrimitive.content)
                else -> exchange.respond(400)
            }
            path.startsWith("/upload/drive/v3/files/") && method == "PATCH" -> {
                val id = path.removePrefix("/upload/drive/v3/files/")
                val file = files[id] ?: return exchange.respond(404, error(404, "File not found", "notFound"))
                when (query["uploadType"]) {
                    "media" -> {
                        file.bytes = body
                        file.modifiedTime = now()
                        exchange.respond(200, resource(file).toString())
                    }
                    "resumable" -> startSession(exchange, fileId = id, name = null)
                    else -> exchange.respond(400)
                }
            }
            path.startsWith("/upload/session/") && method == "PUT" -> {
                val (fileId, name) = sessions.remove(path.removePrefix("/upload/session/")) ?: return exchange.respond(404)
                val file = if (fileId != null) {
                    files.getValue(fileId).apply {
                        bytes = body
                        modifiedTime = now()
                    }
                } else {
                    addFile(name!!, body)
                }
                exchange.respond(200, resource(file).toString())
            }
            else -> exchange.respond(404)
        }
    }

    /** The OAuth token endpoint: `good-code` (with a PKCE verifier) and `refresh-1` are accepted. */
    private fun tokenResponse(form: String): String {
        val fields = form.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        requests += "token ${fields["grant_type"]}"
        check(fields["client_id"] == "client" && fields["client_secret"] == "not-secret")
        return when {
            fields["grant_type"] == "authorization_code" && fields["code"] == "good-code" && fields["code_verifier"] == "verifier" ->
                """{"access_token":"token-1","expires_in":3599,"refresh_token":"refresh-1","scope":"x","token_type":"Bearer"}"""
            fields["grant_type"] == "refresh_token" && fields["refresh_token"] == "refresh-1" ->
                """{"access_token":"token-1","expires_in":3599,"token_type":"Bearer"}"""
            else -> throw TokenRefused()
        }
    }

    private class TokenRefused : Exception()

    private fun startSession(exchange: HttpExchange, fileId: String?, name: String?) {
        val session = "s${sessionIds.incrementAndGet()}"
        sessions[session] = fileId to name
        exchange.responseHeaders.add("Location", "$origin/upload/session/$session")
        exchange.respond(200)
    }

    private fun resource(file: File) =
        JsonObject(mapOf("id" to JsonPrimitive(file.id), "name" to JsonPrimitive(file.name), "modifiedTime" to JsonPrimitive(file.modifiedTime)))

    /** Splits a `multipart/related` body into its JSON metadata and its content. */
    private fun multipart(contentType: String, body: ByteArray): Pair<JsonObject, ByteArray> {
        val boundary = contentType.substringAfter("boundary=")
        val text = String(body, Charsets.ISO_8859_1)
        val parts = text.split("--$boundary").drop(1).dropLast(1).map { it.removePrefix("\r\n").removeSuffix("\r\n") }
        val (metadata, content) = parts.map { it.substringAfter("\r\n\r\n") }
        return Json.parseToJsonElement(metadata).jsonObject to content.toByteArray(Charsets.ISO_8859_1)
    }

    private fun HttpExchange.respond(status: Int, body: String) = respond(status, body.toByteArray())

    private fun HttpExchange.respond(status: Int, body: ByteArray? = null) {
        sendResponseHeaders(status, body?.size?.toLong()?.takeIf { it > 0 } ?: -1)
        body?.takeIf { it.isNotEmpty() }?.let { responseBody.write(it) }
    }

    companion object {
        fun error(status: Int, message: String, reason: String) =
            """{"error":{"code":$status,"message":"$message","errors":[{"domain":"global","reason":"$reason","message":"$message"}]}}"""
    }
}

/** Hands out the tokens of a [FakeGoogleDrive], as the platforms' sign-in would. */
class FakeSignIn(var tokens: ArrayDeque<String> = ArrayDeque(listOf("token-1"))) : GoogleSignIn {
    val staleTokens = mutableListOf<String?>()

    override suspend fun signIn() = BackupTargetConfig.GoogleDrive("alice@gmail.com")

    override suspend fun accessToken(account: BackupTargetConfig.GoogleDrive, stale: String?): String {
        staleTokens += stale
        return if (stale != null && tokens.size > 1) tokens.removeFirst().let { tokens.first() } else tokens.first()
    }
}
