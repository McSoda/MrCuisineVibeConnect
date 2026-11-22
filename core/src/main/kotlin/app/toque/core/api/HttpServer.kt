package app.toque.core.api

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serveur HTTP/1.1 minimal pour l'API : un fil d'écoute, [MAX_CONNECTIONS] connexions simultanées au plus,
 * une réponse par connexion (`Connection: close`) sauf le flux SSE. Lecture seule : le corps des requêtes
 * n'est jamais lu. Aucune dépendance (le robot est un Android 6 modeste).
 */
class HttpServer(
    private val router: ApiRouter,
    private val bus: EventBus,
    private val log: (String) -> Unit = {},
    /**
     * Intervalle des commentaires `: ping` du flux SSE. C'est aussi le délai après lequel on s'aperçoit qu'un client a
     * disparu (une fermeture ne se voit qu'à l'écriture suivante) et où sa place est rendue.
     */
    private val pingIntervalS: Long = PING_INTERVAL_S,
    /** Temps total accordé à un client pour envoyer sa requête (un client qui goutte à goutte ne retient pas un fil). */
    private val requestDeadlineS: Long = REQUEST_DEADLINE_S,
    /** Appelé (une fois) si l'écoute meurt toute seule : le serveur s'est arrêté, à l'appelant de décider de le relancer. */
    private val onDied: (String) -> Unit = {},
    private val socketFactory: () -> ServerSocket = { ServerSocket() },
) {
    private val threadNumber = AtomicInteger()
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "toque-api-${threadNumber.incrementAndGet()}").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "toque-api-watchdog").apply { isDaemon = true }
    }
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val imageGate = Semaphore(1)
    private class Conn(val remote: String, val since: Long = System.currentTimeMillis()) {
        @Volatile var path = ""
        @Volatile var stream = false
    }
    private val open = ConcurrentHashMap<Socket, Conn>()

    private val refusedCount = AtomicInteger()
    /** Les connexions qui occupaient toutes les places au dernier refus (la liste n'est plus lisible une fois saturé). */
    @Volatile private var lastRefusal: Pair<Long, List<ConnectionInfo>>? = null

    private fun snapshotConnections(): List<ConnectionInfo> {
        val now = System.currentTimeMillis()
        return open.values.map { ConnectionInfo(it.remote, now - it.since, it.path, it.stream) }.sortedByDescending { it.ageMs }
    }

    init {
        router.maxConnections = MAX_CONNECTIONS
        router.connections = ::snapshotConnections
        router.refusals = {
            val last = lastRefusal
            RefusalInfo(refusedCount.get(), last?.let { System.currentTimeMillis() - it.first }, last?.second.orEmpty())
        }
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var stopped = false

    /** Démarre l'écoute sur [port] (0 : au choix) et renvoie le port réellement ouvert. */
    @Synchronized
    fun start(port: Int): Int {
        check(!stopped) { "arrêté : un serveur arrêté ne redémarre pas, en créer un autre" }
        check(server == null) { "déjà démarré" }
        val s = socketFactory()
        try {
            s.reuseAddress = true
            s.bind(java.net.InetSocketAddress(port))
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw e
        }
        server = s
        running = true
        Thread({ acceptLoop(s) }, "toque-api-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    /** Arrête l'écoute, ferme toutes les connexions (flux SSE compris) et libère les fils. Définitif. */
    @Synchronized
    fun stop() {
        running = false
        stopped = true
        try { server?.close() } catch (_: IOException) {}
        server = null
        for (c in open.keys.toList()) try { c.close() } catch (_: IOException) {}
        open.clear()
        workers.shutdownNow()
        watchdog.shutdownNow()
    }

    private fun died(reason: String) {
        log("écoute interrompue : $reason")
        stop()
        onDied(reason)
    }

    private fun acceptLoop(s: ServerSocket) {
        var failures = 0
        while (running) {
            val c = try {
                s.accept().also { failures = 0 }
            } catch (e: Exception) {
                if (!running) break   // arrêt demandé : la fermeture de la prise est normale
                // Panne passagère possible (trop de fichiers ouverts…) : quelques essais avant de renoncer.
                if (++failures >= ACCEPT_MAX_FAILURES) { died(e.toString()); break }
                log("accept en échec ($failures) : $e")
                try { Thread.sleep(ACCEPT_RETRY_MS) } catch (_: InterruptedException) { break }
                continue
            }
            if (!slots.tryAcquire()) {
                refusedCount.incrementAndGet()
                val last = lastRefusal
                if (last == null || System.currentTimeMillis() - last.first >= 2_000) {
                    lastRefusal = System.currentTimeMillis() to snapshotConnections()
                }
                refuse(c)
                continue
            }
            val conn = Conn(c.remoteSocketAddress?.toString()?.removePrefix("/") ?: "?")
            open[c] = conn
            try {
                workers.execute {
                    try { serve(c, conn) } finally {
                        open.remove(c)
                        try { c.close() } catch (_: IOException) {}
                        slots.release()
                    }
                }
            } catch (_: Exception) {
                open.remove(c)
                try { c.close() } catch (_: IOException) {}
                slots.release()
            }
        }
    }

    private fun refuse(c: Socket) {
        try {
            c.soTimeout = 1_000
            writeResponse(BufferedOutputStream(c.getOutputStream()),
                ApiResponse.error(503, "too_many_connections", "Trop de connexions", mapOf("Retry-After" to "2")))
            lingerClose(c, REFUSE_LINGER_MS)
        } catch (_: IOException) {} finally {
            try { c.close() } catch (_: IOException) {}
        }
    }

    /**
     * Fermeture en douceur : on arrête d'écrire, puis on vide un instant ce que le client envoie encore. Sans cela,
     * fermer avec des octets non lus provoque une réinitialisation TCP et le client perd la réponse (cas d'une
     * requête refusée avant d'être lue en entier, ou d'une requête avec un corps que l'on ne lit jamais).
     */
    private fun lingerClose(c: Socket, waitMs: Int) {
        try {
            c.shutdownOutput()
            c.soTimeout = waitMs
            val buf = ByteArray(4096)
            var total = 0
            val input = c.getInputStream()
            while (total < LINGER_MAX_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
        } catch (_: IOException) {}
    }

    private fun serve(c: Socket, conn: Conn) {
        try {
            c.soTimeout = READ_TIMEOUT_MS
            c.keepAlive = true
            // Délai total pour la requête : sans lui, un client qui envoie un octet toutes les 9 s retiendrait le fil.
            val readTimer = deadline(c, requestDeadlineS)
            val req = try {
                readRequest(BufferedInputStream(c.getInputStream(), 2048))
            } catch (_: IOException) {
                return
            } finally {
                readTimer?.cancel(false)
            }
            val out = BufferedOutputStream(c.getOutputStream(), 8192)
            if (req == null) {
                writeResponse(out, ApiResponse.error(400, "bad_request", "Requête illisible"))
                lingerClose(c, LINGER_MS)
                return
            }
            conn.path = req.path
            val resp = try {
                router.handle(req)
            } catch (e: Exception) {
                log("erreur interne : $e")
                ApiResponse.error(500, "internal", "Erreur interne")
            }
            if (resp.events) { conn.stream = true; streamEvents(c, out, resp.lastEventId); return }
            // Un client bloqué ne doit pas retenir un fil (ni la barrière des images) indéfiniment.
            val timer = deadline(c, RESPONSE_DEADLINE_S)
            try {
                if (resp.file != null) {
                    if (!imageGate.tryAcquire()) {
                        writeResponse(out, ApiResponse.error(503, "busy", "Une image est déjà en cours d'envoi", mapOf("Retry-After" to "1")))
                    } else {
                        try { writeResponse(out, resp) } finally { imageGate.release() }
                    }
                } else writeResponse(out, resp)
                lingerClose(c, LINGER_MS)
            } finally {
                timer?.cancel(false)
            }
        } catch (_: SocketException) {
        } catch (e: IOException) {
            log("connexion interrompue : $e")
        }
    }

    /** Ferme [c] dans [seconds] s si personne n'annule avant ; null si le serveur est déjà arrêté. */
    private fun deadline(c: Socket, seconds: Long): ScheduledFuture<*>? = try {
        watchdog.schedule({ try { c.close() } catch (_: IOException) {} }, seconds, TimeUnit.SECONDS)
    } catch (_: RejectedExecutionException) {
        null
    }

    // ---- Lecture de la requête

    /** Ligne de requête et en-têtes ; null si elle est mal formée ou trop grande. */
    private fun readRequest(input: InputStream): HttpRequest? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val headers = HashMap<String, String>()
        var total = first.length
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEADER_BYTES || headers.size >= 64) return null
            val i = line.indexOf(':')
            if (i <= 0) return null
            headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        return HttpRequest(parts[0].uppercase(), path, if (q < 0) emptyMap() else parseQuery(target.substring(q + 1)), headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > MAX_HEADER_BYTES) return null
        }
        return sb.toString()
    }

    private fun parseQuery(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in s.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            try {
                out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (_: IllegalArgumentException) {}
        }
        return out
    }

    // ---- Écriture de la réponse

    private fun writeResponse(out: OutputStream, r: ApiResponse) {
        // Le fichier est ouvert d'abord et sa taille lue sur le flux ouvert : si une synchro le réécrit pendant
        // l'envoi, `Content-Length` reste celui des octets réellement envoyés.
        val stream = try {
            r.file?.inputStream()
        } catch (_: FileNotFoundException) {
            writeResponse(out, ApiResponse.error(404, "no_image", "Image absente du robot"))
            return
        }
        try {
            writeHead(out, r, stream)
        } finally {
            stream?.close()
        }
    }

    private fun writeHead(out: OutputStream, r: ApiResponse, file: FileInputStream?) {
        val length = file?.channel?.size() ?: r.body.size.toLong()
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        head.append("Server: Toque\r\n")
        if (r.status != 304) head.append("Content-Type: ").append(r.contentType).append("\r\n")
        if (r.status != 304) head.append("Content-Length: ").append(length).append("\r\n")
        if ("Cache-Control" !in r.headers) head.append("Cache-Control: no-store\r\n")
        for ((k, v) in r.headers) head.append(k).append(": ").append(v).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (file != null) {
            val buf = ByteArray(CHUNK)
            var left = length
            while (left > 0) {
                val n = file.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("fichier raccourci pendant l'envoi")   // la connexion se ferme, rien d'invalide n'est annoncé
                out.write(buf, 0, n)
                left -= n
            }
        } else if (r.status != 304) out.write(r.body)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    // ---- Flux SSE

    private fun streamEvents(c: Socket, out: OutputStream, lastEventId: Long?) {
        c.soTimeout = 0
        val queue = LinkedBlockingQueue<ApiEvent>(QUEUE_CAPACITY)
        val unsubscribe = bus.subscribe { e -> if (!queue.offer(e)) try { c.close() } catch (_: IOException) {} }
        try {
            out.write(("HTTP/1.1 200 OK\r\nServer: Toque\r\nContent-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\nX-Accel-Buffering: no\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            out.write(": connected\n\n".toByteArray())
            for ((name, data) in router.initialEvents()) out.write(frame(null, name, data))
            out.flush()
            var lastSent = lastEventId ?: bus.lastId
            if (lastEventId != null) for (e in bus.since(lastEventId)) {
                out.write(frame(e.id, e.name, e.data))
                lastSent = e.id
            }
            out.flush()
            while (running && !c.isClosed) {
                val e = queue.poll(pingIntervalS, TimeUnit.SECONDS)
                if (e == null) out.write(": ping\n\n".toByteArray())
                else if (e.id > lastSent) {
                    out.write(frame(e.id, e.name, e.data))
                    lastSent = e.id
                }
                out.flush()
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            unsubscribe()
        }
    }

    private fun frame(id: Long?, name: String, data: String): ByteArray {
        val sb = StringBuilder()
        if (id != null) sb.append("id: ").append(id).append('\n')
        sb.append("event: ").append(name).append('\n')
        sb.append("data: ").append(data).append("\n\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val MAX_CONNECTIONS = 8
        const val READ_TIMEOUT_MS = 10_000
        const val REQUEST_DEADLINE_S = 10L
        const val RESPONSE_DEADLINE_S = 30L
        const val ACCEPT_MAX_FAILURES = 5
        const val ACCEPT_RETRY_MS = 200L
        const val LINGER_MS = 500
        const val REFUSE_LINGER_MS = 100
        const val LINGER_MAX_BYTES = 64 * 1024
        const val PING_INTERVAL_S = 15L
        const val MAX_HEADER_BYTES = 16 * 1024
        const val CHUNK = 16 * 1024
        const val QUEUE_CAPACITY = 200
    }
}
