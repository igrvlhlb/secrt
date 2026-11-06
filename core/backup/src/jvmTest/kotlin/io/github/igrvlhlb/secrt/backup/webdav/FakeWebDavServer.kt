package io.github.igrvlhlb.secrt.backup.webdav

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * A small in-process WebDAV server, strict where real ones are: `PUT` into a missing collection
 * is a 409, `MKCOL` of an existing one a 405, and every request needs Basic auth. Serves one
 * user's folder at [root] (e.g. Nextcloud's `/remote.php/dav/files/<user>/`); everything else is
 * 404, like a web server that doesn't speak WebDAV there.
 */
class FakeWebDavServer(
    private val root: String = "/dav/",
    private val username: String = "alice",
    private val password: String = "app-password",
) : AutoCloseable {
    /** Decoded paths under [root]: files, and collections ending in `/`. */
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap<String, ByteArray>()
    private val collections: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add("") }
    val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val origin = "http://127.0.0.1:${server.address.port}"
    val folderUrl = origin + root

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val rawPath = exchange.requestURI.rawPath
        requests += "$method $rawPath"
        val body = exchange.requestBody.readBytes()
        val expected = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        if (exchange.requestHeaders.getFirst("Authorization") != expected) {
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"test\"")
            return exchange.respond(401)
        }
        val decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), Charsets.UTF_8)
        if (!(decoded + if (decoded.endsWith("/")) "" else "/").startsWith(root)) return exchange.respond(404)
        val path = decoded.removePrefix(root.dropLast(1)).removePrefix("/")
        val asCollection = path.trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }
        val parent = path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        when (method) {
            "PROPFIND" -> {
                val depth = exchange.requestHeaders.getFirst("Depth") ?: "infinity"
                val entries = when {
                    asCollection in collections -> {
                        val children = if (depth == "0") emptyList() else (collections + files.keys).filter {
                            it != asCollection && it.startsWith(asCollection) && '/' !in it.removePrefix(asCollection).trimEnd('/')
                        }
                        listOf(asCollection) + children.sorted()
                    }
                    path in files -> listOf(path)
                    else -> return exchange.respond(404)
                }
                // Hrefs as Apache writes them: absolute paths, '@' and friends percent-encoded.
                val xml = buildString {
                    append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
                    entries.forEach { entry ->
                        val href = (root + entry).split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
                        val type = if (entry.isEmpty() || entry.endsWith("/")) "<D:collection/>" else ""
                        append("<D:response><D:href>$href</D:href><D:propstat><D:prop><D:resourcetype>$type</D:resourcetype></D:prop>")
                        append("<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
                    }
                    append("</D:multistatus>")
                }
                exchange.respond(207, xml.toByteArray())
            }
            "GET" -> files[path]?.let { exchange.respond(200, it) } ?: exchange.respond(404)
            "PUT" -> when {
                parent !in collections -> exchange.respond(409)
                else -> {
                    val existed = path in files
                    files[path] = body
                    exchange.respond(if (existed) 204 else 201)
                }
            }
            "MKCOL" -> when {
                asCollection in collections || path in files -> exchange.respond(405)
                parent !in collections -> exchange.respond(409)
                else -> {
                    collections += asCollection
                    exchange.respond(201)
                }
            }
            "DELETE" -> if (files.remove(path) != null) exchange.respond(204) else exchange.respond(404)
            else -> exchange.respond(405)
        }
    }

    private fun HttpExchange.respond(status: Int, body: ByteArray? = null) {
        sendResponseHeaders(status, body?.size?.toLong() ?: -1)
        body?.let { responseBody.write(it) }
    }
}
