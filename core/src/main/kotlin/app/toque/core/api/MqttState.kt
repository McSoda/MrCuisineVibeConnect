package app.toque.core.api

import app.toque.core.json.Json
import app.toque.core.machine.Snapshot
import kotlin.math.abs

/**
 * Sous-ensemble du [Snapshot] utile pour l'automation (programme + fin), sans capteurs/actuateurs.
 *
 * Mêmes valeurs que l'API locale : [targetTempC] est la température que Toque demande (0 hors travail ou sans
 * chauffe), pas l'écho de la base, qui garde sa dernière consigne après le travail et la renvoie différente de la
 * demande (45 → 51, 65 → 72) ; [speedLevel] est le niveau de vitesse que la base annonce avoir reçu, car la vitesse
 * mesurée reste à 0 en marche sur le SKMC 1200.
 */
data class MqttState(
    val actualTempC: Int,
    val targetTempC: Int,
    val speedLevel: Int,
    val jobActive: Boolean,
    val jobKind: String,
    val jobLabel: String,
    val jobState: String,
    val remainingSec: Long,
    val endReason: String,
    val faultCode: String,
    val faultMessage: String,
) {
    fun toJson(): String = Json.write(
        linkedMapOf(
            "actualTempC" to actualTempC,
            "targetTempC" to targetTempC,
            "speedLevel" to speedLevel,
            "jobActive" to jobActive,
            "jobKind" to jobKind,
            "jobLabel" to jobLabel,
            "jobState" to jobState,
            "remainingSec" to remainingSec,
            "endReason" to endReason,
            "faultCode" to faultCode,
            "faultMessage" to faultMessage,
        )
    )

    /**
     * Faut-il publier cet état après [last] (le dernier publié, null au début) ? Tout changement sauf le temps restant
     * part tout de suite ; le temps restant, qui baisse d'une seconde à chaque seconde, ne part que tous les
     * [REMAINING_STEP_S] s (Home Assistant en déduit l'heure de fin), ou dès qu'il saute (temps ajouté), tombe à 0 ou
     * devient inconnu.
     */
    fun needsPublishAfter(last: MqttState?): Boolean {
        if (last == null) return true
        if (copy(remainingSec = 0) != last.copy(remainingSec = 0)) return true
        if (remainingSec == last.remainingSec) return false
        if ((remainingSec <= 0) != (last.remainingSec <= 0)) return true
        return abs(remainingSec - last.remainingSec) >= REMAINING_STEP_S
    }

    companion object {
        const val REMAINING_STEP_S = 5

        fun from(s: Snapshot): MqttState = MqttState(
            actualTempC = s.actualTempC,
            targetTempC = s.commandedTempC,
            speedLevel = s.targetSpeedLevel,
            jobActive = s.jobActive,
            jobKind = s.jobKind,
            jobLabel = s.jobLabel,
            jobState = s.jobState,
            remainingSec = if (s.remainingMs < 0) -1 else (s.remainingMs + 999) / 1000,
            endReason = s.endReason,
            faultCode = s.faultCode,
            faultMessage = s.faultMessage,
        )
    }
}
