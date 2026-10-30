package de.ernaehrung.demo

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LocalDataViewModel
import de.ernaehrung.demo.ui.screens.LocalDataContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalDataScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var db: FoodDatabase? = null
    private val models = ViewModelStore()
    @After fun close() { models.clear(); db?.close() }

    @Test fun productFormPersistsUnknownValuesAndPreventsRepeatedSave() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        db = database
        val model = LocalDataViewModel(FoodStore(database))
        models.put("local", model)
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LocalDataContent(model) } }
            }
        }
        rule.waitUntil(15_000) { rule.onAllNodesWithText("0 gespeicherte Produkte · unabhängig vom Bestand").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Produkt speichern / neue Version").performClick()
        rule.onNodeWithText("Produktname · lokal").performScrollTo().performTextInput("Testtofu")
        rule.onNodeWithText("Änderungsgrund").performScrollTo().performTextInput("Erster Test")
        rule.onNodeWithTag("Produkt lokal speichern").performScrollTo().performClick()
        rule.waitUntil(15_000) { model.saveState.value.succeeded }
        rule.onNodeWithTag("Produkt lokal speichern").assertIsNotEnabled()
        runBlocking {
            val rows = database.foodDao().observeCatalog().first()
            assertEquals("Testtofu", rows.single().version.name)
            assertEquals(null, rows.single().version.energyKcal)
            assertEquals(0L, rows.single().stockAmount)
            assertEquals(1, database.foodDao().operationCount())
        }
        rule.onNodeWithTag("Neuen Vorgang beginnen").performScrollTo().performClick()
        rule.onNodeWithTag("Produkt lokal speichern").performScrollTo().assertIsEnabled()
    }
}
