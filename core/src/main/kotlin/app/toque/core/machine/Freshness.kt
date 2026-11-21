package app.toque.core.machine

/**
 * Fraîcheur des instantanés reçus du service machine. Le service en émet au moins un par seconde ; au-delà de
 * [staleMs] de silence, ce que l'on affiche (et publie sur l'API) ne décrit plus le robot. Sûr entre fils.
 */
class Freshness(
    private val clock: Clock = MonotonicClock,
    val staleMs: Long = DEFAULT_STALE_MS,
) {
    @Volatile private var lastMs = -1L

    /** Un instantané vient d'arriver (ou la liaison vient de s'établir). */
    fun touch() {
        lastMs = clock.nowMs()
    }

    /** Âge du dernier instantané en millisecondes, ou -1 si rien n'a encore été reçu. */
    val ageMs: Long
        get() {
            val l = lastMs
            return if (l < 0) -1 else maxOf(0L, clock.nowMs() - l)
        }

    /** Vrai si quelque chose a déjà été reçu et que le silence dure depuis plus de [staleMs]. */
    val stale: Boolean
        get() {
            val a = ageMs
            return a >= 0 && a > staleMs
        }

    companion object {
        /** Trois fois la période d'émission du service (1 s) : un retard isolé n'est pas un silence. */
        const val DEFAULT_STALE_MS = 3_000L
    }
}
