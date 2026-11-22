package app.toque.core

import app.toque.core.api.ApiResponse
import app.toque.core.api.ApiRouter
import app.toque.core.api.HttpRequest
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.machine.LinkState
import app.toque.core.recipes.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiRouterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val backend = FakeBackend()
    private var token = "secret"
    private val router = ApiRouter(backend) { token }

    private fun get(path: String, query: Map<String, String> = emptyMap(), auth: String? = "Bearer secret",
                    extra: Map<String, String> = emptyMap()): ApiResponse {
        val headers = HashMap(extra)
        if (auth != null) headers["authorization"] = auth
        return router.handle(HttpRequest("GET", path, query, headers))
    }

    private fun ApiResponse.obj() = jsonObj(body)
    private fun add(vararg r: Recipe) { backend.recipes.addAll(r) }

    @Test
    fun onlyGetIsAccepted() {
        for (m in listOf("POST", "PUT", "DELETE", "PATCH", "HEAD")) {
            val r = router.handle(HttpRequest(m, "/api/v1/state", headers = mapOf("authorization" to "Bearer secret")))
            assertEquals(m, 405, r.status)
            assertEquals("GET", r.headers["Allow"])
        }
    }

    @Test
    fun infoIsPublic() {
        val r = get("/api/v1/info", auth = null)
        assertEquals(200, r.status)
        assertEquals(1, (r.obj()["api"] as Number).toInt())
        assertEquals("Cuisine", r.obj()["name"])
    }

    @Test
    fun diagnosticsShowTheRawBaseValuesAndNeedTheToken() {
        assertEquals(401, get("/api/v1/diagnostics", auth = null).status)
        // Brut : ni la correction du poids ni celle de la chauffe de /state.
        backend.snap = backend.snap.copy(motorOn = true, heaterOn = true, targetTempC = 51, weightG = 0, rpm = 0,
            wireOperation = "STANDARD", wireSpeed = 4, wireTempLevel = 0, rxHex = "AA55")
        val r = get("/api/v1/diagnostics")
        assertEquals(200, r.status)
        val base = r.obj()["base"].obj()
        assertEquals(51, (base["targetTempC"] as Number).toInt())
        assertEquals(true, base["heaterOn"])
        assertEquals(0, (base["weightG"] as Number).toInt())
        assertEquals("AA55", base["rxHex"])
        val sent = r.obj()["sent"].obj()
        assertEquals("STANDARD", sent["operation"])
        assertEquals(4, (sent["speed"] as Number).toInt())
        assertEquals(false, r.obj()["job"].obj()["ongoing"])
    }

    @Test
    fun everythingElseNeedsTheToken() {
        for (p in listOf("/api/v1/state","/api/v1/session", "/api/v1/recipes", "/api/v1/recipes/x", "/api/v1/events", "/api/v1/nothing")) {
            assertEquals(p, 401, get(p, auth = null).status)
            assertEquals(p, 401, get(p, auth = "Bearer wrong").status)
            assertEquals(p, 401, get(p, auth = "Basic c2VjcmV0").status)
            assertEquals("Bearer", get(p, auth = null).headers["WWW-Authenticate"])
        }
    }

    @Test
    fun noConfiguredTokenMeansNoAccess() {
        token = ""
        assertEquals(401, get("/api/v1/state", auth = "Bearer ").status)
        assertEquals(401, get("/api/v1/state", auth = "Bearer").status)
        assertEquals(200, get("/api/v1/info", auth = null).status)
    }

    @Test
    fun tokenSchemeIsCaseInsensitive() {
        assertEquals(200, get("/api/v1/state", auth = "bearer secret").status)
    }

    @Test
    fun unknownPathsAre404() {
        assertEquals(404, get("/").status)
        assertEquals(404, get("/other").status)
        assertEquals(404, get("/api/v1/nothing").status)
        assertEquals(404, get("/api/v2/state").status)
        assertEquals("not_found", get("/api/v1/nothing").obj()["error"])
    }

    @Test
    fun trailingSlashIsIgnored() {
        assertEquals(200, get("/api/v1/state/").status)
    }

    @Test
    fun stateAndSessionAreServed() {
        assertEquals("standby", get("/api/v1/state").obj()["status"])
        assertEquals(false, get("/api/v1/session").obj()["active"])
        backend.sess = app.toque.core.recipes.CookSession("s", sampleRecipe(), 1)
        assertEquals("recipe", get("/api/v1/state").obj()["status"])
        assertEquals("s", get("/api/v1/session").obj()["id"])
    }

    @Test
    fun stateExposesTheSnapshotAge() {
        backend.snapAge = 777
        assertEquals(777, (get("/api/v1/state").obj()["snapshotAgeMs"] as Number).toInt())
    }

    @Test
    fun eventsAskForAStreamAndReadLastEventId() {
        val r = get("/api/v1/events", extra = mapOf("last-event-id" to " 12 "))
        assertTrue(r.events)
        assertEquals(12L, r.lastEventId)
        assertNull(get("/api/v1/events").lastEventId)
        assertNull(get("/api/v1/events", extra = mapOf("last-event-id" to "abc")).lastEventId)
    }

    @Test
    fun initialEventsAreStateThenSession() {
        val e = router.initialEvents()
        assertEquals(listOf("state", "session"), e.map { it.first })
        assertEquals("standby", jsonObj(e[0].second.toByteArray())["status"])
    }

    @Test
    fun recipesAreListedAndPaged() {
        add(sampleRecipe("a", "Alpha"), sampleRecipe("b", "Bravo"), sampleRecipe("c", "Charlie"))
        val page = get("/api/v1/recipes", mapOf("limit" to "2", "offset" to "1")).obj()
        assertEquals(3, (page["total"] as Number).toInt())
        assertEquals(2, (page["limit"] as Number).toInt())
        assertEquals(1, (page["offset"] as Number).toInt())
        assertEquals(listOf("b", "c"), page["recipes"].list().map { it.obj()["id"] })
    }

    @Test
    fun limitIsClampedAndGarbageFallsBack() {
        assertEquals(100, (get("/api/v1/recipes", mapOf("limit" to "5000")).obj()["limit"] as Number).toInt())
        assertEquals(1, (get("/api/v1/recipes", mapOf("limit" to "0")).obj()["limit"] as Number).toInt())
        assertEquals(50, (get("/api/v1/recipes", mapOf("limit" to "abc")).obj()["limit"] as Number).toInt())
        assertEquals(0, (get("/api/v1/recipes", mapOf("offset" to "-4")).obj()["offset"] as Number).toInt())
    }

    @Test
    fun recipesAreFiltered() {
        add(sampleRecipe("a", "Alpha").copy(favorite = true), sampleRecipe("b", "Bravo").copy(category = "Dessert"))
        fun ids(q: Map<String, String>) = get("/api/v1/recipes", q).obj()["recipes"].list().map { it.obj()["id"] }
        assertEquals(listOf("b"), ids(mapOf("category" to "Dessert")))
        assertEquals(listOf("a"), ids(mapOf("favorite" to "1")))
        assertEquals(listOf("a"), ids(mapOf("favorite" to "true")))
        assertEquals(listOf("b"), ids(mapOf("q" to "brav")))
        assertEquals(listOf("a", "b"), ids(mapOf("favorite" to "0")))
    }

    @Test
    fun recipeDetailAndUnknownRecipe() {
        add(sampleRecipe("a", "Alpha"))
        val d = get("/api/v1/recipes/a").obj()
        assertEquals("Alpha", d["title"])
        assertEquals(4, (d["machineSteps"] as Number).toInt())
        assertEquals(404, get("/api/v1/recipes/zzz").status)
        assertEquals("unknown_recipe", get("/api/v1/recipes/zzz").obj()["error"])
        assertEquals(404, get("/api/v1/recipes/a/other").status)
        assertEquals(404, get("/api/v1/recipes/a/image/more").status)
    }

    @Test
    fun recipeIdsAreUrlEncoded() {
        val id = "crème brûlée/été+1"
        add(sampleRecipe(id, "Crème"))
        val enc = ApiRouter.encodeSegment(id)
        assertFalse(enc.contains('/'))
        assertFalse(enc.contains(' '))
        assertEquals(id, ApiRouter.decodeSegment(enc))
        assertEquals("Crème", get("/api/v1/recipes/$enc").obj()["title"])
        val img = tmp.newFile("x.jpg").apply { writeBytes(ByteArray(10)) }
        backend.images[id] = img
        assertEquals("/api/v1/recipes/$enc/image", get("/api/v1/recipes/$enc").obj()["imageUrl"])
    }

    @Test
    fun badEscapeInIdIsNotFound() {
        assertEquals(404, get("/api/v1/recipes/%zz").status)
    }

    @Test
    fun imageUrlOnlyWhenCached() {
        add(sampleRecipe("a", "Alpha"), sampleRecipe("b", "Bravo"))
        backend.images["a"] = tmp.newFile("a.jpg").apply { writeBytes(ByteArray(10)) }
        val list = get("/api/v1/recipes").obj()["recipes"].list().map { it.obj() }
        assertNotNull(list[0]["imageUrl"])
        assertNull(list[1]["imageUrl"])
    }

    @Test
    fun imageIsServedWithEtag() {
        add(sampleRecipe("a", "Alpha"))
        val f = tmp.newFile("a.png").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        backend.images["a"] = f
        val r = get("/api/v1/recipes/a/image")
        assertEquals(200, r.status)
        assertEquals("image/png", r.contentType)
        assertEquals(f, r.file)
        val etag = r.headers["ETag"]!!
        assertEquals("max-age=86400", r.headers["Cache-Control"])
        val again = get("/api/v1/recipes/a/image", extra = mapOf("if-none-match" to etag))
        assertEquals(304, again.status)
        assertNull(again.file)
        assertEquals(200, get("/api/v1/recipes/a/image", extra = mapOf("if-none-match" to "\"autre\"")).status)
    }

    @Test
    fun missingImageIs404() {
        add(sampleRecipe("a", "Alpha"))
        val r = get("/api/v1/recipes/a/image")
        assertEquals(404, r.status)
        assertEquals("no_image", r.obj()["error"])
    }

    @Test
    fun noImageIsServedWhileAProgramRuns() {
        add(sampleRecipe("a", "Alpha"))
        backend.images["a"] = tmp.newFile("a.jpg").apply { writeBytes(ByteArray(10)) }
        backend.snap = backend.snap.copy(jobActive = true, jobState = "RUNNING")
        val r = get("/api/v1/recipes/a/image")
        assertEquals(503, r.status)
        assertEquals("5", r.headers["Retry-After"])
        // Les autres routes restent disponibles pendant le programme.
        assertEquals(200, get("/api/v1/state").status)
        assertEquals(200, get("/api/v1/recipes/a").status)
        backend.snap = backend.snap.copy(jobActive = false, jobState = "", link = LinkState.READY)
        assertEquals(200, get("/api/v1/recipes/a/image").status)
    }
}
