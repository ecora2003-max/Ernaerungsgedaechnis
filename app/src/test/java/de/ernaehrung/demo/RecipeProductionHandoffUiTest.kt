package de.ernaehrung.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.ernaehrung.demo.ui.Fold
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipeProductionHandoffUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedRecipeHandoffExpandsProductionFormOnlyAfterRequest() {
        val requestedVersion = mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                Fold("Tatsächliche Herstellung buchen", openRequest = requestedVersion.value) {
                    Text("Tatsächliche Zutatenmengen")
                }
            }
        }
        compose.onNodeWithText("Tatsächliche Zutatenmengen").assertDoesNotExist()
        compose.runOnUiThread { requestedVersion.value = "saved-recipe-version" }
        compose.waitForIdle()
        compose.onNodeWithText("Tatsächliche Zutatenmengen").assertIsDisplayed()
        compose.onNodeWithText("Tatsächliche Herstellung buchen").performClick()
        compose.onNodeWithText("Tatsächliche Zutatenmengen").assertDoesNotExist()
        compose.runOnUiThread { requestedVersion.value = "saved-recipe-version-again" }
        compose.waitForIdle()
        compose.onNodeWithText("Tatsächliche Zutatenmengen").assertIsDisplayed()
    }
}
