package app.toque.core

import app.toque.core.api.ApiModels
import app.toque.core.api.ApiStatus
import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.machine.LinkState
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.CookSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsTest {
    private val idle = Snapshot(link = LinkState.READY)
    private val running = idle.copy(jobActive = true, jobState = "RUNNING", jobKind = "MANUAL")
    private val session = CookSession("s1", sampleRecipe(), stepIndex = 3)

    @Test
    fun statusPrecedenceIsFaultThenRecipeThenRunning() {
        assertEquals(ApiStatus.STANDBY, ApiStatus.of(idle, null))
        assertEquals(ApiStatus.RUNNING, ApiStatus.of(running, null))
        assertEquals(ApiStatus.RECIPE, ApiStatus.of(running, session))
        assertEquals(ApiStatus.RECIPE, ApiStatus.of(idle, session))
        assertEquals(ApiStatus.FAULT, ApiStatus.of(running.copy(faultCode = "E1"), session))
    }

    @Test
    fun stateHasNoJobAndNoFaultWhenIdle() {
        val m = ApiModels.state(idle, null, screenOn = false, nowSec = 42)
        assertEquals(42L, m["ts"])
        assertEquals("standby", m["status"])
        assertEquals(false, m["screenOn"])
        assertNull(m["job"])
        assertNull(m["fault"])
        assertNull(m["sessionId"])
    }

    @Test
    fun stateCarriesTheSnapshotAge() {
        assertEquals(-1L, ApiModels.state(idle, null, true, 1)["snapshotAgeMs"])
        assertEquals(4_200L, ApiModels.state(idle, null, true, 1, snapshotAgeMs = 4_200)["snapshotAgeMs"])
    }

    @Test
    fun stateReportsMachineJobAndSession() {
        val s = running.copy(
            actualTempC = 82, targetTempC = 90, rpm = 1200, speedLevel = 4, weightG = 350, lidOpen = true,
            phaseIndex = 0, phaseCount = 2, elapsedMs = 40_500, totalMs = 300_000, remainingMs = 259_500,
            jobSpeed = 4, jobLabel = "Étape 3", accessory = "BLADE",
        )
        val back = Json.parse(Json.write(ApiModels.state(s, session, true, 1))).obj()
        assertEquals("recipe", back["status"])
        assertEquals("s1", back["sessionId"])
        val machine = back["machine"].obj()
        assertEquals(82, (machine["tempC"] as Number).toInt())
        assertEquals(true, machine["lidOpen"])
        val job = back["job"].obj()
        assertEquals("MANUAL", job["kind"])
        assertEquals(1, (job["phase"] as Number).toInt())        // 1-based
        assertEquals(2, (job["phaseCount"] as Number).toInt())
        assertEquals(259L, (job["remainingS"] as Number).toLong())
    }

    private fun machineOf(s: Snapshot) = Json.parse(Json.write(ApiModels.state(s, null, true, 1))).obj()["machine"].obj()

    @Test
    fun heaterIsOnOnlyWithATargetTemperature() {
        // Relevé sur le robot : programme `STANDARD v3 t0`, la base répond « moteur + chauffe », consigne 0 °C.
        val motorOnly = idle.copy(motorOn = true, heaterOn = true, targetTempC = 0)
        assertEquals(false, machineOf(motorOnly)["heaterOn"])
        assertEquals(true, machineOf(motorOnly.copy(targetTempC = 90))["heaterOn"])
        assertEquals(false, machineOf(idle.copy(targetTempC = 90))["heaterOn"])
        // Le signal brut, lui, reste disponible pour les contrôles de sécurité.
        assertTrue(motorOnly.heaterOn)
    }

    @Test
    fun weightIsUnknownWhileTheMotorRuns() {
        assertEquals(null, machineOf(idle.copy(motorOn = true, weightG = 0))["weightG"])
        assertEquals(1245, (machineOf(idle.copy(weightG = 1245))["weightG"] as Number).toInt())
        assertEquals(0, (machineOf(idle.copy(weightG = 0))["weightG"] as Number).toInt())
    }

    @Test
    fun targetSpeedIsReportedBesideTheMeasuredOne() {
        val m = machineOf(idle.copy(motorOn = true, targetSpeedLevel = 3, rpm = 0, speedLevel = 0))
        assertEquals(3, (m["targetSpeed"] as Number).toInt())
        assertEquals(0, (m["speed"] as Number).toInt())
    }

    @Test
    fun snapshotSurvivesTheMapRoundTripWithTheNewField() {
        val s = idle.copy(targetSpeedLevel = 5, motorOn = true, heaterOn = true, targetTempC = 70, weightG = 12)
        val back = Snapshot.fromMap(s.toMap())
        assertEquals(s, back)
        assertTrue(back.heating)
        assertEquals(5, back.targetSpeedLevel)
    }

    @Test
    fun stateReportsFault() {
        val m = ApiModels.state(idle.copy(faultCode = "E7", faultMessage = "Surchauffe"), null, true, 1)
        assertEquals("fault", m["status"])
        assertEquals(mapOf("code" to "E7", "message" to "Surchauffe"), m["fault"])
    }

    @Test
    fun noSessionIsInactive() {
        assertEquals(mapOf("active" to false), ApiModels.session(null))
    }

    @Test
    fun sessionDescribesStepAndManualNeighbours() {
        val m = Json.parse(Json.write(ApiModels.session(session))).obj()
        assertEquals(true, m["active"])
        assertEquals("s1", m["id"])
        assertEquals(3, (m["stepIndex"] as Number).toInt())
        assertEquals(7, (m["stepCount"] as Number).toInt())
        assertEquals("machine", m["awaiting"])
        val step = m["step"].obj()
        assertEquals("machine", step["kind"])
        assertTrue("texte de repli = résumé du réglage", (step["text"] as String).isNotBlank())
        assertEquals("manual", ApiModels.session(session.copy(stepIndex = 2)).let { it["step"].obj()["kind"] })
        assertEquals(2, (m["lastManual"].obj()["index"] as Number).toInt())
        assertEquals("Ajouter la crème", m["lastManual"].obj()["text"])
        assertEquals(5, (m["nextManual"].obj()["index"] as Number).toInt())
        assertEquals(2, m["ingredients"].list().first().obj()["items"].list().size)
    }

    @Test
    fun sessionStepCarriesWeighingAndMachineSettings() {
        val onWeigh = Json.parse(Json.write(ApiModels.session(session.copy(stepIndex = 2)))).obj()["step"].obj()
        assertEquals(200, (onWeigh["weigh"].obj()["grams"] as Number).toInt())
        val onMachine = Json.parse(Json.write(ApiModels.session(session))).obj()["step"].obj()
        assertEquals(90, (onMachine["machine"].obj()["tempC"] as Number).toInt())
        assertEquals("manuel", onMachine["machine"].obj()["program"])
    }

    @Test
    fun sessionEdgesHaveNoNeighbourAndNoAwaitingWhenDone() {
        val last = ApiModels.session(CookSession("s", sampleRecipe(), stepIndex = 6, doneSteps = setOf(6)))
        assertNull(last["nextManual"])
        assertNull(last["awaiting"])
        assertNull(ApiModels.session(CookSession("s", sampleRecipe(), stepIndex = 0))["lastManual"])
    }

    @Test
    fun recipeSummaryAndDetail() {
        val r = sampleRecipe()
        val sum = ApiModels.recipeSummary(r, "/api/v1/recipes/veloute/image")
        assertEquals("Soupe", sum["category"])
        assertEquals(2, sum["difficulty"])
        assertEquals("/api/v1/recipes/veloute/image", sum["imageUrl"])
        assertNull(ApiModels.recipeSummary(r, null)["imageUrl"])

        val detail = ApiModels.recipeDetail(r, null)
        assertEquals(4, detail["machineSteps"])
        assertEquals(2, detail["ingredientCount"])
        assertEquals(7, (detail["steps"] as List<*>).size)
    }

    @Test
    fun infoDescribesTheRobot() {
        val m = ApiModels.info(FakeBackend().device, boardFirmware = 12, recipeCount = 3, recipesRev = 5)
        assertEquals(1, m["api"])
        assertEquals("toque-test", m["id"])
        assertEquals("SKMC 1200", m["model"])
        assertEquals(mapOf("version" to "0.5.6", "build" to 13), m["toque"])
        assertEquals(mapOf("firmware" to 12), m["board"])
        assertEquals(5L, m["recipesRev"])
    }
}
