package app.toque.core

import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Smart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compare le codec Smart de Toque aux vecteurs « manual » du Monsieur Cuisine Developer SDK
 * (profil MCS mc30 10214015, `conformance-900.json`, MIT), copiés dans
 * core/src/test/resources/smart/sdk-manual-conformance.tsv.
 *
 * Le SDK est issu de l'analyse de l'appli, sans robot (« Physical MCC/MCS : not tested »). Toque est vérifié sur
 * les trames réelles des captures (voir [SmartProtocolTest]). Les deux ne se recouvrent qu'en partie :
 *  - mêmes positions pour la température (octet 10), la vitesse en demi-pas (16), le sens (17), la fonction (19)
 *    et la durée h/min/s (25 à 27) : c'est ce que ce test vérifie sur 300 combinaisons ;
 *  - le SDK ajoute un octet nul en fin de trame (39 octets, longueur 0x26) là où les captures réelles en ont 38
 *    (longueur 0x25). Ses trames de réponse (48 octets, en-tête 02 4F 42 00) ne ressemblent pas non plus aux réponses
 *    réelles (53 octets, en-tête 01 0F 00 00) : elles ne sont donc pas utilisées ici.
 */
class SmartSdkConformanceTest {
    private class Vector(val speed: Int, val celsius: Int, val seconds: Int, val reverse: Boolean, val seq: Int, val sdk: ByteArray)

    private fun vectors(): List<Vector> =
        SmartSdkConformanceTest::class.java.getResourceAsStream("/smart/sdk-manual-conformance.tsv")!!.bufferedReader().use { r ->
            r.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
                val c = it.split('\t')
                Vector(c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3] == "1", c[4].toInt(), Mcc.parseHex(c[5]))
            }
        }

    @Test fun manualCommandsMatchTheSdkExceptForTheTrailingByte() {
        val all = vectors()
        assertEquals(300, all.size)
        for (v in all) {
            val level = Smart.TEMPERATURE_LEVELS.indexOf(v.celsius)
            assertTrue("température inconnue : ${v.celsius}", level >= 0)
            val ours = Smart.buildTx(
                seq = v.seq, temperatureLevel = level, speedHalfSteps = v.speed * 2, reverse = v.reverse,
                function = Smart.FN_MANUAL, hours = v.seconds / 3600, minutes = v.seconds / 60 % 60, seconds = v.seconds % 60,
            )
            val label = "vitesse ${v.speed}, ${v.celsius} °C, ${v.seconds} s, inverse ${v.reverse}, séquence ${v.seq}"
            // Le SDK a un octet de plus (nul) : longueur 0x26 au lieu de 0x25, somme sur un octet de plus.
            assertEquals(label, Smart.TX_SIZE + 1, v.sdk.size)
            assertEquals(label, Smart.TX_L + 1, v.sdk[2].toInt() and 0xFF)
            assertEquals(label, 0, v.sdk[Smart.TX_L].toInt())
            val sdkSum = (1 until v.sdk.size - 1).sumOf { v.sdk[it].toInt() and 0xFF } and 0xFF
            assertEquals("somme du SDK, $label", sdkSum, v.sdk.last().toInt() and 0xFF)
            // Tout le reste (en-tête, séquence, commande, température, vitesse, sens, fonction, durée) est identique.
            for (i in 3 until Smart.TX_L) assertEquals("octet $i, $label", v.sdk[i], ours[i])
            // Le décodeur de Toque relit les mêmes valeurs.
            val back = Smart.decodeTx(ours)
            assertEquals(label, v.speed * 2, back.speedHalfSteps)
            assertEquals(label, level, back.temperatureLevel)
            assertEquals(label, v.seconds, back.hours * 3600 + back.minutes * 60 + back.seconds)
            assertEquals(label, v.reverse, back.reverse)
            assertTrue(label, back.unexpectedBytes.isEmpty())
        }
    }
}
