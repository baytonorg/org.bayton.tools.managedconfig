package org.bayton.tools.managedconfig

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedConfigScreenSmokeTest {
  private lateinit var device: UiDevice
  private var scenario: ActivityScenario<MainActivity>? = null

  @Before
  fun setUp() {
    device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
  }

  @After
  fun tearDown() {
    scenario?.close()
    scenario = null
    device.pressHome()
  }

  @Test
  fun restrictionsManagerPageShowsExpectedDefaultCopy() {
    launchMainActivity()

    waitForAnyText(
      "Managed config received",
      "No managed config received",
    )
    waitForText("Coverage")
  }

  @Test
  fun localSimulationTabIsReachable() {
    launchMainActivity()

    openLocalValidationTab()
    waitForText("Validation input")
  }

  @Test
  fun debugIntentInjectionLaunchesWithoutCrash() {
    val payload =
      """
      {
        "my_string_key": "from-intent-extra",
        "my_bool_key": true
      }
      """.trimIndent()

    launchMainActivity(managedConfigJson = payload)

    waitForAnyText(
      "Managed config received",
      "No managed config received",
    )
  }

  @Test
  fun debugIntentInjectionSupportsWrappedBundleArrayItemsWithoutCrash() {
    val payload =
      """
      {
        "my_bundle_array_key": [
          {
            "my_bundle_array_item": {
              "my_string_key_in_bundle_array": "wrapped-item",
              "my_bool_key_in_bundle_array": true
            }
          }
        ]
      }
      """.trimIndent()

    launchMainActivity(managedConfigJson = payload)

    waitForAnyText(
      "Managed config received",
      "No managed config received",
    )
  }

  private fun openLocalValidationTab() {
    if (device.findObject(By.textContains("Validation input")) != null) return

    val localValidationTab = device.findObject(By.textContains("Local validation"))
    if (localValidationTab != null) {
      clickText("Local validation")
    } else {
      swipeLeft()
    }

    if (device.findObject(By.textContains("Validation input")) == null) {
      swipeLeft()
    }
  }

  private fun swipeLeft() {
    val width = device.displayWidth
    val height = device.displayHeight
    device.swipe(
      (width * 0.85f).toInt(),
      (height * 0.45f).toInt(),
      (width * 0.15f).toInt(),
      (height * 0.45f).toInt(),
      30,
    )
    device.waitForIdle()
  }

  private fun launchMainActivity(managedConfigJson: String? = null) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val intent =
      Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (managedConfigJson != null) {
          putExtra(EXTRA_MANAGED_CONFIG_JSON, managedConfigJson)
        }
      }

    scenario = ActivityScenario.launch(intent)
    device.waitForIdle()
  }

  private fun scrollToText(text: String) {
    repeat(SCROLL_ATTEMPTS) {
      if (device.findObject(By.textContains(text)) != null) return
      if (!scrollOnce(Direction.DOWN, 0.6f)) return
    }
  }

  private fun scrollToTop() {
    repeat(SCROLL_ATTEMPTS) {
      if (!scrollOnce(Direction.UP, 1.0f)) return
    }
  }

  private fun scrollOnce(direction: Direction, percent: Float): Boolean {
    val scrollables = device.findObjects(By.scrollable(true))
    var moved = false
    for (scrollable in scrollables) {
      try {
        scrollable.setGestureMargin(SCROLL_MARGIN_PX)
        if (scrollable.scroll(direction, percent)) {
          moved = true
          break
        }
      } catch (_: androidx.test.uiautomator.StaleObjectException) {
        // recomposed mid-gesture; retry with the next scrollable on next pass
      }
    }
    device.waitForIdle()
    return moved
  }

  private fun clickText(text: String) {
    repeat(3) { attempt ->
      val node = device.wait(Until.findObject(By.textContains(text)), WAIT_TIMEOUT_MS)
      assertNotNull("Expected to find UI text '$text'", node)
      try {
        node!!.click()
        device.waitForIdle()
        return
      } catch (error: androidx.test.uiautomator.StaleObjectException) {
        if (attempt == 2) throw error
        device.waitForIdle()
      }
    }
  }

  private fun waitForText(text: String) {
    scrollToText(text)
    val node = device.wait(Until.findObject(By.textContains(text)), WAIT_TIMEOUT_MS)
    assertNotNull("Expected to find UI text containing '$text'", node)
  }

  private fun waitForAnyText(vararg texts: String) {
    val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      if (texts.any { candidate -> device.findObject(By.textContains(candidate)) != null }) {
        return
      }
      scrollOnce(Direction.DOWN, 0.4f)
      device.waitForIdle()
      Thread.sleep(100)
    }
    org.junit.Assert.fail("Expected to find one of: ${texts.joinToString()}")
  }

  private companion object {
    const val WAIT_TIMEOUT_MS = 5_000L
    const val SCROLL_ATTEMPTS = 8
    const val SCROLL_MARGIN_PX = 200
  }
}
