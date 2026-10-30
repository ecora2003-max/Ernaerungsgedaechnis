package de.ernaehrung.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.navigation.*
import de.ernaehrung.demo.ui.DemoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun allScreens(){render(1f,"")}
    @Test @Config(qualifiers="w320dp-h640dp-mdpi") fun smallPhoneLargeText(){render(1.3f,"small_")}
    private fun render(scale:Float,prefix:String){val route=mutableStateOf(Screen.S01)
        rule.runOnUiThread{rule.activity.setContent{DemoTheme{CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,scale)){key(route.value){DemoApp(start=route.value)}}}}}
        Screen.entries.forEach{screen->
            rule.runOnUiThread{route.value=screen};rule.waitForIdle();rule.onNodeWithTag("screen_${screen.name}").assertExists()
            MainTab.entries.forEach{rule.onNodeWithTag("tab_${it.name}").assertIsDisplayed()}
            rule.runOnUiThread{val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
                val f=File("build/reports/demo-screenshots/$prefix${screen.name}.png");f.parentFile!!.mkdirs();f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
            }
        }
    }
}
