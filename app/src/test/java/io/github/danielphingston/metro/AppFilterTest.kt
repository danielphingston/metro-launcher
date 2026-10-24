package io.github.danielphingston.metro

import android.content.ComponentName
import android.util.Xml
import io.github.danielphingston.metro.data.parseAppFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader

// ComponentName.unflattenFromString is an Android API; Robolectric provides the real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppFilterTest {
    private fun parse(xml: String) = parseAppFilter(Xml.newPullParser().apply { setInput(StringReader(xml)) })

    @Test
    fun mapsComponentsToDrawables() {
        val map = parse(
            """
            <resources>
              <iconback img1="back"/>
              <item component="ComponentInfo{com.example/com.example.Main}" drawable="example"/>
              <item
                  component="  ComponentInfo{com.other/.Home}  "
                  drawable=" other "/>
            </resources>
            """.trimIndent(),
        )
        assertEquals(
            mapOf(
                ComponentName("com.example", "com.example.Main") to "example",
                ComponentName("com.other", "com.other.Home") to "other",
            ),
            map,
        )
    }

    @Test
    fun skipsMalformedItemsAndKeepsFirstDuplicate() {
        val map = parse(
            """
            <resources>
              <item component="ComponentInfo{broken}" drawable="x"/>
              <item component="ComponentInfo{com.a/.A}"/>
              <item drawable="y"/>
              <item component="ComponentInfo{com.a/.A}" drawable="first"/>
              <item component="ComponentInfo{com.a/.A}" drawable="second"/>
            </resources>
            """.trimIndent(),
        )
        assertEquals(mapOf(ComponentName("com.a", "com.a.A") to "first"), map)
    }
}
