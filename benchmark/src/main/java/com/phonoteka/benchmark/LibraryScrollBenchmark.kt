package com.phonoteka.benchmark
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Owner manually authenticates/navigates after compilation reset. No PIN/token/auth bypass exists. */
@RunWith(AndroidJUnit4::class)
class LibraryScrollBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Test fun authorizedLibraryScroll() = benchmark.measureRepeated(
        packageName = "com.phonoteka.labcheck", metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(), iterations = 5,
        setupBlock = {
            startActivityAndWait()
            assertTrue("Unlock your own lab app and open the track list within 120s",
                device.wait(Until.hasObject(By.res("track-search")), 120_000))
        },
        measureBlock = {
            val list = device.findObject(By.res("track-list"))
                ?: error("Track list semantics not available; do not benchmark the auth gate")
            list.setGestureMargin(device.displayWidth / 5)
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }
    )
}
