package app.toque.core.api

import app.toque.core.json.Json
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.CookSession
import app.toque.core.recipes.Recipe
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** Page de résultats de `GET /recipes`. */
class RecipePage(val total: Int, val recipes: List<Recipe>)

/**
 * Ce que l'API lit de l'application. Implémenté dans `app` (recettes, préférences, état de la machine) et
 * simulé dans les tests. Appelé depuis les fils du serveur : les implémentations doivent le supporter.
 */
interface ApiBackend {
    val device: DeviceInfo
    val snapshot: Snapshot
    /** Âge du dernier instantané reçu de la machine (ms), -1 si aucun : mesure la fraîcheur de `snapshot`. */
    val snapshotAgeMs: Long
    val screenOn: Boolean
    val session: CookSession?
    val recipeCount: Int
    /** Change à chaque modification du catalogue. */
    val recipesRev: Long
    fun listRecipes(query: String, category: String, favoritesOnly: Boolean, limit: Int, offset: Int): RecipePage
    fun recipe(id: String): Recipe?
    /** Image de la recette déjà en cache sur le robot, ou null (l'API ne télécharge jamais rien). */
    fun imageFile(recipe: Recipe): File?
}

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    /** Noms en minuscules. */
    val headers: Map<String, String> = emptyMap(),
)

/** Réponse décidée par le routeur ; le serveur HTTP l'écrit sur la connexion. */
class ApiResponse private constructor(
    val status: Int,
    val contentType: String = JSON,
    val body: ByteArray = ByteArray(0),
    val file: File? = null,
    val headers: Map<String, String> = emptyMap(),
    val events: Boolean = false,
    val lastEventId: Long? = null,
) {
    companion object {
        const val JSON = "application/json; charset=utf-8"

        fun json(body: Any?, status: Int = 200, headers: Map<String, String> = emptyMap()) =
            ApiResponse(status, JSON, Json.write(body).toByteArray(Charsets.UTF_8), headers = headers)

        fun error(status: Int, code: String, message: String, headers: Map<String, String> = emptyMap()) =
            json(linkedMapOf("error" to code, "message" to message), status, headers)

        fun file(file: File, contentType: String, headers: Map<String, String>) =
            ApiResponse(200, contentType, file = file, headers = headers)

        fun notModified(headers: Map<String, String>) = ApiResponse(304, headers = headers)

        fun events(lastEventId: Long?) = ApiResponse(200, "text/event-stream", events = true, lastEventId = lastEventId)
    }
}

/**
 * Routage et contrôle d'accès de l'API (lecture seule). `/info` est public ; le reste exige le jeton
 * (`Authorization: Bearer …`). Aucune route n'agit sur la machine ni sur la session.
 */
class ApiRouter(private val backend: ApiBackend, private val token: () -> String) {

    fun handle(req: HttpRequest): ApiResponse {
        if (req.method != "GET") {
            return ApiResponse.error(405, "method_not_allowed", "API en lecture seule", mapOf("Allow" to "GET"))
        }
        val path = req.path.trimEnd('/')
        if (path != API_BASE && !path.startsWith("$API_BASE/")) return notFound()
        val rest = path.removePrefix(API_BASE)

        if (rest == "/info") {
            return ApiResponse.json(ApiModels.info(backend.device, backend.snapshot.firmware, backend.recipeCount, backend.recipesRev))
        }
        if (!authorized(req)) {
            return ApiResponse.error(401, "unauthorized", "Jeton absent ou invalide", mapOf("WWW-Authenticate" to "Bearer"))
        }
        return when {
            rest == "/state" -> ApiResponse.json(currentState())
            rest == "/session" -> ApiResponse.json(ApiModels.session(backend.session))
            rest == "/events" -> ApiResponse.events(req.headers["last-event-id"]?.trim()?.toLongOrNull())
            rest == "/recipes" -> recipes(req)
            rest.startsWith("/recipes/") -> recipeRoute(req, rest.removePrefix("/recipes/"))
            else -> notFound()
        }
    }

    /** Événements envoyés à l'ouverture du flux : l'état courant, pour éviter une interrogation en plus. */
    fun initialEvents(): List<Pair<String, String>> = listOf(
        "state" to Json.write(currentState()),
        "session" to Json.write(ApiModels.session(backend.session)),
    )

    private fun currentState() =
        ApiModels.state(
            backend.snapshot, backend.session, backend.screenOn, System.currentTimeMillis() / 1000, backend.snapshotAgeMs,
        )

    private fun notFound() = ApiResponse.error(404, "not_found", "Adresse inconnue")

    private fun authorized(req: HttpRequest): Boolean {
        val expected = token()
        if (expected.isEmpty()) return false
        val header = req.headers["authorization"] ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val given = header.substring(7).trim()
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    private fun recipes(req: HttpRequest): ApiResponse {
        val limit = (req.query["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset = (req.query["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val favorite = req.query["favorite"]?.lowercase() in setOf("1", "true", "yes")
        val page = backend.listRecipes(req.query["q"].orEmpty(), req.query["category"].orEmpty(), favorite, limit, offset)
        return ApiResponse.json(linkedMapOf(
            "rev" to backend.recipesRev,
            "total" to page.total,
            "limit" to limit,
            "offset" to offset,
            "recipes" to page.recipes.map { ApiModels.recipeSummary(it, imageUrl(it)) },
        ))
    }

    private fun recipeRoute(req: HttpRequest, tail: String): ApiResponse {
        val parts = tail.split('/')
        val id = decodeSegment(parts[0])
        if (id.isEmpty() || parts.size > 2) return notFound()
        val recipe = backend.recipe(id) ?: return ApiResponse.error(404, "unknown_recipe", "Recette inconnue")
        if (parts.size == 1) return ApiResponse.json(ApiModels.recipeDetail(recipe, imageUrl(recipe)))
        if (parts[1] != "image") return notFound()
        return image(req, recipe)
    }

    private fun image(req: HttpRequest, recipe: Recipe): ApiResponse {
        val file = backend.imageFile(recipe) ?: return ApiResponse.error(404, "no_image", "Image absente du robot")
        // Pas d'image pendant un programme : on protège la boucle de communication avec la carte moteur.
        if (backend.snapshot.busy) {
            return ApiResponse.error(503, "busy", "Programme en cours, réessayer plus tard", mapOf("Retry-After" to "5"))
        }
        val etag = "\"${file.length()}-${file.lastModified()}\""
        val headers = mapOf("ETag" to etag, "Cache-Control" to "max-age=86400")
        if (req.headers["if-none-match"]?.trim() == etag) return ApiResponse.notModified(headers)
        val type = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        return ApiResponse.file(file, type, headers)
    }

    private fun imageUrl(r: Recipe): String? =
        if (backend.imageFile(r) != null) "$API_BASE/recipes/${encodeSegment(r.id)}/image" else null

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100

        fun encodeSegment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun decodeSegment(s: String): String =
            try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { "" }
    }
}
