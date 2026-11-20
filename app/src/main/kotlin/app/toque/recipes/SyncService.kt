package app.toque.recipes

import android.app.AlarmManager
import android.app.IntentService
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.toque.Prefs
import app.toque.ToqueApp
import app.toque.machine.MachineService
import app.toque.ui.ChooserActivity
import app.toque.ui.MainActivity
import java.util.Calendar

/** Synchro nocturne des recettes (03 h 30) depuis monsieur-cuisine.com. */
@Suppress("DEPRECATION")
class SyncService : IntentService("toque-synchro") {
    override fun onHandleIntent(intent: Intent?) {
        val app = ToqueApp.of(this)
        if (!app.prefs.autoSync) return
        if (System.currentTimeMillis() - app.prefs.lastSyncMs < 6 * 3600_000L) return
        OnlineSync(this, app.prefs, app.recipes, app.log).sync()
    }

    companion object {
        fun schedule(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(c, 0, Intent(c, SyncAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        }
    }
}

class SyncAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        c.startService(Intent(c, SyncService::class.java))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val prefs = Prefs(c)
        val chooser = prefs.bootLaunch && prefs.bootChooser
        // Avant de démarrer le processus machine : s'il doit attendre le choix, il le saura.
        prefs.chooserPending = chooser
        SyncService.schedule(c)
        c.startService(Intent(c, MachineService::class.java))
        if (prefs.bootLaunch) {
            // Le robot n'a pas de bouton Accueil : Toque (ou son menu de choix) s'ouvre tout seul au
            // démarrage, puis repasse devant 20 s plus tard au cas où l'appli d'origine (écran d'accueil)
            // se serait ouverte après lui.
            val target = if (chooser) ChooserActivity::class.java else MainActivity::class.java
            val open = Intent(c, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(open)
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val again = if (chooser) {
                // Le menu ne revient que si le choix n'est pas encore fait.
                PendingIntent.getBroadcast(c, 2, Intent(c, ChooserAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            } else {
                PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_UPDATE_CURRENT)
            }
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 20_000, again)
        }
    }
}

/** 20 s après le démarrage : ramène le menu de choix devant, sauf si un choix a été fait entre-temps. */
class ChooserAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (!Prefs(c).chooserPending) return
        c.startActivity(Intent(c, ChooserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
