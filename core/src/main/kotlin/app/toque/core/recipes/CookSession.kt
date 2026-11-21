package app.toque.core.recipes

import app.toque.core.json.int
import app.toque.core.json.list
import app.toque.core.json.str
import java.util.concurrent.CopyOnWriteArrayList

/** Nature d'une étape : « manuelle » = sans réglage machine (ajouter, peser, un geste à faire). */
enum class StepKind { MANUAL, MACHINE }

/** Ce que la session attend maintenant (absent : rien, étape machine terminée ou recette sans étape). */
enum class Awaiting { USER, MACHINE }

enum class SessionResult { FINISHED, ABORTED }

/**
 * Une cuisson guidée en cours : la recette, l'étape courante et ce qui est déjà fait.
 * Immuable ; [CookSessions] la fait évoluer et prévient les observateurs (écran, API Home Assistant).
 *
 * [rev] augmente à chaque changement réel ; [id] reste le même pendant toute la cuisson, y compris
 * après une reprise (redémarrage de l'appli ou du robot).
 */
data class CookSession(
    val id: String,
    val recipe: Recipe,
    val stepIndex: Int = 0,
    /** Étapes machine terminées (une série enchaînée en couvre plusieurs). */
    val doneSteps: Set<Int> = emptySet(),
    val startedAt: Long = 0,
    val rev: Long = 1,
) {
    val stepCount: Int get() = recipe.steps.size
    val step: Step? get() = recipe.steps.getOrNull(stepIndex)
    val stepKind: StepKind? get() = step?.let { if (it.machine == null) StepKind.MANUAL else StepKind.MACHINE }
    val isLastStep: Boolean get() = stepCount == 0 || stepIndex >= stepCount - 1

    val awaiting: Awaiting?
        get() = when (stepKind) {
            null -> null
            StepKind.MANUAL -> Awaiting.USER
            StepKind.MACHINE -> if (stepIndex in doneSteps) null else Awaiting.MACHINE
        }

    /** Plus grand index d'étape manuelle strictement avant l'étape courante. */
    val lastManualIndex: Int?
        get() = (stepIndex - 1 downTo 0).firstOrNull { it < stepCount && recipe.steps[it].machine == null }

    /** Plus petit index d'étape manuelle strictement après l'étape courante. */
    val nextManualIndex: Int?
        get() = (stepIndex + 1 until stepCount).firstOrNull { recipe.steps[it].machine == null }

    val lastManual: Step? get() = lastManualIndex?.let { recipe.steps[it] }
    val nextManual: Step? get() = nextManualIndex?.let { recipe.steps[it] }

    /** Seul l'état à retenir pour reprendre ; la recette se retrouve par son identifiant. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "recipeId" to recipe.id,
        "stepIndex" to stepIndex,
        "done" to doneSteps.sorted(),
        "startedAt" to startedAt,
        "rev" to rev,
    )

    companion object {
        /** Reconstruit une session enregistrée ; null si l'enregistrement ou la recette est introuvable. */
        fun fromMap(m: Map<String, Any?>, recipeOf: (String) -> Recipe?): CookSession? {
            val id = m.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val recipe = recipeOf(m.str("recipeId") ?: return null) ?: return null
            val last = maxOf(0, recipe.steps.size - 1)
            return CookSession(
                id = id,
                recipe = recipe,
                stepIndex = (m.int("stepIndex") ?: 0).coerceIn(0, last),
                doneSteps = m["done"].list().mapNotNull { (it as? Number)?.toInt() }
                    .filter { it in recipe.steps.indices }.toSet(),
                startedAt = (m["startedAt"] as? Number)?.toLong() ?: 0L,
                rev = ((m["rev"] as? Number)?.toLong() ?: 0L).coerceAtLeast(1L),
            )
        }
    }
}

/** Ce qui change, tel que l'envoient les observateurs. */
sealed class CookEvent {
    /** La session a changé (démarrage, reprise, étape, étapes terminées, awaiting). */
    data class Changed(val session: CookSession) : CookEvent()

    /** Passage à une autre étape ([Changed] est aussi émis juste avant). */
    data class StepChanged(val session: CookSession) : CookEvent()

    /** Fin de recette ou abandon. */
    data class Ended(val sessionId: String, val recipeId: String, val result: SessionResult) : CookEvent()
}

/**
 * La session de cuisson courante (au plus une). Sûr entre fils : les changements sont sérialisés et les
 * observateurs appelés ensuite, hors verrou.
 *
 * Un observateur qui lève une exception n'empêche pas les suivants de recevoir l'événement (l'enregistrement de la
 * session et l'API Home Assistant sont deux observateurs indépendants) ; l'erreur est remise à [onListenerError].
 *
 * @param clock heure en secondes depuis 1970 ; @param newId fabrique d'identifiants (injectables pour les tests).
 */
class CookSessions(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val onListenerError: (Exception) -> Unit = {},
) {
    @Volatile
    var current: CookSession? = null
        private set

    private val listeners = CopyOnWriteArrayList<(CookEvent) -> Unit>()

    fun addListener(l: (CookEvent) -> Unit) { listeners.add(l) }
    fun removeListener(l: (CookEvent) -> Unit) { listeners.remove(l) }

    /** Nouvelle cuisson. Une session déjà en cours est abandonnée (événement [CookEvent.Ended]). */
    fun start(recipe: Recipe, stepIndex: Int = 0): CookSession {
        val events = ArrayList<CookEvent>()
        val s = synchronized(this) {
            endLocked(SessionResult.ABORTED, events)
            val first = CookSession(
                id = newId(), recipe = recipe,
                stepIndex = stepIndex.coerceIn(0, maxOf(0, recipe.steps.size - 1)),
                startedAt = clock(),
            )
            current = first
            events += CookEvent.Changed(first)
            first
        }
        dispatch(events)
        return s
    }

    /** Reprise d'une session enregistrée : même [CookSession.id], [CookSession.rev] qui continue de croître. */
    fun resume(saved: CookSession): CookSession {
        val events = ArrayList<CookEvent>()
        val s = synchronized(this) {
            endLocked(SessionResult.ABORTED, events)
            val resumed = saved.copy(rev = saved.rev + 1)
            current = resumed
            events += CookEvent.Changed(resumed)
            resumed
        }
        dispatch(events)
        return s
    }

    /** Va à l'étape [index] ; sans effet si elle n'existe pas ou si c'est déjà l'étape courante. */
    fun goTo(index: Int): CookSession? = update { s, events ->
        if (index !in s.recipe.steps.indices || index == s.stepIndex) return@update s
        val n = s.copy(stepIndex = index, rev = s.rev + 1)
        events += CookEvent.Changed(n)
        events += CookEvent.StepChanged(n)
        n
    }

    /** Étape suivante ; sur la dernière étape, termine la recette. */
    fun next(): CookSession? {
        val s = current ?: return null
        if (s.isLastStep) { end(SessionResult.FINISHED); return null }
        return goTo(s.stepIndex + 1)
    }

    /** Marque terminées les étapes machine [first] .. [first] + [count] - 1. */
    fun markDone(first: Int, count: Int = 1): CookSession? = update { s, events ->
        val add = (first until first + count).filter { it in s.recipe.steps.indices && it !in s.doneSteps }
        if (add.isEmpty()) return@update s
        val n = s.copy(doneSteps = s.doneSteps + add, rev = s.rev + 1)
        events += CookEvent.Changed(n)
        n
    }

    /** Termine ou abandonne la session courante ; sans effet s'il n'y en a pas. */
    fun end(result: SessionResult) {
        val events = ArrayList<CookEvent>()
        synchronized(this) { endLocked(result, events) }
        dispatch(events)
    }

    private fun endLocked(result: SessionResult, events: MutableList<CookEvent>) {
        val s = current ?: return
        current = null
        events += CookEvent.Ended(s.id, s.recipe.id, result)
    }

    private fun update(change: (CookSession, MutableList<CookEvent>) -> CookSession): CookSession? {
        val events = ArrayList<CookEvent>()
        val result = synchronized(this) {
            val s = current ?: return null
            val n = change(s, events)
            current = n
            n
        }
        dispatch(events)
        return result
    }

    private fun dispatch(events: List<CookEvent>) {
        for (e in events) for (l in listeners) {
            try { l(e) } catch (x: Exception) { onListenerError(x) }
        }
    }
}
