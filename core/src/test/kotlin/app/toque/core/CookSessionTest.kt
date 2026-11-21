package app.toque.core

import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.recipes.Awaiting
import app.toque.core.recipes.CookEvent
import app.toque.core.recipes.CookSession
import app.toque.core.recipes.CookSessions
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.SessionResult
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepKind
import app.toque.core.recipes.StepMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookSessionTest {
    private fun manual(text: String) = Step(text)
    private fun machine(text: String) = Step(text, StepMachine(seconds = 60, speed = 2))

    /** 0 manuelle, 1 machine, 2 manuelle, 3 machine, 4 machine, 5 manuelle, 6 machine. */
    private val recipe = Recipe(
        id = "r1", title = "Velouté",
        steps = listOf(
            manual("éplucher"), machine("mixer"), manual("ajouter la crème"),
            machine("chauffer"), machine("mixer encore"), manual("verser"), machine("fin"),
        ),
    )

    private var ids = 0
    private fun sessions() = CookSessions(clock = { 1000L }, newId = { "id-${++ids}" })

    @Test
    fun manualStepsAreStepsWithoutMachineSetting() {
        val s = CookSession("a", recipe, stepIndex = 0)
        assertEquals(StepKind.MANUAL, s.stepKind)
        assertEquals(StepKind.MACHINE, s.copy(stepIndex = 1).stepKind)
    }

    @Test
    fun lastAndNextManualAreStrictlyBeforeAndAfter() {
        val onMachine = CookSession("a", recipe, stepIndex = 3)
        assertEquals(2, onMachine.lastManualIndex)
        assertEquals(5, onMachine.nextManualIndex)
        assertEquals("ajouter la crème", onMachine.lastManual?.text)
        assertEquals("verser", onMachine.nextManual?.text)

        // Sur une étape manuelle, elle-même n'est ni la dernière ni la prochaine.
        val onManual = CookSession("a", recipe, stepIndex = 2)
        assertEquals(0, onManual.lastManualIndex)
        assertEquals(5, onManual.nextManualIndex)
    }

    @Test
    fun lastAndNextManualAreNullAtTheEdges() {
        val first = CookSession("a", recipe, stepIndex = 0)
        assertNull(first.lastManualIndex)
        val last = CookSession("a", recipe, stepIndex = 6)
        assertEquals(5, last.lastManualIndex)
        assertNull(last.nextManualIndex)
        assertNull(last.nextManual)
    }

    @Test
    fun awaitingFollowsTheStep() {
        assertEquals(Awaiting.USER, CookSession("a", recipe, stepIndex = 0).awaiting)
        assertEquals(Awaiting.MACHINE, CookSession("a", recipe, stepIndex = 1).awaiting)
        assertNull(CookSession("a", recipe, stepIndex = 1, doneSteps = setOf(1)).awaiting)
        assertNull(CookSession("a", recipe.copy(steps = emptyList())).awaiting)
    }

    @Test
    fun startCreatesSessionAndPublishesIt() {
        val hub = sessions()
        val seen = ArrayList<CookEvent>()
        hub.addListener { seen += it }
        val s = hub.start(recipe)
        assertEquals("id-1", s.id)
        assertEquals(1L, s.rev)
        assertEquals(1000L, s.startedAt)
        assertEquals(s, hub.current)
        assertEquals(listOf<CookEvent>(CookEvent.Changed(s)), seen)
    }

    @Test
    fun startClampsStepIndex() {
        assertEquals(6, sessions().start(recipe, stepIndex = 99).stepIndex)
        assertEquals(0, sessions().start(recipe, stepIndex = -3).stepIndex)
        assertEquals(0, sessions().start(recipe.copy(steps = emptyList()), stepIndex = 4).stepIndex)
    }

    @Test
    fun goToBumpsRevAndEmitsStepChange() {
        val hub = sessions()
        hub.start(recipe)
        val seen = ArrayList<CookEvent>()
        hub.addListener { seen += it }
        val s = hub.goTo(2)!!
        assertEquals(2, s.stepIndex)
        assertEquals(2L, s.rev)
        assertEquals(listOf(CookEvent.Changed(s), CookEvent.StepChanged(s)), seen)
    }

    @Test
    fun noOpChangesDoNotBumpRevNorEmit() {
        val hub = sessions()
        val s0 = hub.start(recipe, 2)
        val seen = ArrayList<CookEvent>()
        hub.addListener { seen += it }
        assertEquals(s0, hub.goTo(2))
        assertEquals(s0, hub.goTo(-1))
        assertEquals(s0, hub.goTo(7))
        assertEquals(s0, hub.markDone(-4, 1))
        assertTrue(seen.isEmpty())
        hub.markDone(1)
        seen.clear()
        assertEquals(hub.current, hub.markDone(1))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun markDoneCoversAChainOfSteps() {
        val hub = sessions()
        hub.start(recipe, 3)
        val s = hub.markDone(3, 2)!!
        assertEquals(setOf(3, 4), s.doneSteps)
        assertEquals(2L, s.rev)
        assertNull(s.awaiting)
        // Les indices hors recette sont ignorés.
        assertEquals(setOf(3, 4, 6), hub.markDone(6, 5)!!.doneSteps)
    }

    @Test
    fun nextOnLastStepFinishesTheSession() {
        val hub = sessions()
        val s = hub.start(recipe, 6)
        val seen = ArrayList<CookEvent>()
        hub.addListener { seen += it }
        assertNull(hub.next())
        assertNull(hub.current)
        assertEquals(listOf<CookEvent>(CookEvent.Ended(s.id, "r1", SessionResult.FINISHED)), seen)
    }

    @Test
    fun nextMovesForward() {
        val hub = sessions()
        hub.start(recipe, 0)
        assertEquals(1, hub.next()!!.stepIndex)
    }

    @Test
    fun startingAgainAbortsThePreviousSession() {
        val hub = sessions()
        val first = hub.start(recipe)
        val seen = ArrayList<CookEvent>()
        hub.addListener { seen += it }
        val second = hub.start(recipe)
        assertNotEquals(first.id, second.id)
        assertEquals(CookEvent.Ended(first.id, "r1", SessionResult.ABORTED), seen[0])
        assertEquals(CookEvent.Changed(second), seen[1])
    }

    @Test
    fun operationsWithoutSessionDoNothing() {
        val hub = sessions()
        assertNull(hub.goTo(1))
        assertNull(hub.markDone(1))
        assertNull(hub.next())
        hub.end(SessionResult.ABORTED)
        assertNull(hub.current)
    }

    @Test
    fun resumeKeepsIdAndKeepsRevGrowing() {
        val hub = sessions()
        hub.start(recipe)
        hub.goTo(3)
        hub.markDone(3)
        val saved = hub.current!!
        val restored = CookSession.fromMap(saved.toMap()) { if (it == "r1") recipe else null }!!
        assertEquals(saved, restored)

        val other = sessions()
        val resumed = other.resume(restored)
        assertEquals(saved.id, resumed.id)
        assertEquals(saved.rev + 1, resumed.rev)
        assertEquals(3, resumed.stepIndex)
        assertEquals(setOf(3), resumed.doneSteps)
        assertEquals(saved.startedAt, resumed.startedAt)
    }

    @Test
    fun savedSessionSurvivesAJsonRoundTrip() {
        val saved = CookSession("a-1", recipe, stepIndex = 4, doneSteps = setOf(1, 3), startedAt = 1763700000L, rev = 9L)
        val text = Json.write(saved.toMap())
        val back = CookSession.fromMap(Json.parse(text).obj()) { recipe }
        assertEquals(saved, back)
    }

    @Test
    fun fromMapRejectsUnknownRecipeAndMissingId() {
        val m = CookSession("a", recipe).toMap()
        assertNull(CookSession.fromMap(m) { null })
        assertNull(CookSession.fromMap(m - "id") { recipe })
        assertNull(CookSession.fromMap(emptyMap()) { recipe })
    }

    @Test
    fun fromMapClampsDamagedValues() {
        val m = linkedMapOf<String, Any?>(
            "id" to "a", "recipeId" to "r1", "stepIndex" to 99, "done" to listOf(1, 42, "x", -1), "rev" to -5,
        )
        val s = CookSession.fromMap(m) { recipe }!!
        assertEquals(6, s.stepIndex)
        assertEquals(setOf(1), s.doneSteps)
        assertEquals(1L, s.rev)
    }

    @Test
    fun anObserverCanReadTheHubWithoutDeadlock() {
        val hub = sessions()
        var stepSeen = -1
        hub.addListener { if (it is CookEvent.Changed) stepSeen = hub.current!!.stepIndex }
        hub.start(recipe)
        hub.goTo(4)
        assertEquals(4, stepSeen)
    }
}
