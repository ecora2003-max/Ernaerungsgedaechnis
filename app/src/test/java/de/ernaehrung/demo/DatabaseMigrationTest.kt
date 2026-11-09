package de.ernaehrung.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {
    @Test fun upgradeFromPublishedV1PreservesEveryOldRowAndRetryKeyThenSupportsNewEvents() = upgradeFromPublishedVersion(1)
    @Test fun upgradeFromPublishedV2PreservesAll25TablesAndIntroducesPlanVersioning() = upgradeFromPublishedVersion(2)
    @Test fun upgradeFromPublishedV3Preserves26TablesWithoutGuessingPositionLineageOrExecutingPlans() = upgradeFromPublishedVersion(3)
    @Test fun upgradeFromPublishedV4Preserves28TablesAndLeavesLegacyPackCurrencyAndValidityUnknown() = upgradeFromPublishedVersion(4)
    @Test fun upgradeFromPublishedV5Preserves29TablesAndNeverInfersPaymentOrCompleteness() = upgradeFromPublishedVersion(5)

    @Test fun upgradeFromPublishedV6Preserves32TablesPaidReceiptsBookingsAndExpenseHistory() = upgradeFromPublishedVersion(6)
    @Test fun upgradeFromPublishedV7Preserves35TablesScansAndAliasesWithoutInventingPaidPrices() = upgradeFromPublishedVersion(7)

    @Test fun upgradeFromPublishedV8Preserves36TablesIncludingPrivatePaidPricesAndIntroducesOnlyEmptyHealthTables() = upgradeFromPublishedVersion(8)

    @Test fun upgradeFromPublishedV9PreservesAll39TablesAndExactHealthHistoryWithoutInventingBodyMetadata() = upgradeFromPublishedVersion(9)

    @Test fun upgradeFromPublishedV10PreservesAll41TablesAndBodyReplayPrivacyMarkers() = upgradeFromPublishedVersion(10)

    @Test fun upgradeFromPublishedV11PreservesAll44TablesIncludingSavedOpenAndRetiredTrainingIntents() = upgradeFromPublishedVersion(11)
    @Test fun upgradeFromPublishedV12PreservesAll44TablesIncludingLegacyBodyLinksAndIntroducesOnlyEmptyBatchReviews() = upgradeFromPublishedVersion(12)

    @Test fun upgradeFromPublishedV13PreservesAll45TablesIncludingBatchReviewHistoryAndIntroducesOnlyEmptyCapacityHistory() = upgradeFromPublishedVersion(13)

    @Test fun upgradeFromPublishedV14PreservesAll46TablesAndPhysicalCapacityHistoryWithoutInventingGoalTargets() = upgradeFromPublishedVersion(14)

    @Test fun upgradeFromPublishedV15PreservesAll49TablesExactPersonalGoalsAndAddsOnlyEmptyExclusionHistory() = upgradeFromPublishedVersion(15)

    @Test fun upgradeFromPublishedV16PreservesAll50TablesAndPrivateExclusionsAndAddsOnlyEmptyRecipeFavorites() = upgradeFromPublishedVersion(16)

    @Test fun upgradeFromPublishedV17PreservesAll51TablesIncludingFavoritesAndAddsOnlyEmptyLocationHistory() = upgradeFromPublishedVersion(17)
    @Test fun upgradeFromPublishedV18PreservesAll52TablesAndLeavesNewWorkoutMetricEvidenceUnknown() = upgradeFromPublishedVersion(18)

    private fun upgradeFromPublishedVersion(fromVersion: Int) {
        val schema = javaClass.classLoader!!.getResourceAsStream("de.ernaehrung.demo.data.local.FoodDatabase/$fromVersion.json")!!
            .bufferedReader().use { it.readText() }
        PublishedDatabaseMigrationFixture.verify(ApplicationProvider.getApplicationContext<Context>(), fromVersion, schema)
    }
}
