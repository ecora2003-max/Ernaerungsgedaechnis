package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence as Catalog
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.backup.BackupArchiveCodec
import de.ernaehrung.demo.data.backup.BackupCell
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonalRestrictionRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private var at = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FoodDatabase::class.java).build()
        store = signedRecipeStore(db) { at }
    }
    @After fun close() { db.close() }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected atomic rejection") } catch (_: IllegalArgumentException) { }
    }
    private suspend fun propose(key: String, raw: String, reason: String = "Eigene belegte Vorgabe"): String = requireNotNull(
        store.proposePersonalRestrictions(key,PersonalRestrictionInput(store.readPersonalRestrictions().latest?.id,raw,reason),true).versionId)
    private suspend fun confirm(key: String, id: String) = store.confirmPersonalRestrictions(key,id,store.readPersonalRestrictions().versionBinding(id),true)
    private suspend fun active(key: String, raw: String): String {
        val proposal = propose("$key-propose",raw)
        return requireNotNull(confirm("$key-confirm",proposal).versionId)
    }

    @Test fun originalTextAndInactiveProposalsNeedExactSeparateConfirmationAndNeverBookFood() = runBlocking {
        val food = store.rememberProduct("food",ProductInput("Unverändertes Lebensmittel"))
        val before = db.foodDao().operationCount()
        val raw = "  ALLERGEN:SOY, DIET:VEGAN  "
        val p = propose("p",raw)
        assertNull(store.readPersonalRestrictions().active)
        assertNull(store.readPersonalRestrictions().binding)
        assertEquals(raw,store.readPersonalRestrictions().latest?.rawText)
        val binding = store.readPersonalRestrictions().versionBinding(p)
        val newer = propose("newer","ALLERGEN:SOY")
        reject { store.confirmPersonalRestrictions("stale",p,binding,true) }
        assertNull(db.personalRestrictionDao().run("stale"))
        val confirmed = confirm("confirm",newer)
        assertEquals(confirmed,confirm("confirm",newer))
        assertEquals(setOf("ALLERGEN:SOY"),store.readPersonalRestrictions().tokens)
        assertEquals(confirmed.versionId,store.readPersonalRestrictions().active?.id)
        reject { store.proposePersonalRestrictions("p",PersonalRestrictionInput(null,"DIET:VEGAN","Eigene belegte Vorgabe"),true) }
        assertEquals(before,db.foodDao().operationCount()); assertNotNull(db.foodDao().version(food))
    }

    @Test fun eraseIsCurrentScopeBoundRedactsValuesAndHashesAndOldRetriesCannotResurrectOrEraseNewerValues() = runBlocking {
        val p = propose("p","ALLERGEN:SOY","Privater Originalgrund")
        val bound = store.readPersonalRestrictions().versionBinding(p)
        confirm("confirm",p)
        val old = store.readPersonalRestrictions()
        propose("later","DIET:VEGAN")
        reject { store.erasePersonalRestrictions("stale-erase",old.token,true) }
        val current = store.readPersonalRestrictions()
        val firstBinding = current.binding
        store.erasePersonalRestrictions("erase",current.token,true)
        val erased = store.readPersonalRestrictions()
        assertNull(erased.active); assertTrue(erased.tokens.isEmpty()); assertNotEquals(firstBinding,erased.binding)
        assertTrue(erased.history.all { it.rawText == null && it.reason == null && it.zoneId == null && it.payloadHash == null })
        assertTrue(store.proposePersonalRestrictions("p",PersonalRestrictionInput(null,"ALLERGEN:SOY","Privater Originalgrund"),true).erased)
        assertTrue(store.confirmPersonalRestrictions("confirm",p,bound,true).erased)
        val fresh = active("fresh","DIET:VEGAN")
        assertTrue(store.erasePersonalRestrictions("erase",current.token,true).erased)
        assertEquals(fresh,store.readPersonalRestrictions().active?.id)
        assertEquals(0,db.foodDao().operationCount())
    }

    private suspend fun recipeFixture(): Pair<String,Map<String,Catalog.ConfirmedLine>> {
        val id = store.rememberProduct("rice",ProductInput("Reis",source = Source.USER,quality = Quality.CONFIRMED))
        val v = requireNotNull(db.foodDao().version(id))
        val json = """{"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
          "meta":{"producer":"synthetic","traceId":"private-profile","createdAt":"2026-11-01T04:00:00Z"},
          "payload":{"draftId":"profile-draft","title":"Reis","portionCount":2,
          "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"UNKNOWN",
          "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":false}}],"steps":["Kochen"],
          "flags":{"considerStock":false,"considerShopping":false,"considerOffers":false},"warnings":[],"status":"DRAFT"}}"""
        return json to mapOf("rice" to Catalog.ConfirmedLine("rice",v.id,v.revision,v.recordedAt,at,
            BaseUnit.GRAM,Gate.Basis.NET,Gate.State.RAW,allergens = mapOf("SOY" to false)))
    }
    @Test fun currentProfileCannotBeOmittedAndDeviceScopeIsIndependentOfTheUnchangedExternalV1Digest() = runBlocking {
        val (json,facts) = recipeFixture()
        val states = mapOf("rice" to Gate.State.RAW)
        val request = Gate.Request(Gate.Flags(false,false,false),2,setOf("ALLERGEN:SOY"),true)
        val legacy = store.previewRecipeDraftV1(json,request,facts,states)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE,legacy.status)
        assertEquals(legacy.approvalBasisDigest,legacy.actionScopeDigest)
        store.recordSignedRecipeTestConsent("legacy",json,request,facts,states,legacy,true)
        active("profile","ALLERGEN:SOY")
        for (mask in 0..7) {
            val flags = Gate.Flags(mask and 1 != 0,mask and 2 != 0,mask and 4 != 0)
            val flaggedJson = json.replace("\"considerStock\":false","\"considerStock\":${flags.considerStock}")
                .replace("\"considerShopping\":false","\"considerShopping\":${flags.considerShopping}")
                .replace("\"considerOffers\":false","\"considerOffers\":${flags.considerOffers}")
            val blocked = store.previewRecipeDraftV1(flaggedJson,request.copy(flags = flags,confirmedRestrictions = emptySet()),facts,states)
            assertEquals("context $mask",Gate.Status.BLOCKED,blocked.status)
            assertTrue("context $mask","CONFIRMED_PERSONAL_RESTRICTIONS_MISSING" in blocked.blockers)
            assertNull(blocked.input); assertNull(blocked.approvalBasisDigest)
        }
        var current = store.previewRecipeDraftV1(json,request,facts,states)
        assertEquals(legacy.approvalBasisDigest,current.approvalBasisDigest) // External v1 contract unchanged.
        assertNotEquals(current.approvalBasisDigest,current.actionScopeDigest)
        val inFlight = SignedRecipeTestProof.issue(store,"profile-changed-during-auth",requireNotNull(current.actionScopeDigest))
        val signedScope = current.actionScopeDigest
        active("same-tags-new-version","ALLERGEN:SOY")
        reject { store.recordReviewedRecipeDraftV1Consent("profile-changed-during-auth",json,request,facts,states,current,true,actionProof = inFlight) }
        assertNull(db.foodDao().operation("RECIPE_DRAFT_CONSENT_V1:profile-changed-during-auth"))
        current = store.previewRecipeDraftV1(json,request,facts,states)
        assertEquals(legacy.approvalBasisDigest,current.approvalBasisDigest)
        assertNotEquals(signedScope,current.actionScopeDigest)
        assertNull(store.recordedRecipeDraftV1ConsentKey(requireNotNull(current.approvalBasisDigest)))
        reject { store.saveReviewedRecipeDraftV1("legacy",json,request,facts,states,legacy,true) }
        val wrongScope = SignedRecipeTestProof.issue(store,"wrong-scope",requireNotNull(current.approvalBasisDigest))
        reject { store.recordReviewedRecipeDraftV1Consent("wrong-scope",json,request,facts,states,current,true,actionProof = wrongScope) }
        assertNull(db.foodDao().operation("RECIPE_DRAFT_CONSENT_V1:wrong-scope"))
        store.recordSignedRecipeTestConsent("bound",json,request,facts,states,current,true)
        val recipe = store.saveReviewedRecipeDraftV1("bound",json,request,facts,states,current,true)
        assertNotNull(db.foodDao().recipeVersion(recipe))
        val before = db.foodDao().operationCount()
        store.erasePersonalRestrictions("erase",store.readPersonalRestrictions().token,true)
        assertNull(store.recordedRecipeDraftV1ConsentKey(requireNotNull(current.approvalBasisDigest)))
        reject { store.saveReviewedRecipeDraftV1("bound",json,request,facts,states,current,true) }
        val postErase = store.previewRecipeDraftV1(json,request,facts,states)
        assertEquals(legacy.approvalBasisDigest,postErase.approvalBasisDigest)
        assertNotEquals(legacy.actionScopeDigest,postErase.actionScopeDigest)
        assertNotEquals(current.actionScopeDigest,postErase.actionScopeDigest)
        assertEquals(before,db.foodDao().operationCount())
        assertEquals(0,db.foodDao().activeConsumptionCount()); assertTrue(db.foodDao().planningBatches().isEmpty())
    }

    @Test fun privateBackupScopeIncludesOnlyExplicitlyChosenProfileDataAndPublishedRoom15RetainsItsOriginalRetryDigest() = runBlocking {
        val emptyCurrent = store.captureBackup("de.ernaehrung.demo",true,true)
        val old15 = emptyCurrent.copy(databaseVersion = 15,identityHash = "f0276403a772111cf77efeaf250b4b2b",
            tables = emptyCurrent.tables.filterNot { it.name in setOf("personal_restrictions","recipe_favorites","storage_location_reviews") })
        val digest = BackupArchiveCodec.digest(BackupArchiveCodec.encode(old15))
        val id = active("own","ALLERGEN:SOY")
        assertTrue(store.captureBackup("de.ernaehrung.demo",false,true).tables.single { it.name == "personal_restrictions" }.rows.isEmpty())
        assertTrue(store.captureBackup("de.ernaehrung.demo",true,true).tables.single { it.name == "personal_restrictions" }.rows.isNotEmpty())
        assertEquals(id,store.readPersonalRestrictions().active?.id)
        val target = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FoodDatabase::class.java).build()
        try {
            val destination = FoodStore(target) { at }
            assertTrue(destination.validateBackupForPreview(old15,"de.ernaehrung.demo"))
            destination.restoreBackup("old15",old15,digest,"de.ernaehrung.demo",true)
            assertTrue(destination.readPersonalRestrictions().history.isEmpty())
            val fresh = requireNotNull(destination.proposePersonalRestrictions("fresh",PersonalRestrictionInput(null,"DIET:VEGAN","Neue eigene Vorgabe"),true).versionId)
            destination.restoreBackup("retry",old15,digest,"de.ernaehrung.demo",true)
            assertEquals(fresh,destination.readPersonalRestrictions().latest?.id)
            assertEquals(digest,BackupArchiveCodec.digest(BackupArchiveCodec.encode(old15)))
        } finally { target.close() }
    }

    @Test fun malformedProfileInAnOtherwiseTypedBackupRollsBackAllFoodRowsAndTheRestoreMarker() = runBlocking {
        store.rememberProduct("food",ProductInput("Unverändertes Lebensmittel",source = Source.USER,quality = Quality.CONFIRMED))
        active("profile","ALLERGEN:SOY")
        val valid = store.captureBackup("de.ernaehrung.demo",true,true)
        val malformed = valid.copy(tables = valid.tables.map { table ->
            if (table.name != "personal_restrictions") table else {
                val column = table.columns.indexOf("rawText"); require(column >= 0)
                table.copy(rows = table.rows.map { row -> row.toMutableList().apply { this[column] = BackupCell.Text("unsupported-profile-value") } })
            }
        })
        val target = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FoodDatabase::class.java).build()
        try {
            val destination = FoodStore(target) { at }
            val digest = BackupArchiveCodec.digest(BackupArchiveCodec.encode(malformed))
            reject { destination.restoreBackup("malformed-profile",malformed,digest,"de.ernaehrung.demo",true) }
            assertEquals(0,target.foodDao().operationCount())
            assertNull(target.foodDao().operation("malformed-profile"))
            assertTrue(target.foodDao().allVersions().isEmpty())
            assertTrue(target.personalRestrictionDao().history().isEmpty())
            // The rejected restore did not consume its key. A valid retry must
            // restore the complete private chain, independent of UUID row order.
            val validDigest = BackupArchiveCodec.digest(BackupArchiveCodec.encode(valid))
            destination.restoreBackup("malformed-profile",valid,validDigest,"de.ernaehrung.demo",true)
            assertEquals(store.readPersonalRestrictions().history,destination.readPersonalRestrictions().history)
            assertEquals(setOf("ALLERGEN:SOY"),destination.readPersonalRestrictions().tokens)
            assertNotNull(target.foodDao().operation("food"))
            destination.erasePersonalRestrictions("erase-restored",destination.readPersonalRestrictions().token,true)
            destination.restoreBackup("malformed-profile",valid,validDigest,"de.ernaehrung.demo",true)
            assertNull(destination.readPersonalRestrictions().active)
            assertTrue(destination.readPersonalRestrictions().history.all { it.rawText == null && it.payloadHash == null })
        } finally { target.close() }
    }
}
