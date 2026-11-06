package de.ernaehrung.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.FoodDatabase
import org.junit.rules.ExternalResource
import org.robolectric.util.ReflectionHelpers

/** JVM fixture lifecycle, outside the Compose/Activity rule. Robolectric can
 * reuse an SDK sandbox's application singleton between different UI classes.
 * Close and reset only this synthetic workspace before launch and after the
 * Activity and its readers have stopped. Never packaged in a device APK.
 */
internal class IsolatedWorkspaceDatabase : ExternalResource() {
    private lateinit var context: Context
    override fun before() {
        context = ApplicationProvider.getApplicationContext()
        reset()
    }
    override fun after() = reset()
    private fun reset() {
        ReflectionHelpers.getStaticField<FoodDatabase?>(FoodDatabase::class.java, "instance")?.close()
        ReflectionHelpers.setStaticField(FoodDatabase::class.java, "instance", null)
        context.deleteDatabase(FoodDatabase.TEST_DATABASE_NAME)
    }
}
