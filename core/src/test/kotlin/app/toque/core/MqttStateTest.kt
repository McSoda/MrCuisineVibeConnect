package app.toque.core

import app.toque.core.api.MqttState
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.machine.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttStateTest {
    private fun state(remaining: Long = 270, job: Boolean = true, temp: Int = 45, jobState: String = "RUNNING") = MqttState(
        actualTempC = 40, targetTempC = temp, speedLevel = 2, jobActive = job, jobKind = "MANUAL", jobLabel = "Étapes 2 à 4",
        jobState = jobState, remainingSec = remaining, endReason = "", faultCode = "", faultMessage = "",
    )

    @Test
    fun firstStateIsAlwaysPublished() {
        assertTrue(state().needsPublishAfter(null))
    }

    @Test
    fun theCountdownIsPublishedEveryFiveSecondsOnly() {
        var last = state(remaining = 270)
        var published = 0
        for (r in 269 downTo 200L) {
            val s = state(remaining = r)
            if (s.needsPublishAfter(last)) { published++; last = s }
        }
        // 70 secondes de décompte : 14 messages, pas 70.
        assertEquals(14, published)
    }

    @Test
    fun anyOtherChangeIsPublishedAtOnce() {
        val last = state(remaining = 270)
        assertTrue(state(remaining = 270, temp = 65).needsPublishAfter(last))
        assertTrue(state(remaining = 269, jobState = "PAUSED").needsPublishAfter(last))
        assertTrue(state(remaining = 269, job = false).needsPublishAfter(last))
        assertFalse(state(remaining = 270).needsPublishAfter(last))
        assertFalse(state(remaining = 269).needsPublishAfter(last))
    }

    @Test
    fun theEndAndJumpsOfTheCountdownAreNotDelayed() {
        val last = state(remaining = 3)
        assertTrue(state(remaining = 0).needsPublishAfter(last))        // fin : tombe à 0
        assertTrue(state(remaining = -1).needsPublishAfter(state(remaining = 40)))   // devient inconnu
        assertTrue(state(remaining = 60).needsPublishAfter(state(remaining = 40)))   // temps ajouté
    }

    @Test
    fun valuesAreTheOnesTheJobAsksNotTheBaseEcho() {
        // Au repos, la base renvoie encore sa dernière consigne (67) : MQTT annonce 0, comme l'API.
        val idle = Snapshot(targetTempC = 67, targetSpeedLevel = 0, jobActive = false)
        assertEquals(0, MqttState.from(idle).targetTempC)
        // En travail : la température demandée par Toque (45), pas l'écho (51) ; la vitesse annoncée, pas la mesurée (0).
        val running = Snapshot(
            targetTempC = 51, rpm = 0, speedLevel = 0, targetSpeedLevel = 3, jobActive = true, jobState = "RUNNING", jobTempLevel = 2,
        )
        val s = MqttState.from(running)
        assertEquals(45, s.targetTempC)
        assertEquals(3, s.speedLevel)
        assertEquals(45, (Json.parse(s.toJson()).obj()["targetTempC"] as Number).toInt())
    }
}
