package de.ernaehrung.demo

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LocalDataViewModel
import de.ernaehrung.demo.ui.screens.LocalDataContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecognitionModeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val models = ViewModelStore()
    private var db: FoodDatabase? = null
    @After fun close() { models.clear(); db?.close() }
    @Test fun defaultWorkspaceRestoresTheChosenReceiptModeAndItsManualFallbackAfterActivityRecreation() {
        compose.onNodeWithTag("workspace_tab_SCAN").performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("recognition_mode_Bon & OCR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recognition_mode_Bon & OCR").performScrollTo().performClick()
        compose.onNodeWithText("Bontext manuell eingeben / einfügen").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("recognition_mode_Bon & OCR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recognition_mode_Bon & OCR").assertIsSelected()
        compose.onNodeWithText("Bontext manuell eingeben / einfügen").assertExists()
    }
    @Test fun receiptModeAndManualFallbackSurviveDisposalOfTheRecognitionEditorWithoutCreatingAnyFacts() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FoodDatabase::class.java).build()
        db = database
        val model = ViewModelProvider(models,object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = LocalDataViewModel(FoodStore(database)) as T
        })[LocalDataViewModel::class.java]
        compose.runOnUiThread { compose.activity.setContent { DemoTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { LocalDataContent(model,initialSection = "Erkennung") }
        } } }
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("recognition_mode_Bon & OCR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recognition_mode_Bon & OCR").performScrollTo().performClick()
        compose.onNodeWithText("Bontext manuell eingeben / einfügen").assertExists()
        compose.onNodeWithTag("local_section_Katalog").performScrollTo().performClick()
        compose.onNodeWithText("Bontext manuell eingeben / einfügen").assertDoesNotExist()
        compose.onNodeWithTag("local_section_Erkennung").performScrollTo().performClick()
        compose.onNodeWithText("Bontext manuell eingeben / einfügen").assertExists()
        compose.onNodeWithTag("recognition_mode_Bon & OCR").assertIsSelected()
        assertEquals(0,database.foodDao().operationCount())
        assertEquals(0,database.foodDao().activeConsumptionCount())
        assertEquals(0,database.foodDao().planningBatches().size)
    }
}
