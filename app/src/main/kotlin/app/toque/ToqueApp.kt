package app.toque

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import app.toque.api.HomeAssistantApi
import app.toque.core.i18n.I18n
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.recipes.CookEvent
import app.toque.core.recipes.CookSession
import app.toque.core.recipes.CookSessions
import app.toque.machine.MachineClient
import app.toque.recipes.RecipeRepo
import app.toque.ui.MainActivity
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class ToqueApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var log: Logbook
        private set
    val machine: MachineClient by lazy { MachineClient(this) }
    val recipes: RecipeRepo by lazy { RecipeRepo(this) }

    /** L'API locale pour Home Assistant (désactivée tant que l'utilisateur ne l'active pas dans les Réglages). */
    val homeAssistant: HomeAssistantApi by lazy { HomeAssistantApi(this) }

    /**
     * Veille de Toque (écran noir avec l'heure, rétroéclairage au minimum). Android, lui, reste éveillé : c'est ce
     * qui fait la « veille » que voit la personne, et l'API Home Assistant la publie via `screenOn`.
     */
    @Volatile var standbyAsleep = false
        private set

    /** À appeler (fil principal) quand la veille de Toque commence ou finit. */
    fun setStandbyAsleep(asleep: Boolean) {
        if (standbyAsleep == asleep) return
        standbyAsleep = asleep
        if (prefs.haEnabled) homeAssistant.onScreenChanged()
    }

    /** La cuisson guidée en cours (au plus une), enregistrée à chaque changement pour reprendre après un redémarrage. */
    val cook = CookSessions(onListenerError = { log.write("cuisine", "observateur en échec : $it") })

    /** Reprend la cuisson enregistrée, avec le même identifiant. À appeler une fois au démarrage de l'interface. */
    fun restoreCookSession() {
        if (cook.current != null) return
        try {
            val text = prefs.cookSession
            val saved = if (text.isNotBlank()) {
                CookSession.fromMap(Json.parse(text).obj()) { recipes.getAdjusted(it) }
            } else {
                // Ancien format : recette + étape, sans identifiant de session.
                prefs.takeLegacyGuided()?.let { (id, step) ->
                    recipes.getAdjusted(id)?.let { r ->
                        CookSession(
                            UUID.randomUUID().toString(), r,
                            stepIndex = step.coerceIn(0, maxOf(0, r.steps.size - 1)),
                            startedAt = System.currentTimeMillis() / 1000,
                        )
                    }
                }
            }
            if (saved != null) cook.resume(saved) else prefs.cookSession = ""
        } catch (e: Exception) {
            log.write("cuisine", "reprise de la recette impossible : $e")
            prefs.cookSession = ""
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        log = Logbook(this)
        LanguageFiles.install(this, log)
        I18n.select(I18n.resolve(prefs.language, Locale.getDefault().language).code)
        // Racines récentes absentes d'Android 6 (voir ExtraRoots), pour toutes les connexions HTTPS.
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(ExtraRoots.socketFactory(this))
        } catch (e: Exception) {
            log.write("tls", "racines supplémentaires non chargées : $e")
        }
        app.toque.ui.Ui.setAccent(prefs.accentColor)
        if (!isMainProcess()) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                log.crash("interface", error)
                val now = System.currentTimeMillis()
                val recent = now - prefs.lastCrashMs < 10_000
                prefs.lastCrashMs = now
                if (recent) throw error // évite une boucle de relances
                // Relance discrète de l'interface ; le processus machine, lui, continue.
                val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_ONE_SHOT)
                (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi)
                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
        cook.addListener { e ->
            when (e) {
                is CookEvent.Changed -> prefs.cookSession = Json.write(e.session.toMap())
                is CookEvent.Ended -> prefs.cookSession = ""
                is CookEvent.StepChanged -> Unit
            }
        }
        log.write("interface", "démarrage")
        TimeSync.start(this, prefs, log)
        if (prefs.haEnabled) homeAssistant.apply()
    }

    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: return true
        return name == packageName
    }

    companion object {
        fun of(c: Context): ToqueApp = c.applicationContext as ToqueApp
    }
}
