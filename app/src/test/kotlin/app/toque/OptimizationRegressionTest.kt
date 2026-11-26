package app.toque

import android.view.View
import android.widget.ImageView
import app.toque.ui.ImageLoader
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.screens.ScaleScreen
import app.toque.update.Updater
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Tests de non-régression pour les correctifs cycle de vie, mémoire et bornes dans `:app`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
class OptimizationRegressionTest {

    @After
    fun tearDown() {
        ScaleScreen.history.clear()
        Updater.onInstallError = null
    }

    @Test
    fun mainActivityForwardsStopAndStartToCurrentScreenAndClearsUpdaterCallback() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        var shown = 0
        var hidden = 0
        val spyScreen = object : Screen(activity) {
            override val title: String = "Spy"
            override fun build(): View = View(c)
            override fun onShow() { shown++ }
            override fun onHide() { hidden++ }
        }

        activity.push(spyScreen)
        assertEquals(1, shown)
        assertEquals(0, hidden)

        Updater.onInstallError = { }
        controller.pause().stop()
        assertEquals("onStop doit appeler onHide sur l'écran courant", 1, hidden)

        controller.restart().start().resume()
        assertEquals("onStart après onStop doit rappeler onShow sur l'écran courant", 2, shown)

        controller.pause().stop().destroy()
        assertEquals(2, hidden)
        assertNull("onDestroy doit libérer Updater.onInstallError", Updater.onInstallError)
    }

    @Test
    fun scaleScreenHistoryIsBoundedAtFiftyEntries() {
        ScaleScreen.history.clear()
        for (i in 1..65) {
            ScaleScreen.recordHistory(ScaleScreen.WeightEntry("12:00", i * 10, "ing-$i"))
        }
        assertEquals(50, ScaleScreen.history.size)
        assertEquals(160, ScaleScreen.history.first().grams)
        assertEquals(650, ScaleScreen.history.last().grams)
    }

    @Test
    fun imageLoaderIgnoresNonPositiveTargetWidthWithoutLooping() {
        val ctx = RuntimeEnvironment.getApplication()
        val view = ImageView(ctx)
        val dummy = File(ctx.cacheDir, "nonexistent.jpg")
        ImageLoader.load(view, dummy, 0)
        ImageLoader.load(view, dummy, -100)
        assertNull(view.drawable)
    }
}
