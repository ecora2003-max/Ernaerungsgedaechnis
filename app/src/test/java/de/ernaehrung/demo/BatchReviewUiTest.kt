package de.ernaehrung.demo

import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.local.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchReviewUiTest {
    @get:Rule(order = 0) val database: org.junit.rules.TestRule = IsolatedWorkspaceDatabase()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun finishEditing() {
        compose.runOnUiThread {
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(5_000) {
            Build.VERSION.SDK_INT < 30 || compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
        }
        compose.waitForIdle()
    }
    private fun tap(tag: String, enabled: Boolean = true) {
        finishEditing(); waitTag(tag)
        if (enabled) compose.waitUntil(25_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        if (tag.startsWith("workspace_tab_") || tag == "workspace_back") compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun text(text: String) {
        finishEditing()
        compose.waitUntil(25_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun field(label: String, value: String) = compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)

    @Test fun actualQualityToBatchReviewRequiresNewConfirmationPreservesOriginalsAndSurvivesRestartWithoutInventingMovements() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val dao = db.foodDao()
        val token = "synthetic-batch-review-${UUID.randomUUID()}"
        val today = AppCalendar.today()
        val day = today.toString()
        val tomorrow = today.plusDays(1).toString()
        val v = store.rememberProduct("$token-product", ProductInput("Synthetische Chargenprüfware"))
        val location = store.addLocation("$token-location", "Synthetisches Prüflager", true)
        val batch = store.receive("$token-receive", BatchInput(v, location, 100_000), true)
        val original = dao.originalBatch(batch)!!
        val movements = dao.movements("$token-receive")
        val bodyBefore = db.bodyDao().versions()
        val trainingBefore = db.manualWorkoutDao().versions()
        val healthBefore = db.healthDao().history("com.sec.android.app.shealth")
        val authBefore = db.healthDao().state("com.sec.android.app.shealth")
        val mealsBefore = dao.activeConsumptionCount()
        try {
            val before = dao.operationCount()
            tap("workspace_tab_MORE"); tap("workspace_go_QUALITY"); tap("quality_category_STORAGE")
            tap("quality_open_batch:$batch"); waitTag("batch_review_current_release")
            compose.onNodeWithTag("batch_review_current_release").assertTextEquals("Verwendung ungeklärt")
            assertEquals(before, dao.operationCount())
            tap("batch_review_edit")
            field("Geprüftes Zugangsdatum · optional", day)
            field("Geprüftes MHD · optional", tomorrow)
            field("Geprüftes Verbrauchsdatum · optional", tomorrow)
            field("Geprüfte Lager-/Prüfnotiz · optional", "Geprüfte Testbedingungen")
            field("Grund dieser Chargenprüfung", "Heutige Lagerprüfung")
            text("Geprüfter Verwendungsstatus")
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Ausdrücklich freigegeben").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Ausdrücklich freigegeben").performScrollTo().performClick()
            text("Tatsächliche Bedingungen und Eignung für diese Freigabe geprüft")
            text("Diese genauen Chargenangaben ausdrücklich bestätigen")
            compose.onNodeWithTag("batch_review_save").performScrollTo().assertIsEnabled()
            field("Geprüftes MHD · optional", today.plusDays(2).toString())
            finishEditing()
            compose.onNodeWithTag("batch_review_save").performScrollTo().assertIsNotEnabled()
            field("Geprüftes MHD · optional", tomorrow)
            text("Tatsächliche Bedingungen und Eignung für diese Freigabe geprüft")
            text("Diese genauen Chargenangaben ausdrücklich bestätigen")
            tap("batch_review_save"); waitTag("batch_review_saved")
            compose.waitUntil(25_000) { runBlocking { dao.latestBatchReview(batch)?.revision == 1 } }
            val review = dao.latestBatchReview(batch)!!
            assertEquals(before + 1, dao.operationCount())
            assertEquals(100_000L, dao.balance(batch)); assertEquals(original, dao.originalBatch(batch))
            assertEquals(movements, dao.movements("$token-receive")); assertTrue(dao.movements(review.operationId).isEmpty())
            val input = BatchReviewInput(batch, null, day, tomorrow, tomorrow, null, null, ReleaseStatus.RELEASED,
                "Geprüfte Testbedingungen", "Heutige Lagerprüfung", true)
            assertEquals(review.id, store.reviewBatch(review.operationId, input, true))
            text("Unverändertes Chargenoriginal")
            compose.onNodeWithTag("batch_review_original_best_before").performScrollTo().assertTextEquals("MHD: Unbekannt")
            compose.onNodeWithTag("batch_review_original_release").assertTextEquals("Verwendung ungeklärt")
            tap("workspace_back"); waitTag("quality_count_STORAGE")
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("quality_issue_batch:$batch").fetchSemanticsNodes().isEmpty() }
            tap("workspace_tab_STOCK"); tap("stock_review_$batch"); waitTag("batch_review_current_best_before")
            compose.onNodeWithTag("batch_review_current_best_before").assertTextEquals("MHD: $tomorrow")
            tap("batch_review_edit"); field("Grund dieser Chargenprüfung", "Ungespeicherter Entwurf")
            finishEditing(); compose.activityRule.scenario.recreate(); waitTag("batch_review_current_best_before")
            compose.onAllNodesWithTag("batch_review_save").assertCountEquals(0)
            assertEquals(1, dao.batchReviews(batch).size)
            tap("batch_review_edit")
            store.reviewBatch("$token-other-review", input.copy(expectedPreviousId = review.id, release = ReleaseStatus.BLOCKED,
                reason = "Andere ausdrückliche lokale Prüfung", conditionsConfirmed = false), true)
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Prüfstand geändert oder nicht lesbar. Formular schließen, Charge neu lesen und erneut prüfen.").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("batch_review_save").performScrollTo().assertIsNotEnabled()
            assertEquals(2, dao.batchReviews(batch).size)
            assertEquals(ReleaseStatus.BLOCKED, dao.batch(batch)!!.release)
            assertEquals(100_000L, dao.balance(batch)); assertEquals(original, dao.originalBatch(batch))
            assertEquals(bodyBefore, db.bodyDao().versions()); assertEquals(trainingBefore, db.manualWorkoutDao().versions())
            assertEquals(healthBefore, db.healthDao().history("com.sec.android.app.shealth")); assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
            assertEquals(mealsBefore, dao.activeConsumptionCount())
        } finally {
            if (dao.reversal("$token-receive") == null)
                store.reverse("$token-cleanup", ReversalInput("$token-receive", day, "Synthetische Chargenfixture bereinigen"), true)
        }
    }
}
