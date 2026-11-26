package app.toque.ui

import app.toque.core.machine.RootShell
import app.toque.core.ring.KnobRing
import app.toque.core.ring.Rgb
import app.toque.core.ring.RingColour
import app.toque.core.ring.RingLeds
import java.util.concurrent.Executors

/**
 * Anneau de la molette : code couleur (voir [RingColour]), anneau éteint pendant une recette guidée
 * (sur demande) et lumière pendant la veille (une veilleuse blanche, faible et fixe, ou éteinte),
 * tous tenus par un seul [KnobRing].
 *
 * L'anneau a trois LED, vues par Android sous /sys/class/leds : « red » (rouge), « green » (vert)
 * et « blue », qui allume en fait du blanc (pas de LED bleue). L'intensité va de 1 à 79 (puis
 * repart de très faible à 80 et à 160). Une intensité fixe est tenue sans défaut ; des changements
 * fréquents (une respiration) rendent l'anneau irrégulier au bout d'un cycle : on n'écrit donc que
 * quand la couleur à montrer change, et la veille est une veilleuse plutôt qu'une respiration.
 *
 * Le blanc du robot est noté dans [SAVED] tant que l'anneau montre autre chose : si Toque s'arrête
 * entre-temps, il le retrouve au démarrage suivant.
 */
object KnobLight {
    private const val LEDS = "/sys/class/leds"
    private const val SAVED = "/data/local/tmp/toque-knob-light.saved"
    const val NIGHT_LEVEL = 3

    /** Journal de l'appli (posé par l'activité). */
    @Volatile var log: (String) -> Unit = {}

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-molette").apply { isDaemon = true } }
    private val ring = KnobRing(RootLeds) { m -> log(m) }
    @Volatile private var dimmed = false
    /** Dernière demande transmise : couleur et anneau gardé éteint (null : aucune). */
    private var requested: Pair<RingColour?, Boolean>? = null

    /**
     * Code couleur : [colour], ou null s'il est coupé ; [dark] : anneau gardé éteint, rouge excepté
     * (voir [KnobRing.show]). Appelée à chaque instantané ; n'agit qu'au changement.
     */
    fun show(colour: RingColour?, dark: Boolean) {
        val request = colour to dark
        if (requested == request) return
        requested = request
        worker.execute { ring.show(colour, dark) }
    }

    /** [level] : intensité du blanc pendant la veille ([NIGHT_LEVEL] en veilleuse, 0 pour éteindre). */
    fun start(level: Int) {
        if (dimmed) return
        dimmed = true
        worker.execute { ring.standby(level) }
    }

    fun stop() {
        if (!dimmed) return
        dimmed = false
        worker.execute { ring.wake() }
    }

    /** Toque rend l'écran (appli d'origine, Toque masqué) : l'anneau reprend le blanc du robot. */
    fun release() {
        requested = null
        dimmed = false
        worker.execute { ring.release() }
    }

    private object RootLeds : RingLeds {
        private fun parse(s: String): Rgb? {
            val n = s.trim().split(Regex("\\s+")).map { it.toIntOrNull() ?: return null }
            return if (n.size == 3) Rgb(n[0], n[1], n[2]) else null
        }

        override fun read(): Rgb? {
            val r = RootShell.run(
                """
                L=$LEDS
                for c in red green blue; do [ -w ${'$'}L/${'$'}c/brightness ] || exit 3; done
                if [ -f $SAVED ]; then cat $SAVED
                else echo "${'$'}(cat ${'$'}L/red/brightness) ${'$'}(cat ${'$'}L/green/brightness) ${'$'}(cat ${'$'}L/blue/brightness)"
                fi
                """.trimIndent(),
                8_000,
            )
            return if (r.ok) parse(r.out) else null
        }

        override fun write(colour: Rgb, restore: Rgb?): Boolean {
            if (!colour.valid || (restore != null && !restore.valid)) return false
            // La note est écrite avant de changer l'anneau et effacée après l'avoir remis : arrêté entre
            // les deux, Toque ne prend pas un anneau éteint ou coloré pour le blanc du robot.
            val before = if (restore != null) "echo \"$restore\" > $SAVED && " else ""
            val after = if (restore != null) "" else " && rm -f $SAVED"
            val r = RootShell.run(
                """
                L=$LEDS
                ${before}echo ${colour.red} > ${'$'}L/red/brightness && echo ${colour.green} > ${'$'}L/green/brightness && echo ${colour.white} > ${'$'}L/blue/brightness$after
                """.trimIndent(),
                8_000,
            )
            return r.ok
        }

        override fun noted(): Boolean = RootShell.run("[ -f $SAVED ]", 8_000).ok
    }
}
