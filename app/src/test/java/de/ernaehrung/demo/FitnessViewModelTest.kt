package de.ernaehrung.demo

import android.content.Context
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.FitnessViewModel
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
class FitnessViewModelTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var provider: SyntheticHealthSource
    private var models = ViewModelStore()
    private fun model(): FitnessViewModel = ViewModelProvider(models, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FitnessViewModel(store, provider) as T
    })[FitnessViewModel::class.java]
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { HEALTH_AT + 5_000L }
        provider = SyntheticHealthSource()
    }
    @After fun close() { models.clear(); db.close(); Dispatchers.resetMain() }

    @Test fun explicitConnectionSyncStopAndLocalDeletionUseTheRealFoodStoreBoundary() = runBlocking {
        val vm = model()
        withTimeout(10_000) { vm.state.first { it.cache != null } }
        vm.onPermissionResult() // No pending gesture: an unsolicited callback grants nothing.
        vm.onForeground()
        withTimeout(10_000) { vm.state.first { !it.busy && it.availability != null } }
        assertEquals(0, provider.reads.get())
        assertEquals(false, vm.state.value.cache?.state?.enabled)
        vm.connect()
        withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.lastSuccess != null } }
        assertEquals(1, provider.reads.get())
        assertEquals(2, vm.state.value.cache?.records?.size)
        vm.stop()
        withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.state?.enabled == false } }
        vm.onForeground()
        withTimeout(10_000) { vm.state.first { !it.busy } }
        assertEquals(1, provider.reads.get())
        vm.purge(false)
        withTimeout(10_000) { vm.state.first { !it.busy && it.message != null } }
        assertEquals(2, db.healthDao().history(FitnessHealthPolicy.SAMSUNG_PACKAGE).size)
        vm.purge(true)
        withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.records?.isEmpty() == true } }
        assertEquals(0, db.foodDao().operationCount())
        assertTrue(db.foodDao().purchases().isEmpty())
    }

    @Test fun partialOrRestoredPermissionCallbacksCannotReplaceALiveLocalConnectionGesture() = runBlocking {
        var vm = model()
        withTimeout(10_000) { vm.state.first { it.cache != null } }
        provider.permissions = emptySet()
        vm.connect()
        val request = withTimeout(10_000) { vm.state.first { it.awaitingPermission } }
        val id = requireNotNull(request.permissionLaunch)
        assertTrue(vm.consumePermissionLaunch(id)); assertFalse(vm.consumePermissionLaunch(id))
        provider.permissions = setOf("android.permission.health.READ_STEPS")
        vm.onPermissionResult()
        withTimeout(10_000) { vm.state.first { !it.busy && !it.awaitingPermission && it.message != null } }
        assertTrue(db.healthDao().runs(FitnessHealthPolicy.SAMSUNG_PACKAGE).isEmpty())
        vm.connect()
        withTimeout(10_000) { vm.state.first { it.awaitingPermission } }
        models.clear(); models = ViewModelStore(); vm = model()
        provider.permissions = provider.requiredPermissions
        vm.onPermissionResult() // A new process/model has no pending confirmation.
        vm.onForeground()
        withTimeout(10_000) { vm.state.first { !it.busy && it.cache != null && it.availability != null } }
        assertEquals(0, provider.reads.get())
        assertTrue(db.healthDao().runs(FitnessHealthPolicy.SAMSUNG_PACKAGE).isEmpty())
        vm.connect()
        withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.lastSuccess != null } }
        assertEquals(1, provider.reads.get())
    }

    @Test fun pendingIntervalsHaveASuccessNoticeAndLaterCompletionClearsIt(): Unit = runBlocking {
        val pending = healthSteps().copy(endAt = HEALTH_AT + 1_000)
        provider.snapshot = healthSnapshot(records = listOf(healthWorkout(), pending))
        val vm = model()
        withTimeout(10_000) { vm.state.first { it.cache != null } }
        vm.connect()
        val partial = withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.lastSuccess != null } }
        assertNull(partial.cache?.state?.lastErrorCode)
        assertTrue(requireNotNull(partial.message).contains("Noch zurückgestellte Quellintervalle: 1."))
        assertEquals(1, partial.cache?.records?.count { it.visible })
        provider.snapshot = healthSnapshot(HEALTH_AT + 1_000, listOf(healthWorkout(), pending))
        vm.resync()
        val complete = withTimeout(10_000) { vm.state.first { !it.busy && it.cache?.records?.all { r -> r.visible } == true } }
        assertEquals("Samsung-Health-Daten lokal übernommen.", complete.message)
        assertEquals(0, db.foodDao().operationCount())
    }
}
