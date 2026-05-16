package com.streame.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val baselineRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineRule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 5
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), 8_000)

            // Let home compose and initial rows settle.
            device.waitForIdle(1_000)
            
            // 1. Explore Home Rows deeper to trigger catalog loading and focus stabilization
            repeat(8) {
                device.pressDPadDown()
                device.waitForIdle(200)
            }
            repeat(4) {
                device.pressDPadRight()
                device.waitForIdle(200)
            }
            repeat(4) {
                device.pressDPadLeft()
                device.waitForIdle(200)
            }

            // 2. Sidebar navigation
            repeat(5) { device.pressDPadLeft() } // Ensure sidebar focus
            device.waitForIdle(500)
            device.pressDPadDown()
            device.waitForIdle(100)
            device.pressDPadUp()
            device.waitForIdle(100)
            device.pressDPadRight() // Return to content
            device.waitForIdle(500)

            // 3. Long press for context menu (if a card is focused)
            val focusedObject = device.findObject(By.focused(true))
            focusedObject?.longClick()
            device.waitForIdle(1000)
            device.pressBack() // Close context menu
            device.waitForIdle(500)

            // 4. Enter Detail Screen and return
            device.pressDPadCenter()
            device.waitForIdle(2_000)
            device.pressBack()
            device.waitForIdle(1_000)

            // Mobile scroll simulation
            val scrollable = device.findObject(By.scrollable(true))
            if (scrollable != null) {
                // Scroll down
                scrollable.setGestureMargin(device.displayWidth / 5)
                scrollable.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f)
                device.waitForIdle(500)
                scrollable.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f)
                device.waitForIdle(500)
                
                // Scroll up
                scrollable.scroll(androidx.test.uiautomator.Direction.UP, 0.8f)
                device.waitForIdle(500)
            }
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "com.streame.tv"
    }
}
