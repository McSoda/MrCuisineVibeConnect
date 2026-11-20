package app.toque

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Connexions HTTPS qui font confiance aux certificats d'Android **et** à quelques autorités
 * racine récentes (assets/certs/roots.pem : DigiCert Global Root G2 et G3, ISRG Root X1, Microsoft RSA 2017).
 *
 * Le magasin d'Android 6 du robot date de 2015 et ne les contient pas : sans elles, le téléchargement
 * d'une version depuis GitHub (hôte release-assets.githubusercontent.com) échoue en « Trust anchor not found ».
 * Les racines ajoutées s'ajoutent à celles du système, elles ne les remplacent pas.
 */
object Tls {
    @Volatile private var factory: SSLSocketFactory? = null

    /** À appeler une fois au démarrage ; sans cela (ou en cas d'échec), les certificats du système seuls servent. */
    fun init(context: Context, log: Logbook? = null) {
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val extra = context.assets.open("certs/roots.pem").use { cf.generateCertificates(it) }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            // Les racines du système d'abord (en copie), puis les nôtres.
            var n = 0
            for (tm in system.trustManagers) {
                if (tm is javax.net.ssl.X509TrustManager) for (c in tm.acceptedIssuers) store.setCertificateEntry("sys${n++}", c)
            }
            for ((i, c) in extra.withIndex()) store.setCertificateEntry("toque$i", c)
            val merged = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            factory = SSLContext.getInstance("TLS").apply { init(null, merged.trustManagers, null) }.socketFactory
        } catch (e: Exception) {
            log?.write("réseau", "racines supplémentaires indisponibles : $e")
        }
    }

    /** Ouvre l'URL ; en HTTPS, avec les racines supplémentaires si elles sont chargées. */
    @Throws(IOException::class)
    fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        val f = factory
        if (c is HttpsURLConnection && f != null) c.sslSocketFactory = f
        return c
    }
}
