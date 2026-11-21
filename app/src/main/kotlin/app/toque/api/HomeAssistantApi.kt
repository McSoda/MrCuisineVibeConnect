package app.toque.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import app.toque.ToqueApp
import app.toque.core.api.ApiBackend
import app.toque.core.api.ApiPublisher
import app.toque.core.api.ApiRouter
import app.toque.core.api.DeviceInfo
import app.toque.core.api.EventBus
import app.toque.core.api.HttpServer
import app.toque.core.api.RecipePage
import app.toque.core.i18n.I18n
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.CookSession
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeFilter
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * L'API locale pour Home Assistant (docs/HOME_ASSISTANT_API.md) : le serveur HTTP, sa publication sur le réseau
 * (mDNS) et la liaison avec ce que l'appli sait déjà (machine, recettes, session de cuisson, préférences).
 *
 * Vit dans le processus principal, rattaché à [ToqueApp] : il continue quand l'écran change. Lecture seule.
 * [apply] est à appeler sur le fil principal (écoute de la machine) à chaque changement des préférences.
 */
class HomeAssistantApi(private val app: ToqueApp) : ApiBackend {
    private val bus = EventBus()
    private val publisher = ApiPublisher(this, bus)
    private var server: HttpServer? = null
    private var nsd: NsdManager.RegistrationListener? = null
    private val stops = ArrayList<() -> Unit>()

    @Volatile private var snap: Snapshot = Snapshot()
    @Volatile private var recipesRevision = 1L

    /** Port ouvert, ou 0 si l'API est arrêtée. */
    @Volatile var port = 0
        private set

    /** Pourquoi l'API n'a pas pu démarrer (port pris…), vide sinon. */
    @Volatile var error = ""
        private set

    val running: Boolean get() = server != null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            publisher.onScreenChanged()
        }
    }

    /** Démarre, arrête ou relance le serveur selon les préférences. */
    @Synchronized
    fun apply() {
        stop()
        val prefs = app.prefs
        if (!prefs.haEnabled) return
        if (prefs.haToken.isEmpty()) prefs.haToken = newToken()
        try {
            val s = HttpServer(ApiRouter(this) { app.prefs.haToken }, bus, { app.log.write("api", it) })
            val bound = s.start(prefs.haPort)
            server = s
            port = bound
            error = ""
            listen()
            announce(bound)
            app.log.write("api", "API Home Assistant à l'écoute sur le port $bound")
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            app.log.write("api", "démarrage impossible : $e")
            stop()
        }
    }

    /** Remplace le jeton ; l'ancien cesse de fonctionner tout de suite. */
    fun regenerateToken() {
        app.prefs.haToken = newToken()
    }

    @Synchronized
    fun stop() {
        for (s in stops) try { s() } catch (_: Exception) {}
        stops.clear()
        unannounce()
        server?.stop()
        server = null
        port = 0
    }

    private fun newToken(): String {
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    /** Abonnements aux sources de changements ; le fil principal suffit pour l'écoute de la machine. */
    private fun listen() {
        snap = app.machine.snapshot
        stops += app.machine.onSnapshot { s ->
            snap = s
            publisher.onSnapshot()
        }
        val cookListener: (app.toque.core.recipes.CookEvent) -> Unit = { publisher.onCookEvent(it) }
        app.cook.addListener(cookListener)
        stops += { app.cook.removeListener(cookListener) }
        stops += app.recipes.onChange {
            recipesRevision++
            publisher.onRecipesChanged()
        }
        try {
            app.registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
            stops += { app.unregisterReceiver(screenReceiver) }
        } catch (e: Exception) {
            app.log.write("api", "écoute de l'écran impossible : $e")
        }
    }

    // ---- Publication mDNS (_toque._tcp) pour la découverte automatique

    private fun announce(port: Int) {
        try {
            val manager = app.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = app.prefs.deviceId
                serviceType = "_toque._tcp."
                setPort(port)
                setAttribute("api", "1")
                setAttribute("id", app.prefs.deviceId)
                setAttribute("version", versionName)
                setAttribute("name", app.prefs.haName)
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { app.log.write("api", "annonce mDNS refusée ($code)") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = l
        } catch (e: Exception) {
            app.log.write("api", "annonce mDNS impossible : $e")
        }
    }

    private fun unannounce() {
        val l = nsd ?: return
        nsd = null
        try {
            (app.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l)
        } catch (_: Exception) {}
    }

    // ---- ApiBackend

    private val versionName: String by lazy {
        try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
    }

    @Suppress("DEPRECATION")
    private val versionCode: Int by lazy {
        try { app.packageManager.getPackageInfo(app.packageName, 0).versionCode } catch (_: Exception) { 0 }
    }

    override val device: DeviceInfo
        get() = DeviceInfo(
            id = app.prefs.deviceId,
            name = app.prefs.haName,
            toqueVersion = versionName,
            toqueBuild = versionCode,
            androidRelease = Build.VERSION.RELEASE ?: "",
            androidSdk = Build.VERSION.SDK_INT,
            ip = localIp(),
            uptimeS = SystemClock.elapsedRealtime() / 1000,
            lang = I18n.code,
        )

    override val snapshot: Snapshot get() = snap

    override val screenOn: Boolean
        get() = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    override val session: CookSession? get() = app.cook.current

    override val recipeCount: Int get() = app.recipes.count

    override val recipesRev: Long get() = recipesRevision

    override fun listRecipes(query: String, category: String, favoritesOnly: Boolean, limit: Int, offset: Int): RecipePage {
        val hits = app.recipes.index.search(query, RecipeFilter(category = category, favoritesOnly = favoritesOnly), limit = 100_000)
        val page = hits.drop(offset).take(limit).mapNotNull { app.recipes.get(it.entry.id) }
        return RecipePage(hits.size, page)
    }

    override fun recipe(id: String): Recipe? = app.recipes.get(id)

    override fun imageFile(recipe: Recipe): File? = app.recipes.imageFile(recipe.image)

    /** Adresse IPv4 du réseau local (la première adresse privée non locale), ou vide. */
    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress ?: ""
    } catch (_: Exception) {
        ""
    }

    /** Adresse de base à saisir dans Home Assistant (pour l'affichage). */
    fun baseUrl(): String = if (port == 0) "" else "http://${localIp().ifEmpty { "?" }}:$port"
}
