package de.ernaehrung.demo

import android.content.Context
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.*
import de.ernaehrung.demo.data.local.AppCalendar
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.ui.LiveOffersViewModel
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual ViewModel, private selection and existing price kernels. All source
 * documents, article reviews and independent permissions are synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveOffersAuthorizationViewModelTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var models = ViewModelStore()
    private val day = AppCalendar.today()
    private var clock = Instant.now()
    private val first = LiveOfferScope(LiveRetailer.LIDL, "DE123", null, null, day)
    private val second = LiveOfferScope(LiveRetailer.KAUFLAND, "DE456", null, null, day)
    @Volatile private var firstGrant = grant(first, "synthetic-lidl")
    private val secondGrant = grant(second, "synthetic-kaufland")
    private val reads = AtomicInteger()
    private val articleReads = AtomicInteger()
    private lateinit var runtime: LiveOfferRuntime
    private fun grant(scope: LiveOfferScope, sourceId: String) = LiveSourceGrant(sourceId, scope.retailer,
        "synthetic-independent-review-v1", day, day, setOf("feed.example.test"), true, 1, scope)
    private fun row(scope: LiveOfferScope, sourceId: String, id: String) = LiveOffer("$sourceId:$id", sourceId,
        scope.retailer, "Synthetischer Artikel $id", id, null, if (id == "a") 199 else 299, null,
        if (id == "a") 500_000 else 1_000_000, OfferMassUnit.GRAM, OfferQuantityBasis.NET,
        LiveOfferKind.PROMOTION, day, day, clock, clock, scope.storeId, null, null,
        LiveOfferApplicability.STORE_CONFIRMED, null, true, "https://feed.example.test/offers")
    private fun source(sourceId: String) = object : AuthorizedOfferSource {
        override val endpoint = URI("https://feed.example.test/offers")
        override suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch {
            reads.incrementAndGet()
            return LiveOfferBatch(sourceId, scope, clock, listOf(row(scope, sourceId, "a"), row(scope, sourceId, "b")))
        }
    }
    private fun model(): LiveOffersViewModel = ViewModelProvider(models, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LiveOffersViewModel(context, runtime, comparison = LiveOfferComparison(
                reviews = LiveArticleReviewRegistry { offer, _, _ ->
                    articleReads.incrementAndGet()
                    LiveArticleReview("synthetic-version-${offer.sourceProductId}", ShoppingComparabilityKernel.Article(
                        "synthetic-reviewed-food", quantity = ShoppingUnitRateKernel.Quantity(
                            if (offer.sourceProductId == "a") 500 else 1000, "g", "NET", 1, true)), null, true, true, true)
                }, articles = ShoppingPriceComparisonGate.ConfirmedArticleRegistry { _, _, _, _ -> true },
                sourceStillPermitted = { scope, offer -> runtime.policy(scope)?.sourceId == offer.sourceId })) as T
    })[LiveOffersViewModel::class.java]
    private suspend fun idle(vm: LiveOffersViewModel) = withTimeout(10_000) { vm.state.first { it.selectionLoaded && !it.busy } }
    private suspend fun load(vm: LiveOffersViewModel, scope: LiveOfferScope) {
        vm.refresh(scope); idle(vm)
        assertEquals(LiveRefreshStatus.LOADED, vm.state.value.result?.status)
    }
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        PrivateOfferSettings.forApp(context).clearSelection()
        runtime = LiveOfferRuntime(mapOf(first.retailer to source(firstGrant.sourceId), second.retailer to source(secondGrant.sourceId)),
            LivePermissionRegistry { when (it) { first -> firstGrant; second -> secondGrant; else -> null } }) { clock }
    }
    @After fun close() { models.clear(); Dispatchers.resetMain() }

    @Test fun changedGrantWithSameSourceIdDiscardsSessionPricesReferenceAndComparisonAndPausesAutomationAcrossReopen(): Unit = runBlocking {
        var vm = model(); idle(vm); load(vm, first)
        vm.rememberReference("synthetic-lidl:a"); vm.compare("synthetic-lidl:a", "synthetic-lidl:b")
        assertTrue(vm.state.value.comparison!!.rateOrder!! > 0)
        vm.setAutomatic(true); idle(vm); assertTrue(vm.state.value.automatic)
        firstGrant = firstGrant.copy(evidenceRef = "synthetic-independent-review-v2")
        vm.automaticTick(); idle(vm)
        assertNull("Old prices cannot acquire a new review with the same source ID", vm.state.value.result)
        assertNull(vm.state.value.reference); assertNull(vm.state.value.comparison)
        assertFalse(vm.state.value.automatic)
        assertFalse(PrivateOfferSettings.forApp(context).selection()!!.automatic)
        assertEquals(1, reads.get())
        models.clear(); models = ViewModelStore(); vm = model(); idle(vm)
        assertFalse(vm.state.value.automatic); assertNull(vm.state.value.result); assertEquals(1, reads.get())
        clock = clock.plusSeconds(2); load(vm, first)
        assertEquals("synthetic-independent-review-v2", vm.state.value.authorizedEvidenceRef)
        assertNull(vm.state.value.reference); assertEquals(2, reads.get())
    }

    @Test fun revokedReferenceGrantCannotParticipateInAComparisonBeforeTheNextTimerButOtherProviderPricesRemain(): Unit = runBlocking {
        val vm = model(); idle(vm); load(vm, first); vm.rememberReference("synthetic-lidl:a")
        vm.selectProvider(); idle(vm); load(vm, second)
        vm.compareWithReference("synthetic-kaufland:b")
        assertTrue(vm.state.value.comparison!!.rateOrder!! > 0)
        val other = vm.state.value.result; val checkedArticles = articleReads.get()
        firstGrant = firstGrant.copy(cachePermitted = false)
        vm.compareWithReference("synthetic-kaufland:b")
        assertNull(vm.state.value.reference); assertNull(vm.state.value.comparison)
        assertEquals(other, vm.state.value.result)
        assertEquals(checkedArticles, articleReads.get()); assertEquals(2, reads.get())
        vm.rememberReference("synthetic-kaufland:b")
        assertEquals(second, vm.state.value.reference!!.scope)
    }

    @Test fun staleButtonCallbacksCannotRememberOrComparePricesUnderAReplacementGrant(): Unit = runBlocking {
        val vm = model(); idle(vm); load(vm, first)
        firstGrant = firstGrant.copy(evidenceRef = "synthetic-independent-review-v2")
        vm.rememberReference("synthetic-lidl:a")
        assertNull(vm.state.value.result); assertNull(vm.state.value.reference)
        clock = clock.plusSeconds(2); load(vm, first)
        val checkedArticles = articleReads.get()
        firstGrant = firstGrant.copy(minimumRefreshSeconds = 2)
        vm.compare("synthetic-lidl:a", "synthetic-lidl:b")
        assertNull(vm.state.value.result); assertNull(vm.state.value.comparison)
        assertEquals(checkedArticles, articleReads.get()); assertEquals(2, reads.get())
    }

    @Test fun permissionChangedAfterTheDurableReadButBeforeMainPublicationCannotRelabelTheOldResponse(): Unit = runBlocking {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val release = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Unit>()
        val journal = MemoryOfferRefreshJournal()
        val supplied = source(firstGrant.sourceId)
        val gated = object : AuthorizedOfferSource {
            override val endpoint = supplied.endpoint
            override suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch {
                release.await()
                return supplied.fetch(scope)
            }
        }
        runtime = LiveOfferRuntime(mapOf(first.retailer to gated), LivePermissionRegistry { firstGrant },
            object : OfferRefreshJournal {
                override fun last(sourceId: String) = journal.last(sourceId)
                override fun record(sourceId: String, attempt: OfferRefreshAttempt) {
                    journal.record(sourceId, attempt)
                    if (!attempt.automaticPaused) delivered.complete(Unit)
                }
            }) { clock }
        val vm = model()
        withTimeout(10_000) { while (!vm.state.value.selectionLoaded) { main.scheduler.runCurrent(); yield() } }
        vm.refresh(first); main.scheduler.runCurrent()
        // Do not let a fast IO worker publish while runCurrent is still draining
        // Main. Only release its reply after that scheduler call has returned.
        release.complete(Unit)
        withTimeout(10_000) { delivered.await() }
        // The read was already accepted on IO, but its Main continuation has
        // not run. Replacing the independent profile must discard that reply.
        firstGrant = firstGrant.copy(evidenceRef = "synthetic-independent-review-v2")
        withTimeout(10_000) { while (vm.state.value.busy) { main.scheduler.runCurrent(); yield() } }
        assertNull(vm.state.value.result); assertNull(vm.state.value.authorizedEvidenceRef)
        assertEquals(1, reads.get())
    }
}
