package de.ernaehrung.demo

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate
import de.ernaehrung.demo.data.integration.RecipeOfflineDraftGenerator
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.screens.RecipeDraftImportPanel
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Must run on an Android emulator/device, not Robolectric.
 * Test-only JCA attestation is NOT an Android credential gesture; that
 * manual/hardware gate remains open. No production data or network provider.
 */
@RunWith(AndroidJUnit4::class)
class RecipeOnDeviceEndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun offlineComposeProposalCannotBypassManualReviewOrSave() {
        var reviews = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecipeDraftImportPanel(
                            catalog = emptyList(),
                            checkpointStore = object : RecipeDraftEncryptedImportCheckpoint {
                                override fun save(json: String) = Unit
                                override fun load(): String? = null
                                override fun discard() = Unit
                            },
                            evidenceCheckpointStore = object : RecipeDraftEncryptedEvidenceCheckpoint {
                                override fun save(form: RecipeDraftEvidenceForm) = Unit
                                override fun load(): RecipeDraftEvidenceForm? = null
                                override fun discard() = Unit
                            },
                            onInvalidate = {},
                            onDiscardPending = {},
                            onReview = { _, _, _, _ -> reviews++ }
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen")
            .performClick()
        compose.onNodeWithTag("recipe_offline_generate").performScrollTo().performClick()
        compose.onNodeWithText("Entwurf:", substring = true).assertExists()
        compose.onNodeWithTag("recipe_draft_manual_review").assertIsNotEnabled()
        compose.onNodeWithTag("recipe_draft_save_confirmed").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, reviews) }
    }

    @Test fun offlineDraftToAttestedRoomSaveProductionSeparateConsumptionAndRestart() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "recipe-device-e2e-" + UUID.randomUUID() + ".db"
        var db: FoodDatabase? = null
        var clock = System.currentTimeMillis()
        val day = LocalDate.now(ZoneId.of("Europe/Berlin")).toString()
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val verifier = RecipeActionProofVerifier { proof, key, digest, at ->
            RecipeActionProofPayload.fresh(proof, key, digest, at) &&
                runCatching {
                    Signature.getInstance("SHA256withECDSA").run {
                        initVerify(keyPair.public)
                        update(RecipeActionProofPayload.bytes(proof))
                        verify(Base64.getDecoder().decode(proof.signatureBase64))
                    }
                }.getOrDefault(false)
        }
        fun open(): FoodDatabase =
            Room.databaseBuilder(target, FoodDatabase::class.java, databaseName)
                .addMigrations(
                    FoodMigrations.MIGRATION_1_2, FoodMigrations.MIGRATION_2_3,
                    FoodMigrations.MIGRATION_3_4, FoodMigrations.MIGRATION_4_5,
                    FoodMigrations.MIGRATION_5_6, FoodMigrations.MIGRATION_6_7,
                    FoodMigrations.MIGRATION_7_8
                ).build()
        try {
            db = open()
            var store = FoodStore(requireNotNull(db), { clock }, verifier)
            val ingredients = RecipeOfflineDraftGenerator.ingredientsFor(0)
            val versions = ingredients.associate { line ->
                line.foodTypeId to store.rememberProduct(
                    "device-product-" + line.foodTypeId,
                    ProductInput(line.label, source = Source.USER, quality = Quality.CONFIRMED)
                )
            }
            val location = store.addLocation("device-location", "Küche", true)
            val batches = ingredients.associate { line ->
                line.foodTypeId to store.receive(
                    "device-stock-" + line.foodTypeId,
                    BatchInput(versions.getValue(line.foodTypeId), location, 500_000L,
                        day, release = ReleaseStatus.RELEASED), true
                )
            }
            clock += 2_000L
            val json = RecipeOfflineDraftGenerator.generate(
                2, 0, OffsetDateTime.ofInstant(Instant.ofEpochMilli(clock - 1_000L),
                    ZoneOffset.UTC), "device-draft-1"
            )
            val flags = RecipeDraftHostGate.Flags(false, false, false)
            val request = RecipeDraftHostGate.Request(
                flags, 2, setOf("ALLERGEN:SOY"), userConfirmedRecipeSave = true
            )
            val states = ingredients.associate { line ->
                line.foodTypeId to if (line.foodTypeId.startsWith("cooked-"))
                    RecipeDraftHostGate.State.COOKED else RecipeDraftHostGate.State.RAW
            }
            val facts = ingredients.associate { line ->
                val version = requireNotNull(db.foodDao().version(
                    versions.getValue(line.foodTypeId)))
                line.foodTypeId to RecipeCatalogEvidence.ConfirmedLine(
                    line.foodTypeId, version.id, version.revision, version.recordedAt,
                    clock, BaseUnit.GRAM,
                    if (line.basis == "DRAINED") RecipeDraftHostGate.Basis.DRAINED
                    else RecipeDraftHostGate.Basis.NET,
                    states.getValue(line.foodTypeId),
                    allergens = mapOf("SOY" to false)
                )
            }
            val before = db.foodDao().operationCount()
            val review = store.previewRecipeDraftV1(json, request, facts, states)
            assertEquals(RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE, review.status)
            assertNotNull(review.input)
            assertEquals(before, db.foodDao().operationCount())
            assertEquals(0, db.foodDao().consumptionCount())
            val key = "device-recipe-save"
            assertTrue(runCatching {
                store.saveReviewedRecipeDraftV1(key, json, request, facts, states,
                    review, true, requireActionAttestation = true)
            }.isFailure)
            val digest = requireNotNull(review.approvalBasisDigest)
            val nonce = store.beginRecipeDraftCredentialAction(key, digest)
            val unsigned = RecipeActionProof(key, digest, clock, nonce, "")
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(RecipeActionProofPayload.bytes(unsigned))
                sign()
            }
            val proof = unsigned.copy(
                signatureBase64 = Base64.getEncoder().encodeToString(signature)
            )
            assertEquals(digest, store.recordReviewedRecipeDraftV1Consent(
                key, json, request, facts, states, review, true, actionProof = proof
            ))
            val recipe = store.saveReviewedRecipeDraftV1(
                key, json, request, facts, states, review, true,
                requireActionAttestation = true
            )
            assertNotNull(db.foodDao().recipeVersion(recipe))
            assertEquals(0, db.foodDao().consumptionCount())
            val allocations = ingredients.map { line ->
                Allocation(batches.getValue(line.foodTypeId),
                    line.gramsPerPortion * 2_000L)
            }
            val production = ProductionInput(
                recipe, 2_000L, allocations, location, day, true,
                "Kühlplatz und Behälter ausdrücklich geprüft",
                release = ReleaseStatus.RELEASED
            )
            assertTrue(runCatching {
                store.produce("device-cook-unconfirmed", production, false)
            }.isFailure)
            val output = store.produce("device-cook", production, true)
            assertEquals(0, db.foodDao().consumptionCount())
            val outputVersion = requireNotNull(db.foodDao().batch(output)).versionId
            val meal = ConsumptionInput(
                outputVersion, day, "Mittag", 1_000L,
                listOf(Allocation(output, 1_000L))
            )
            assertTrue(runCatching {
                store.consume("device-eat-unconfirmed", meal, false)
            }.isFailure)
            store.consume("device-eat", meal, true)
            assertEquals(1, db.foodDao().activeConsumptionCount())
            assertEquals(1_000L, db.foodDao().balance(output))
            assertTrue(db.foodDao().allPaidPrices().isEmpty())

            // Reopen the real on-device SQLite database: no destructive migration,
            // no duplicate recipe, production or consumption on idempotent retry.
            db.close()
            db = open()
            store = FoodStore(requireNotNull(db), { clock }, verifier)
            assertEquals(recipe, store.saveReviewedRecipeDraftV1(
                key, json, request, facts, states, review, true,
                requireActionAttestation = true
            ))
            assertEquals(output, store.produce("device-cook", production, true))
            store.consume("device-eat", meal, true)
            assertEquals(1, db.foodDao().activeConsumptionCount())
            assertEquals(1_000L, db.foodDao().balance(output))
            assertEquals(1, db.foodDao().observeRecipes().let {
                kotlinx.coroutines.flow.first(it)
            }.size)
            assertEquals(0, target.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        } finally {
            db?.close()
            target.deleteDatabase(databaseName)
        }
    }
}
