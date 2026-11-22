package app.toque.core.api

import app.toque.core.machine.Snapshot
import app.toque.core.recipes.Awaiting
import app.toque.core.recipes.CookSession
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepKind
import app.toque.core.recipes.StepMachine

/** Version de l'API ; les évolutions d'une version sont additives (voir docs/HOME_ASSISTANT_API.md). */
const val API_VERSION = 1

/** Base de toutes les adresses de l'API. */
const val API_BASE = "/api/v1"

/** `status` de `/state`, calculé côté robot. « Indisponible » n'existe pas ici : Home Assistant le déduit. */
object ApiStatus {
    const val STANDBY = "standby"
    const val RUNNING = "running"
    const val RECIPE = "recipe"
    const val FAULT = "fault"

    fun of(s: Snapshot, session: CookSession?): String = when {
        s.hasFault -> FAULT
        session != null -> RECIPE
        s.busy -> RUNNING
        else -> STANDBY
    }
}

/** Identité du robot, telle que la fournit l'appli (adresse et durée de fonctionnement sont lues à chaque appel). */
data class DeviceInfo(
    val id: String,
    val name: String,
    val model: String = "SKMC 1200",
    val toqueVersion: String,
    val toqueBuild: Int,
    val androidRelease: String,
    val androidSdk: Int,
    val ip: String,
    val uptimeS: Long,
    val lang: String,
)

/** Construction des réponses JSON (des `Map` prêtes pour `Json.write`). */
object ApiModels {
    fun info(d: DeviceInfo, boardFirmware: Int, recipeCount: Int, recipesRev: Long): Map<String, Any?> = linkedMapOf(
        "api" to API_VERSION,
        "id" to d.id,
        "name" to d.name,
        "model" to d.model,
        "toque" to linkedMapOf("version" to d.toqueVersion, "build" to d.toqueBuild),
        "board" to linkedMapOf("firmware" to boardFirmware),
        "android" to linkedMapOf("release" to d.androidRelease, "sdk" to d.androidSdk),
        "ip" to d.ip,
        "uptimeS" to d.uptimeS,
        "lang" to d.lang,
        "recipeCount" to recipeCount,
        "recipesRev" to recipesRev,
    )

    fun state(
        s: Snapshot, session: CookSession?, screenOn: Boolean, nowSec: Long, snapshotAgeMs: Long = -1,
    ): Map<String, Any?> = linkedMapOf(
        "ts" to nowSec,
        "snapshotAgeMs" to snapshotAgeMs,
        "status" to ApiStatus.of(s, session),
        "screenOn" to screenOn,
        "link" to s.link.name,
        "fault" to if (s.hasFault) linkedMapOf("code" to s.faultCode, "message" to s.faultMessage) else null,
        "machine" to linkedMapOf(
            "tempC" to s.actualTempC,
            // Ce que Toque demande, pas l'écho de la base (elle ajuste sa consigne et garde la dernière).
            "targetTempC" to s.commandedTempC,
            "rpm" to s.rpm,
            "speed" to s.speedLevel,
            "targetSpeed" to s.targetSpeedLevel,
            "weightG" to if (s.weightValid) s.weightG else null,
            "scaleStable" to s.scaleStable,
            "lidOpen" to s.lidOpen,
            "bowlDetected" to s.bowlDetected,
            "motorOn" to s.motorOn,
            "heaterOn" to s.heating,
        ),
        "job" to if (s.jobOngoing) linkedMapOf(
            "kind" to s.jobKind,
            "label" to s.jobLabel,
            "state" to s.jobState,
            "pauseReason" to s.pauseReason,
            "phase" to if (s.phaseCount > 0) s.phaseIndex + 1 else 0,
            "phaseCount" to s.phaseCount,
            "elapsedS" to s.elapsedMs / 1000,
            "totalS" to s.totalMs / 1000,
            "remainingS" to s.remainingMs / 1000,
            "speed" to s.jobSpeed,
            "reverse" to s.jobReverse,
            "accessory" to s.accessory,
        ) else null,
        "sessionId" to session?.id,
    )

    /**
     * `GET /diagnostics` : ce que la base renvoie, brut, et ce que Toque lui envoie. Pour le développement et la
     * comparaison de versions du robot (le contenu peut évoluer sans changer la version de l'API) : `/state` en
     * donne la lecture corrigée, ici rien n'est interprété.
     */
    fun diagnostics(
        s: Snapshot, nowSec: Long, snapshotAgeMs: Long = -1, connections: List<ConnectionInfo>? = null, maxConnections: Int = 0,
        refusals: RefusalInfo? = null,
    ): Map<String, Any?> = linkedMapOf(
        "ts" to nowSec,
        "snapshotAgeMs" to snapshotAgeMs,
        "server" to linkedMapOf(
            "open" to connections?.size,
            "max" to maxConnections,
            "connections" to connections?.map(::connection),
            "refused" to refusals?.let {
                linkedMapOf("count" to it.count, "lastAgoMs" to it.lastAgoMs, "connections" to it.connections.map(::connection))
            },
        ),
        "base" to linkedMapOf(
            "firmware" to s.firmware,
            "hasSample" to s.hasSample,
            "tempC" to s.actualTempC,
            "targetTempC" to s.targetTempC,
            "rpm" to s.rpm,
            "speed" to s.speedLevel,
            "targetSpeed" to s.targetSpeedLevel,
            "weightG" to s.weightG,
            "scaleStable" to s.scaleStable,
            "scaleState" to s.scaleState,
            "lidOpen" to s.lidOpen,
            "bowlDetected" to s.bowlDetected,
            "motorOn" to s.motorOn,
            "heaterOn" to s.heaterOn,
            "errorCode" to s.boardErrorCode,
            "rxHex" to s.rxHex,
        ),
        "sent" to linkedMapOf(
            "operation" to s.wireOperation,
            "speed" to s.wireSpeed,
            "tempLevel" to s.wireTempLevel,
            "txHex" to s.txHex,
        ),
        "job" to linkedMapOf(
            "ongoing" to s.jobOngoing,
            "state" to s.jobState,
            "tempLevel" to s.jobTempLevel,
            "speed" to s.jobSpeed,
            "reverse" to s.jobReverse,
        ),
        "frames" to linkedMapOf("tx" to s.txFrames, "rx" to s.rxFrames, "invalid" to s.invalidFrames),
    )

    private fun connection(c: ConnectionInfo): Map<String, Any?> =
        linkedMapOf("remote" to c.remote, "ageMs" to c.ageMs, "path" to c.path, "stream" to c.stream)

    /** Texte affiché pour une étape : celui de la recette, ou à défaut le résumé du réglage machine. */
    fun stepText(step: Step): String = step.text.ifBlank { step.machine?.summary ?: "" }

    private fun machine(m: StepMachine): Map<String, Any?> = linkedMapOf(
        "program" to m.program.key,
        "seconds" to m.seconds,
        "speed" to m.speed,
        "tempC" to m.tempC,
        "reverse" to m.reverse,
        "accessory" to m.accessory.name.lowercase(),
        "pulses" to m.pulses,
    )

    private fun step(index: Int, s: Step): Map<String, Any?> = linkedMapOf(
        "index" to index,
        "kind" to if (s.machine == null) "manual" else "machine",
        "text" to stepText(s),
        "machine" to s.machine?.let { machine(it) },
        "weigh" to s.weigh?.let { linkedMapOf("label" to it.label, "grams" to it.grams) },
    )

    private fun manualRef(index: Int?, s: Step?): Map<String, Any?>? =
        if (index == null || s == null) null else linkedMapOf("index" to index, "text" to stepText(s))

    fun session(s: CookSession?): Map<String, Any?> {
        if (s == null) return linkedMapOf("active" to false)
        return linkedMapOf(
            "active" to true,
            "id" to s.id,
            "rev" to s.rev,
            "recipe" to linkedMapOf(
                "id" to s.recipe.id, "title" to s.recipe.title,
                "category" to s.recipe.category, "servings" to s.recipe.servings,
            ),
            "stepIndex" to s.stepIndex,
            "stepCount" to s.stepCount,
            "awaiting" to when (s.awaiting) {
                Awaiting.USER -> "user"
                Awaiting.MACHINE -> "machine"
                null -> null
            },
            "step" to s.step?.let { step(s.stepIndex, it) },
            "lastManual" to manualRef(s.lastManualIndex, s.lastManual),
            "nextManual" to manualRef(s.nextManualIndex, s.nextManual),
            "doneSteps" to s.doneSteps.sorted(),
            "ingredients" to s.recipe.ingredients.map { g ->
                linkedMapOf(
                    "group" to g.title,
                    "items" to g.items.map { linkedMapOf("qty" to it.qty, "unit" to it.unit, "name" to it.name, "note" to it.note) },
                )
            },
            "startedAt" to s.startedAt,
        )
    }

    /** Contenu de l'événement `step`. */
    fun stepEvent(s: CookSession): Map<String, Any?> = linkedMapOf(
        "sessionId" to s.id,
        "index" to s.stepIndex,
        "kind" to when (s.stepKind) {
            StepKind.MANUAL -> "manual"
            StepKind.MACHINE -> "machine"
            null -> null
        },
        "text" to (s.step?.let { stepText(it) } ?: ""),
    )

    fun recipeSummary(r: Recipe, imageUrl: String?): Map<String, Any?> = linkedMapOf(
        "id" to r.id,
        "title" to r.title,
        "category" to r.category,
        "tags" to r.tags,
        "servings" to r.servings,
        "prepMinutes" to r.prepMinutes,
        "totalMinutes" to r.totalMinutes,
        "difficulty" to r.difficulty,
        "rating" to r.rating,
        "ratingCount" to r.ratingCount,
        "favorite" to r.favorite,
        "image" to r.image,
        "imageUrl" to imageUrl,
        "source" to r.source.name.lowercase(),
    )

    fun recipeDetail(r: Recipe, imageUrl: String?): Map<String, Any?> {
        val m = LinkedHashMap(RecipeCodec.toMap(r))
        m["machineSteps"] = r.machineSteps
        m["ingredientCount"] = r.allIngredients.size
        m["imageUrl"] = imageUrl
        return m
    }
}
