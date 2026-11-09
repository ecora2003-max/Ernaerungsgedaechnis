package de.ernaehrung.demo

import android.content.Context
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.HealthExtraViewModel
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthExtraViewModelTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var provider: SyntheticExtraSource
    private var models = ViewModelStore()
    private val at = Instant.parse("2025-04-04T14:00:00Z").toEpochMilli()
    private val selection = HealthExtraSelection(setOf(HealthExtraKind.HEART_RATE, HealthExtraKind.BASAL_RATE))
    private fun model(): HealthExtraViewModel = ViewModelProvider(models, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HealthExtraViewModel(store, provider) { at } as T
    })[HealthExtraViewModel::class.java]
    private suspend fun idle(vm: HealthExtraViewModel) = withTimeout(10_000) { vm.state.first { !it.busy && it.cache != null } }
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { at }; provider = SyntheticExtraSource()
    }
    @After fun close() { models.clear(); db.close(); Dispatchers.resetMain() }

    @Test fun explicitTypeSelectionLifecycleAndDeletionUseIndependentPersistentConsent(): Unit = runBlocking {
        val vm = model(); idle(vm)
        vm.onPermissionResult(); vm.onForeground(); idle(vm)
        assertEquals(0, provider.reads); assertTrue(vm.state.value.selection.kinds.isEmpty())
        vm.edit(selection); vm.connect(false); idle(vm)
        assertEquals(0, provider.reads); assertNull(db.healthExtraDao().state())
        vm.connect(true)
        withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.records?.size == 2 } }
        assertEquals(selection, vm.state.value.cache!!.state.selection())
        // An unapproved form edit is not a new persisted read scope.
        vm.edit(HealthExtraSelection(setOf(HealthExtraKind.ACTIVE_ENERGY)))
        vm.onForeground(); idle(vm)
        assertEquals(selection, provider.lastQuery!!.selection)
        vm.stop(); withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.state?.enabled == false } }
        val reads = provider.reads; vm.onForeground(); idle(vm); assertEquals(reads, provider.reads)
        vm.purge(false); idle(vm); assertEquals(2, vm.state.value.cache!!.records.size)
        vm.purge(true); withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.records?.isEmpty() == true } }
        assertNull(db.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE))
        assertEquals(0, db.foodDao().operationCount())
    }

    @Test fun permissionCallbackRequiresLiveGestureAndExactSelectedActualGrants(): Unit = runBlocking {
        var vm = model(); idle(vm)
        provider.grants = emptySet(); vm.edit(selection); vm.connect(true)
        val request = withTimeout(10_000) { vm.state.first { it.awaitingPermission } }
        assertEquals(setOf("android.permission.health.READ_HEART_RATE", "android.permission.health.READ_BASAL_METABOLIC_RATE"), request.requestedPermissions)
        val token = request.permissionLaunch!!
        assertTrue(vm.consumePermissionLaunch(token)); assertFalse(vm.consumePermissionLaunch(token))
        provider.grants = setOf("android.permission.health.READ_HEART_RATE")
        vm.onPermissionResult(); idle(vm)
        assertEquals(0, provider.reads); assertNull(db.healthExtraDao().state())
        vm.connect(true); withTimeout(10_000) { vm.state.first { it.awaitingPermission } }
        models.clear(); models = ViewModelStore(); vm = model(); idle(vm)
        provider.grants = selection.kinds.map { "android.permission.health.${it.permission}" }.toSet()
        vm.onPermissionResult(); vm.onForeground(); idle(vm)
        assertEquals(0, provider.reads); assertNull(db.healthExtraDao().state())
        vm.edit(selection); vm.connect(true)
        withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.lastSuccess != null } }
        assertEquals(1, provider.reads)
    }

    @Test fun olderScopeRequiresHistoryConsentFeatureAndActualGrantWithoutReadingUnsupportedData(): Unit = runBlocking {
        val vm = model(); idle(vm)
        vm.edit(selection.copy(windowDays = 90)); vm.connect(true); idle(vm)
        assertEquals(0, provider.reads)
        assertTrue(vm.state.value.message!!.contains("HEALTH_EXTRA_HISTORY_CONSENT_REQUIRED"))
        vm.edit(selection.copy(windowDays = 90, historyOptIn = true))
        provider.history = false; vm.connect(true); idle(vm)
        assertEquals(0, provider.reads); assertTrue(vm.state.value.message!!.contains("HEALTH_EXTRA_HISTORY_UNAVAILABLE"))
        provider.history = true; provider.grants -= HealthExtraKernel.HISTORY_PERMISSION
        vm.connect(true)
        val pending = withTimeout(10_000) { vm.state.first { it.awaitingPermission } }
        assertTrue(HealthExtraKernel.HISTORY_PERMISSION in pending.requestedPermissions)
        provider.grants += HealthExtraKernel.HISTORY_PERMISSION
        vm.onPermissionResult()
        withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.lastSuccess != null } }
        assertEquals(1, provider.reads); assertTrue(provider.lastQuery!!.needsHistory)
    }
}
