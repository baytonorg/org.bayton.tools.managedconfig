package org.bayton.tools.managedconfig

import android.content.Intent
import android.os.Bundle
import androidx.enterprise.feedback.KeyedAppState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedConfigSupportInstrumentedTest {
  @Test
  fun nestedAndFlattenedJsonNormalizeToEquivalentBundleValues() {
    val nested =
      parseJsonBundle(
        """
        {
          "my_string_key": "qa-string",
          "my_bundle_key": {
            "my_string_key_in_bundle": "bundle-string"
          },
          "my_bundle_array_key": [
            {
              "my_string_key_in_bundle_array": "array-item-1"
            }
          ]
        }
        """.trimIndent(),
      )

    val flattened =
      parseJsonBundle(
        """
        {
          "my_string_key": "qa-string",
          "my_bundle_key.my_string_key_in_bundle": "bundle-string",
          "my_bundle_array_key[0].my_string_key_in_bundle_array": "array-item-1"
        }
        """.trimIndent(),
      )

    assertEquals(OverrideFormat.UNFLATTENED, nested.format)
    assertEquals(OverrideFormat.FLATTENED, flattened.format)
    assertEquals("qa-string", nested.bundle.getString("my_string_key"))
    assertEquals("qa-string", flattened.bundle.getString("my_string_key"))
    assertEquals(
      nested.bundle.getBundle("my_bundle_key")?.getString("my_string_key_in_bundle"),
      flattened.bundle.getBundle("my_bundle_key")?.getString("my_string_key_in_bundle"),
    )

    val nestedArray = nested.bundle.parcelableArrayForKey("my_bundle_array_key")
    val flattenedArray = flattened.bundle.parcelableArrayForKey("my_bundle_array_key")
    assertEquals(1, nestedArray?.size)
    assertEquals(1, flattenedArray?.size)
    assertEquals(
      (nestedArray?.get(0) as Bundle).getString("my_string_key_in_bundle_array"),
      (flattenedArray?.get(0) as Bundle).getString("my_string_key_in_bundle_array"),
    )
  }

  @Test
  fun mixedFlattenedAndNestedJsonIsRejected() {
    val payload =
      """
      {
        "my_bundle_key.my_string_key_in_bundle": "bundle-string",
        "my_bundle_key": {
          "my_choice_key_in_bundle": "another"
        }
      }
      """.trimIndent()

    val error = runCatching { parseJsonBundle(payload) }.exceptionOrNull()

    assertEquals(OverrideFormat.MIXED_UNSUPPORTED, detectOverrideFormat(payload))
    assertTrue(error?.message?.contains("Mixed flattened and nested JSON") == true)
  }

  @Test
  fun invalidJsonIsSafelyClassified() {
    val payload = """{"my_string_key":"broken"""
    val parsed = parseJsonBundleSafely(payload)

    assertEquals(OverrideFormat.INVALID, parsed.format)
    assertTrue(parsed.bundle.isEmpty)
  }

  @Test
  fun hiddenValuesAreVisibleInKeyAndPayloadFormatting() {
    val bundle =
      Bundle().apply {
        putString("my_hidden_key", "secret-token")
        putString("my_string_key", "visible")
      }

    assertEquals("secret-token", formatValueForDisplay(bundle.getString("my_hidden_key")))
    assertEquals("visible", formatValueForDisplay(bundle.getString("my_string_key")))

    val rendered = formatBundleAsStructuredJson(bundle)
    assertTrue(rendered.contains("\"my_hidden_key\": \"secret-token\""))
    assertTrue(rendered.contains("\"my_string_key\": \"visible\""))
  }

  @Test
  fun feedbackSignatureChangesWhenHiddenValueChanges() {
    val first =
      Bundle().apply {
        putString("my_hidden_key", "first-secret")
      }
    val second =
      Bundle().apply {
        putString("my_hidden_key", "second-secret")
      }

    assertNotEquals(feedbackSignature(first), feedbackSignature(second))
  }

  @Test
  fun localOnlyExtraKeyDoesNotAppearOnManagedItems() {
    val managed = Bundle()
    val effective =
      Bundle().apply {
        putString("local_only_key", "simulated")
      }

    val uiState =
      buildUiState(
        UiStateInputs(
          effectiveRestrictions = effective,
          managedRestrictions = managed,
          rawManagedRestrictions = managed,
          managedRuntimeStructure = RuntimeManagedConfigStructure.EMPTY.label,
          managedShapeHighlights = emptyList(),
          editorJson = """{"local_only_key":"simulated"}""",
          editorFormat = OverrideFormat.UNFLATTENED.label,
          appliedOverrideFormat = OverrideFormat.UNFLATTENED.label,
          localShapeHighlights = emptyList(),
          keyedAppStatesStatus = "Not reported",
          keyedAppStatesUpdatedAt = "",
          source = "Test",
        ),
      )

    assertTrue(uiState.effectiveItems.any { it.key == "local_only_key" })
    assertFalse(uiState.managedItems.any { it.key == "local_only_key" })
  }

  @Test
  fun keyedAppStatesReportPresenceForManagedKeys() {
    val bundle =
      Bundle().apply {
        putBoolean("my_bool_key", true)
        putString("my_string_key", "hello")
      }

    val states = buildManagedConfigFeedbackStates(bundle, managedConfigDefinitions)
    val summary = states.first { it.key == "managed_config_status" }
    val boolState = states.first { it.key == "my_bool_key" }
    val hiddenState = states.first { it.key == "my_hidden_key" }

    assertEquals(KeyedAppState.SEVERITY_INFO, summary.severity)
    assertTrue(summary.message?.contains("Received 2 managed keys") == true)
    assertEquals(KeyedAppState.SEVERITY_INFO, boolState.severity)
    assertEquals("Managed config received and rendered.", boolState.message)
    assertEquals(KeyedAppState.SEVERITY_INFO, hiddenState.severity)
    assertEquals("Managed config not set.", hiddenState.message)
  }

  @Test
  fun keyedAppStatesReportTypeMismatchesAsErrors() {
    val bundle =
      Bundle().apply {
        putString("my_integer_key", "not-an-int")
      }

    val states = buildManagedConfigFeedbackStates(bundle, managedConfigDefinitions)
    val integerState = states.first { it.key == "my_integer_key" }

    assertEquals(KeyedAppState.SEVERITY_ERROR, integerState.severity)
    assertTrue(integerState.message?.contains("Expected integer but received String") == true)
  }

  @Test
  fun keyedAppStatesRejectNonBundleEntriesForBundleArray() {
    val bundle =
      Bundle().apply {
        putParcelableArray("my_bundle_array_key", arrayOf(Intent("android.intent.action.VIEW")))
      }

    val states = buildManagedConfigFeedbackStates(bundle, managedConfigDefinitions)
    val bundleArrayState = states.first { it.key == "my_bundle_array_key" }

    assertEquals(KeyedAppState.SEVERITY_ERROR, bundleArrayState.severity)
    assertTrue(bundleArrayState.message?.contains("Expected bundle_array but received Array") == true)
  }

  @Test
  fun runtimePreviewUsesAndroidStyleBundleFormatting() {
    val bundle =
      Bundle().apply {
        putString("my_string_key", "hello")
        putBundle(
          "my_bundle_key",
          Bundle().apply {
            putString("my_string_key_in_bundle", "nested")
          },
        )
      }

    val rendered = formatBundleRuntimePreview(bundle)

    assertTrue(rendered.contains("my_string_key: hello"))
    assertTrue(rendered.contains("my_bundle_key:"))
    assertTrue(rendered.contains("  my_string_key_in_bundle: nested"))
    assertFalse(rendered.contains("Bundle["))
    assertFalse(rendered.contains("\"my_string_key\""))
  }
}
