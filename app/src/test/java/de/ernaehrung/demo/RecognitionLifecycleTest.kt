package de.ernaehrung.demo

import android.content.Context
import android.net.Uri
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.recognition.*
import de.ernaehrung.demo.ui.LocalDataViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionLifecycleTest {
    private suspend fun withModel(gateway: ProductLookupGateway, block: suspend TestScope.(LocalDataViewModel, FoodDatabase) -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        val owner = ViewModelStore()
        try {
            val model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = LocalDataViewModel(FoodStore(db), gateway) as T
            })[LocalDataViewModel::class.java]
            block(model, db)
        } finally { owner.clear(); db.close(); Dispatchers.resetMain() }
    }
    @Test fun olderUncooperativeLookupCannotOverwriteNewBarcodeOrClearedResult() = runBlocking {
        withModel(ProductLookupGateway { code ->
            if (code == "3017620422003") withContext(NonCancellable) { delay(100) }
            ProductLookup.NotFound(code)
        }) { model, _ ->
            model.lookupProduct("3017620422003"); model.lookupProduct("4006381333931")
            advanceUntilIdle()
            assertEquals("4006381333931", (model.externalLookup.value.result as ProductLookup.NotFound).barcode)
            model.lookupProduct("3017620422003"); model.clearProductLookup(); advanceUntilIdle()
            assertNull(model.externalLookup.value.result); assertFalse(model.externalLookup.value.busy)
        }
    }
    @Test fun invalidBarcodeNeverCallsNetworkAndOfflineFailureDoesNotCreateProductFacts() = runBlocking {
        var calls = 0
        withModel(ProductLookupGateway { calls++; throw java.io.IOException("Offline") }) { model, db ->
            model.lookupProduct("3017620422004"); advanceUntilIdle()
            assertEquals(0, calls); assertNotNull(model.externalLookup.value.error)
            model.lookupProduct("3017620422003"); advanceUntilIdle()
            assertEquals(1, calls); assertTrue(model.externalLookup.value.error!!.contains("Offline"))
            assertTrue(db.foodDao().allVersions().isEmpty()); assertTrue(db.foodDao().purchases().isEmpty())
        }
    }
    @Test fun olderUncooperativeFailureCannotReplaceNewSuccessfulLookupWithStaleError() = runBlocking {
        withModel(ProductLookupGateway { code ->
            if (code == "3017620422003") {
                withContext(NonCancellable) { delay(100) }
                throw java.io.IOException("Alter Verbindungsfehler")
            }
            ProductLookup.NotFound(code)
        }) { model, _ ->
            model.lookupProduct("3017620422003"); model.lookupProduct("4006381333931"); advanceUntilIdle()
            assertEquals("4006381333931", (model.externalLookup.value.result as ProductLookup.NotFound).barcode)
            assertNull(model.externalLookup.value.error)
        }
    }
    @Test fun photoFailuresAlwaysCleanUpAndSuccessfulOcrStillCreatesNoReceiptWithoutSave() = runBlocking {
        withModel(ProductLookupGateway { ProductLookup.NotFound(it) }) { model, db ->
            var cleaned = 0
            val broken = object : PhotoRecognitionGateway {
                override suspend fun barcodes(uri: Uri): List<String> = throw java.io.IOException("Unlesbar")
                override suspend fun receiptText(uri: Uri): PhotoText = throw java.io.IOException("Unlesbar")
            }
            model.analyzePhoto(Uri.parse("content://test/image"), true, "CAMERA_OCR", broken) { cleaned++ }; advanceUntilIdle()
            assertEquals(1, cleaned); assertFalse(model.photo.value.busy); assertTrue(model.photo.value.message!!.contains("Unlesbar"))
            val readable = object : PhotoRecognitionGateway {
                override suspend fun barcodes(uri: Uri) = listOf("4006381333931")
                override suspend fun receiptText(uri: Uri) = PhotoText("TESTMARKT\nSUMME 0,00", "a".repeat(64))
            }
            model.analyzePhoto(Uri.parse("content://test/image"), true, "GALLERY_OCR", readable) { cleaned++ }; advanceUntilIdle()
            assertEquals(2, cleaned); assertEquals("TESTMARKT\nSUMME 0,00", model.photo.value.text!!.text)
            assertTrue(db.foodDao().receiptScans().isEmpty()); assertTrue(db.foodDao().receipts().isEmpty()); assertTrue(db.foodDao().purchases().isEmpty())
        }
    }
}
