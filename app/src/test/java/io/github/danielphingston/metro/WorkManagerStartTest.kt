package io.github.danielphingston.metro

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WorkManagerStartTest {
    @Test
    fun aWorkManagerDatabaseThatWontOpenIsHandedToAHandlerNotThrown() {
        // Without a handler WorkManager throws from its own thread, on every start.
        assertNotNull(MetroApplication().workManagerConfiguration.initializationExceptionHandler)
    }
}
