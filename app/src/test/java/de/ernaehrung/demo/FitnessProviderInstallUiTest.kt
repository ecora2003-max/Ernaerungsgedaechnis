package de.ernaehrung.demo

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import de.ernaehrung.demo.ui.screens.FitnessScreen
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Compose/controller/Room; synthetic unavailable provider, JVM intent
 * dispatch. Not an actual Store, HC permission or physical Watch acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FitnessProviderInstallUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val models = ViewModelStore()
    private lateinit var db: FoodDatabase
    @After fun close() { models.clear(); if (::db.isInitialized) db.close() }

    @Test fun unavailableProviderStartsNoExternalActionOrImportUntilTheExplicitStoreGesture(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build()
        var reads = 0
        val source = object : FitnessHealthSource {
            override val requiredPermissions = FitnessHealthPolicy.REQUIRED_PERMISSIONS
            override fun availability() = FitnessHealthSource.Availability.INSTALL_OR_UPDATE_REQUIRED
            override suspend fun grantedPermissions() = emptySet<String>()
            override suspend fun readSnapshot(): FitnessHealthSnapshot { reads++; error("No authorized read") }
        }
        val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FitnessViewModel(FoodStore(db), source) as T
        })[FitnessViewModel::class.java]
        rule.runOnUiThread { rule.activity.setContent {
            DemoTheme { Column(Modifier.verticalScroll(rememberScrollState())) { FitnessScreen(model) } }
        } }
        rule.waitUntil(15_000) {
            rule.waitForIdle()
            rule.onAllNodesWithTag("fitness_install_provider").fetchSemanticsNodes().size == 1
        }
        assertNull(shadowOf(rule.activity).nextStartedActivity)
        rule.onNodeWithTag("fitness_opt_in").performScrollTo().performClick()
        rule.onNodeWithTag("fitness_connect").assertIsNotEnabled()
        assertNull(shadowOf(rule.activity).nextStartedActivity)
        rule.onNodeWithTag("fitness_install_provider").performScrollTo().performClick()
        val opened = requireNotNull(shadowOf(rule.activity).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("com.android.vending", opened.getPackage())
        assertEquals("market", opened.data?.scheme)
        assertEquals("com.google.android.apps.healthdata", opened.data?.getQueryParameter("id"))
        assertEquals(context.packageName, opened.getStringExtra("callerId"))
        assertEquals(0, reads)
        assertNull(db.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE))
        assertTrue(db.healthDao().runs(FitnessHealthPolicy.SAMSUNG_PACKAGE).isEmpty())
        assertEquals(0, db.foodDao().operationCount())
    }
}
