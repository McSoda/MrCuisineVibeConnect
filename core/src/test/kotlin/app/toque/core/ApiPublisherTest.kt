package app.toque.core

import app.toque.core.api.ApiPublisher
import app.toque.core.api.EventBus
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.recipes.CookEvent
import app.toque.core.recipes.CookSession
import app.toque.core.recipes.SessionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiPublisherTest {
    private val backend = FakeBackend()
    private val bus = EventBus()
    private var now = 1_000_000L
    private val pub = ApiPublisher(backend, bus) { now }

    private fun names(afterId: Long = 0) = bus.since(afterId).map { it.name }
    private fun states() = bus.since(0).filter { it.name == "state" }
    private val session = CookSession("s1", sampleRecipe(), stepIndex = 1)

    @Test
    fun firstSnapshotPublishesState() {
        pub.onSnapshot()
        assertEquals(listOf("state"), names())
    }

    @Test
    fun identicalSnapshotsAreNotRepublished() {
        pub.onSnapshot()
        repeat(20) { now += 100; pub.onSnapshot() }
        assertEquals(1, states().size)
    }

    @Test
    fun smallAnalogChangesAreIgnored() {
        pub.onSnapshot()
        now += 1_500
        backend.snap = backend.snap.copy(rpm = 49, weightG = 1)
        pub.onSnapshot()
        assertEquals(1, states().size)
    }

    @Test
    fun analogChangesAreLimitedToOnePerSecond() {
        pub.onSnapshot()
        now += 400
        backend.snap = backend.snap.copy(actualTempC = 30)
        pub.onSnapshot()
        assertEquals("trop tôt", 1, states().size)
        now += 700
        pub.onSnapshot()
        assertEquals(2, states().size)
        // Écart mesuré par rapport à la dernière valeur publiée, pas la précédente.
        now += 1_100
        backend.snap = backend.snap.copy(actualTempC = 30)
        pub.onSnapshot()
        assertEquals(2, states().size)
        backend.snap = backend.snap.copy(actualTempC = 31)
        pub.onSnapshot()
        assertEquals(3, states().size)
    }

    @Test
    fun weightAndRpmThresholds() {
        pub.onSnapshot()
        now += 2_000
        backend.snap = backend.snap.copy(weightG = 2)
        pub.onSnapshot()
        assertEquals(2, states().size)
        now += 2_000
        backend.snap = backend.snap.copy(rpm = 50)
        pub.onSnapshot()
        assertEquals(3, states().size)
    }

    @Test
    fun discreteChangesGoOutImmediately() {
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(lidOpen = true)
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(bowlDetected = true)
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(motorOn = true)
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(heaterOn = true)
        pub.onSnapshot()
        now += 10
        backend.screen = false
        pub.onSnapshot()
        assertEquals(6, states().size)
    }

    @Test
    fun jobAndPhaseChangesGoOutImmediately() {
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(jobActive = true, jobState = "RUNNING", jobKind = "MANUAL", phaseCount = 2)
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(phaseIndex = 1)
        pub.onSnapshot()
        assertEquals(3, states().size)
    }

    @Test
    fun idleHeartbeatIsThirtySeconds() {
        pub.onSnapshot()
        now += 29_000
        pub.onSnapshot()
        assertEquals(1, states().size)
        now += 1_000
        pub.onSnapshot()
        assertEquals(2, states().size)
    }

    @Test
    fun heartbeatIsFiveSecondsDuringAJob() {
        backend.snap = backend.snap.copy(jobActive = true, jobState = "RUNNING", jobKind = "MANUAL")
        pub.onSnapshot()
        now += 4_900
        backend.snap = backend.snap.copy(remainingMs = 10_000)
        pub.onSnapshot()
        assertEquals(1, states().size)
        now += 200
        pub.onSnapshot()
        assertEquals(2, states().size)
    }

    @Test
    fun faultRaisedAndClearedArePublished() {
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(faultCode = "E7", faultMessage = "Surchauffe")
        pub.onSnapshot()
        val raised = bus.since(0).first { it.name == "fault" }
        assertEquals(mapOf("code" to "E7", "message" to "Surchauffe", "active" to true), Json.parse(raised.data).obj())
        now += 10
        backend.snap = backend.snap.copy(faultCode = "", faultMessage = "")
        pub.onSnapshot()
        val cleared = bus.since(0).filter { it.name == "fault" }.last()
        assertEquals("E7", Json.parse(cleared.data).obj()["code"])
        assertEquals(false, Json.parse(cleared.data).obj()["active"])
        assertEquals(2, bus.since(0).count { it.name == "fault" })
        // Un défaut qui dure ne se répète pas.
        now += 10
        pub.onSnapshot()
        assertEquals(2, bus.since(0).count { it.name == "fault" })
    }

    @Test
    fun screenChangeIsPublished() {
        pub.onSnapshot()
        backend.screen = false
        pub.onScreenChanged()
        assertEquals(false, Json.parse(states().last().data).obj()["screenOn"])
    }

    @Test
    fun cookEventsBecomeSessionStepDoneAndState() {
        backend.sess = session
        pub.onCookEvent(CookEvent.Changed(session))
        assertEquals(listOf("session", "state"), names())
        assertEquals("recipe", Json.parse(states().last().data).obj()["status"])

        val before = bus.lastId
        pub.onCookEvent(CookEvent.StepChanged(session.copy(stepIndex = 2)))
        assertEquals(listOf("step", "state"), names(before))
        val step = Json.parse(bus.since(before).first().data).obj()
        assertEquals("manual", step["kind"])
        assertEquals("s1", step["sessionId"])
    }

    @Test
    fun endingASessionPublishesDoneAndAnInactiveSession() {
        backend.sess = session
        pub.onCookEvent(CookEvent.Changed(session))
        val before = bus.lastId
        backend.sess = null
        pub.onCookEvent(CookEvent.Ended("s1", "veloute", SessionResult.FINISHED))
        assertEquals(listOf("done", "session", "state"), names(before))
        val events = bus.since(before)
        assertEquals("finished", Json.parse(events[0].data).obj()["result"])
        assertEquals(false, Json.parse(events[1].data).obj()["active"])
        assertEquals("standby", Json.parse(events[2].data).obj()["status"])

        pub.onCookEvent(CookEvent.Ended("s2", "x", SessionResult.ABORTED))
        assertEquals("aborted", Json.parse(bus.since(0).last { it.name == "done" }.data).obj()["result"])
    }

    @Test
    fun recipesEventCarriesRevAndTotal() {
        backend.recipes += sampleRecipe()
        backend.rev = 8
        pub.onRecipesChanged()
        val e = Json.parse(bus.since(0).single().data).obj()
        assertEquals(8, (e["rev"] as Number).toInt())
        assertEquals(1, (e["total"] as Number).toInt())
    }

    @Test
    fun aFailingSubscriberDoesNotBlockTheOthersNorThePublisher() {
        val errors = ArrayList<Exception>()
        val b = EventBus(onListenerError = { errors += it })
        val seen = ArrayList<String>()
        b.subscribe { throw IllegalStateException("boum") }
        b.subscribe { seen += it.name }
        val e = b.publish("x", null)
        assertEquals(listOf("x"), seen)
        assertEquals(1, errors.size)
        assertEquals(e.id, b.lastId)
    }

    @Test
    fun busKeepsOnlyTheLastEventsAndSubscribersGetLiveOnes() {
        val small = EventBus(capacity = 3)
        val seen = ArrayList<String>()
        val off = small.subscribe { seen += it.name }
        repeat(5) { small.publish("e$it", mapOf("n" to it)) }
        assertEquals(listOf("e0", "e1", "e2", "e3", "e4"), seen)
        assertEquals(listOf("e2", "e3", "e4"), small.since(0).map { it.name })
        assertEquals(listOf("e4"), small.since(4).map { it.name })
        assertEquals(5L, small.lastId)
        off()
        small.publish("late", null)
        assertEquals(5, seen.size)
        assertTrue(small.since(5).single().data == "null")
    }
}
