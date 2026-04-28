package org.bayton.tools.managedconfig

import android.os.Bundle
import androidx.enterprise.feedback.KeyedAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseFeedbackSupportUnitTest {
  @Test
  fun feedbackSignatureIsStableForEquivalentBundles() {
    val first =
      Bundle().apply {
        putString("my_string_key", "value")
        putBundle(
          "my_bundle_key",
          Bundle().apply {
            putString("my_string_key_in_bundle", "nested")
          },
        )
      }
    val second =
      Bundle().apply {
        putBundle(
          "my_bundle_key",
          Bundle().apply {
            putString("my_string_key_in_bundle", "nested")
          },
        )
        putString("my_string_key", "value")
      }

    assertEquals(feedbackSignature(first), feedbackSignature(second))
  }

  @Test
  fun feedbackSignatureChangesWhenNestedContentChanges() {
    val first =
      Bundle().apply {
        putBundle(
          "my_bundle_key",
          Bundle().apply {
            putString("my_string_key_in_bundle", "first")
          },
        )
      }
    val second =
      Bundle().apply {
        putBundle(
          "my_bundle_key",
          Bundle().apply {
            putString("my_string_key_in_bundle", "second")
          },
        )
      }

    assertNotEquals(feedbackSignature(first), feedbackSignature(second))
  }

  @Test
  fun buildManagedConfigFeedbackStatesReportsEmptySummary() {
    val states = buildManagedConfigFeedbackStates(Bundle(), managedConfigDefinitions)
    val summary = states.first { it.key == "managed_config_status" }

    assertEquals(KeyedAppState.SEVERITY_INFO, summary.severity)
    assertEquals("No managed configuration currently set.", summary.message)
    assertEquals("managed_key_count=0", summary.data)
  }

  @Test
  fun buildManagedConfigFeedbackStatesAcceptsValidBundleArray() {
    val bundle =
      Bundle().apply {
        putParcelableArray(
          "my_bundle_array_key",
          arrayOf(
            Bundle().apply {
              putString("my_string_key_in_bundle_array", "item-1")
            },
          ),
        )
      }

    val states = buildManagedConfigFeedbackStates(bundle, managedConfigDefinitions)
    val bundleArrayState = states.first { it.key == "my_bundle_array_key" }

    assertEquals(KeyedAppState.SEVERITY_INFO, bundleArrayState.severity)
    assertEquals("Managed config received and rendered.", bundleArrayState.message)
    assertTrue(bundleArrayState.data?.contains("type=bundle_array") == true)
  }
}
