package app.toque.core

import app.toque.core.api.ApiRouter
import app.toque.core.api.EventBus
import app.toque.core.api.HttpServer
import app.toque.core.json.obj
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketTimeoutException

/** Le serveur sur de vraies connexions locales (port choisi par le système). */
class HttpServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val backend = FakeBackend()
    private val bus = EventBus()
    private val server = HttpServer(ApiRouter(backend) { "secret" }, bus, pingIntervalS = 1)
    private var port = 0
    private val sockets = ArrayList<Socket>()

    @Before fun start() { port = server.start(0) }

    @After fun stop() {
        server.stop()
        for (s in sockets) try { s.close() } catch (_: Exception) {}
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun connect(): Socket = Socket("127.0.0.1", port).also { it.soTimeout = 5_000; sockets += it }

    private fun raw(request: String): Reply {
        val s = connect()
        s.getOutputStream().write(request.toByteArray())
        s.getOutputStream().flush()
        val all = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = s.getInputStream().read(buf)
            if (n < 0) break
            all.write(buf, 0, n)
        }
        val bytes = all.toByteArray()
        val sep = String(bytes, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        assertTrue("réponse sans fin d'en-têtes", sep > 0)
        val lines = String(bytes, 0, sep, Charsets.ISO_8859_1).split("\r\n")
        val status = lines[0].split(' ')[1].toInt()
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Reply(status, headers, bytes.copyOfRange(sep + 4, bytes.size))
    }

    private fun get(path: String, token: String? = "secret", extra: String = "", method: String = "GET") =
        raw("$method $path HTTP/1.1\r\nHost: robot\r\n" + (if (token != null) "Authorization: Bearer $token\r\n" else "") + extra + "\r\n")

    @Test
    fun infoIsPublicAndWellFormed() {
        val r = get("/api/v1/info", token = null)
        assertEquals(200, r.status)
        assertTrue(r.headers["content-type"]!!.startsWith("application/json"))
        assertEquals("close", r.headers["connection"])
        assertEquals(r.body.size, r.headers["content-length"]!!.toInt())
        assertEquals("toque-test", jsonObj(r.body)["id"])
    }

    @Test
    fun accentedTextIsSentAsUtf8() {
        backend.recipes += sampleRecipe("a", "Crème brûlée")
        val r = get("/api/v1/recipes/a")
        assertEquals("Crème brûlée", jsonObj(r.body)["title"])
        assertEquals(r.body.size, r.headers["content-length"]!!.toInt())
    }

    @Test
    fun stateNeedsTheToken() {
        assertEquals(401, get("/api/v1/state", token = null).status)
        assertEquals(401, get("/api/v1/state", token = "nope").status)
        val ok = get("/api/v1/state")
        assertEquals(200, ok.status)
        assertEquals("standby", jsonObj(ok.body)["status"])
    }

    @Test
    fun queryStringIsDecoded() {
        backend.recipes += sampleRecipe("a", "Crème brûlée")
        backend.recipes += sampleRecipe("b", "Tarte")
        val r = get("/api/v1/recipes?q=cr%C3%A8me&limit=5")
        val list = jsonObj(r.body)["recipes"] as List<*>
        assertEquals(1, list.size)
        assertEquals("a", list[0].obj()["id"])
    }

    @Test
    fun writesAreRefused() {
        for (m in listOf("POST", "PUT", "DELETE")) {
            val r = get("/api/v1/state", method = m, extra = "Content-Length: 2\r\n")
            assertEquals(m, 405, r.status)
            assertEquals("GET", r.headers["allow"])
        }
    }

    @Test
    fun garbageIsBadRequest() {
        assertEquals(400, raw("pas du http\r\n\r\n").status)
        assertEquals(400, raw("GET /api/v1/info\r\n\r\n").status)
        assertEquals(400, raw("GET /api/v1/info HTTP/1.1\r\nsans deux points\r\n\r\n").status)
    }

    @Test
    fun hugeHeadersAreRefused() {
        val r = raw("GET /api/v1/info HTTP/1.1\r\nX: " + "a".repeat(20_000) + "\r\n\r\n")
        assertEquals(400, r.status)
    }

    @Test
    fun imageIsStreamedByteForByte() {
        backend.recipes += sampleRecipe("a", "Alpha")
        val data = ByteArray(300_000) { (it * 31).toByte() }
        backend.images["a"] = tmp.newFile("a.jpg").apply { writeBytes(data) }
        val r = get("/api/v1/recipes/a/image")
        assertEquals(200, r.status)
        assertEquals("image/jpeg", r.headers["content-type"])
        assertEquals(data.size, r.headers["content-length"]!!.toInt())
        assertArrayEquals(data, r.body)
        assertNotNull(r.headers["etag"])
        val again = get("/api/v1/recipes/a/image", extra = "If-None-Match: ${r.headers["etag"]}\r\n")
        assertEquals(304, again.status)
        assertEquals(0, again.body.size)
    }

    @Test
    fun imageIsRefusedWhileAProgramRuns() {
        backend.recipes += sampleRecipe("a", "Alpha")
        backend.images["a"] = tmp.newFile("a.jpg").apply { writeBytes(ByteArray(10)) }
        backend.snap = backend.snap.copy(jobActive = true, jobState = "RUNNING")
        val r = get("/api/v1/recipes/a/image")
        assertEquals(503, r.status)
        assertEquals("5", r.headers["retry-after"])
    }

    // ---- SSE

    private class Frame(val id: String?, val event: String, val data: String)

    private fun openStream(extra: String = ""): Pair<Socket, BufferedReader> {
        val s = connect()
        s.getOutputStream().write(("GET /api/v1/events HTTP/1.1\r\nAuthorization: Bearer secret\r\n$extra\r\n").toByteArray())
        s.getOutputStream().flush()
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val status = reader.readLine()
        assertEquals("HTTP/1.1 200 OK", status)
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        assertEquals("text/event-stream", headers["content-type"])
        return s to reader
    }

    /** Prochaine trame (les commentaires `:` sont ignorés). */
    private fun BufferedReader.frame(): Frame {
        var id: String? = null
        var event = ""
        var data = ""
        while (true) {
            val line = readLine() ?: error("flux fermé")
            when {
                line.startsWith(":") -> continue
                line.isEmpty() -> if (event.isNotEmpty()) return Frame(id, event, data)
                line.startsWith("id: ") -> id = line.substring(4)
                line.startsWith("event: ") -> event = line.substring(7)
                line.startsWith("data: ") -> data = line.substring(6)
            }
        }
    }

    @Test
    fun streamStartsWithTheCurrentStateThenSendsLiveEvents() {
        val (_, r) = openStream()
        val state = r.frame()
        assertEquals("state", state.event)
        assertEquals(null, state.id)
        assertEquals("standby", jsonObj(state.data.toByteArray())["status"])
        assertEquals("session", r.frame().event)

        val e = bus.publish("step", mapOf("index" to 4))
        val live = r.frame()
        assertEquals("step", live.event)
        assertEquals(e.id.toString(), live.id)
        assertEquals(4, (jsonObj(live.data.toByteArray())["index"] as Number).toInt())
    }

    @Test
    fun lastEventIdReplaysMissedEvents() {
        val a = bus.publish("one", mapOf("n" to 1))
        bus.publish("two", mapOf("n" to 2))
        bus.publish("three", mapOf("n" to 3))
        val (_, r) = openStream("Last-Event-ID: ${a.id}\r\n")
        assertEquals("state", r.frame().event)
        assertEquals("session", r.frame().event)
        assertEquals("two", r.frame().event)
        assertEquals("three", r.frame().event)
        bus.publish("four", null)
        assertEquals("four", r.frame().event)
    }

    @Test
    fun aNewStreamDoesNotReplayOldEvents() {
        bus.publish("old", null)
        val (_, r) = openStream()
        r.frame(); r.frame()
        bus.publish("fresh", null)
        assertEquals("fresh", r.frame().event)
    }

    @Test
    fun streamNeedsTheToken() {
        assertEquals(401, get("/api/v1/events", token = null).status)
    }

    @Test
    fun stoppingTheServerClosesStreams() {
        val (s, r) = openStream()
        r.frame(); r.frame()
        server.stop()
        s.soTimeout = 3_000
        val end = try { r.readLine() } catch (_: SocketTimeoutException) { "timeout" } catch (_: java.io.IOException) { null }
        assertTrue("le flux doit se fermer, reçu : $end", end == null)
    }

    @Test
    fun connectionsAreLimited() {
        val streams = (1..HttpServer.MAX_CONNECTIONS).map { openStream() }
        streams.forEach { it.second.frame() }
        val r = get("/api/v1/info", token = null)
        assertEquals(503, r.status)
        assertEquals("2", r.headers["retry-after"])
        // Une place libérée : le serveur répond de nouveau.
        streams[0].first.close()
        var ok = false
        for (i in 1..100) {   // jusqu'à 10 s : la fermeture se voit à la 2e écriture (ping toutes les secondes)
            if (get("/api/v1/info", token = null).status == 200) { ok = true; break }
            Thread.sleep(100)
        }
        assertTrue("le serveur doit reprendre après la fermeture d'un flux", ok)
    }

    @Test
    fun portCanBeReusedAfterStop() {
        server.stop()
        val again = HttpServer(ApiRouter(backend) { "secret" }, bus)
        val p = again.start(0)
        try {
            Socket("127.0.0.1", p).use { assertTrue(it.isConnected) }
        } finally {
            again.stop()
        }
    }
}
