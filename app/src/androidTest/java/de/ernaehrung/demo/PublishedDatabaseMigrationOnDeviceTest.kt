package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

/** A real platform-SQLite upgrade; all records belong to disposable synthetic files. */
@RunWith(AndroidJUnit4::class)
class PublishedDatabaseMigrationOnDeviceTest {
    @Before fun prepareDevice() = prepareUnlockedTestDevice()
    @Test fun publishedV14AndV16UpgradePreserveEveryOldRowAndReplayKeyThenSupportNewConfirmedActions() {
        assertTrue("Synthetic migration fixtures require a disposable emulator",
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(connectivity.activeNetwork)
        // V14 is the previous separate test-app milestone; V16 also carries
        // personal goal/exclusion history before favorites were added in V17.
        for (version in listOf(14, 16)) {
            val schema = instrumentation.context.assets
                .open("de.ernaehrung.demo.data.local.FoodDatabase/$version.json")
                .bufferedReader().use { it.readText() }
            PublishedDatabaseMigrationFixture.verify(target, version, schema)
            Log.i("EG_DEVICE_TEST", "Published Room $version -> 17: all old rows, retry keys, reopen and new actions PASS on API ${Build.VERSION.SDK_INT}")
        }
        assertNull(connectivity.activeNetwork)
    }
}
