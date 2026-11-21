package app.toque.core

import app.toque.core.api.ApiBackend
import app.toque.core.api.DeviceInfo
import app.toque.core.api.RecipePage
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.machine.LinkState
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.CookSession
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.Weigh
import java.io.File

/** Robot simulé pour les tests de l'API. */
class FakeBackend : ApiBackend {
    @Volatile var snap = Snapshot(link = LinkState.READY)
    @Volatile var screen = true
    @Volatile var sess: CookSession? = null
    @Volatile var rev = 1L
    val recipes = ArrayList<Recipe>()
    val images = HashMap<String, File>()

    override val device: DeviceInfo
        get() = DeviceInfo(
            id = "toque-test", name = "Cuisine", toqueVersion = "0.5.6", toqueBuild = 13,
            androidRelease = "6.0", androidSdk = 23, ip = "192.168.1.40", uptimeS = 100, lang = "fr",
        )
    override val snapshot: Snapshot get() = snap
    override val screenOn: Boolean get() = screen
    override val session: CookSession? get() = sess
    override val recipeCount: Int get() = recipes.size
    override val recipesRev: Long get() = rev

    override fun listRecipes(query: String, category: String, favoritesOnly: Boolean, limit: Int, offset: Int): RecipePage {
        val all = recipes.filter {
            (query.isEmpty() || it.title.contains(query, ignoreCase = true)) &&
                (category.isEmpty() || it.category == category) && (!favoritesOnly || it.favorite)
        }
        return RecipePage(all.size, all.drop(offset).take(limit))
    }

    override fun recipe(id: String): Recipe? = recipes.firstOrNull { it.id == id }
    override fun imageFile(recipe: Recipe): File? = images[recipe.id]
}

/** 0 manuelle, 1 machine, 2 manuelle (avec pesée), 3 machine, 4 machine, 5 manuelle, 6 machine. */
fun sampleRecipe(id: String = "veloute", title: String = "Velouté") = Recipe(
    id = id, title = title, category = "Soupe", tags = listOf("automne"), servings = 4,
    prepMinutes = 15, totalMinutes = 40, difficulty = 2, rating = 4.5, ratingCount = 12, image = "https://x/y.jpg",
    ingredients = listOf(IngredientGroup("", listOf(Ingredient("800", "g", "potiron"), Ingredient("20", "cl", "crème")))),
    steps = listOf(
        Step("Éplucher"),
        Step("Mixer", StepMachine(seconds = 60, speed = 2)),
        Step("Ajouter la crème", weigh = Weigh("Crème", 200)),
        Step("", StepMachine(seconds = 120, speed = 1, tempC = 90)),
        Step("Mixer encore", StepMachine(seconds = 30, speed = 5)),
        Step("Verser"),
        Step("Fin", StepMachine(seconds = 10, speed = 1)),
    ),
)

fun jsonObj(body: ByteArray): Map<String, Any?> = Json.parse(String(body, Charsets.UTF_8)).obj()
