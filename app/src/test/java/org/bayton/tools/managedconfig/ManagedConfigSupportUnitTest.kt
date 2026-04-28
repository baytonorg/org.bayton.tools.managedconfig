package org.bayton.tools.managedconfig

import android.os.Bundle
import android.os.Parcelable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagedConfigSupportUnitTest {
  @Test
  fun buildUiStateDoesNotFlagCanonicalBundleArrayAsError() {
    val localBundle =
      Bundle().apply {
        putParcelableArray(
          "my_bundle_array_key",
          arrayOf(
            Bundle().apply {
              putBoolean("my_bool_key_in_bundle_array", true)
            } as Parcelable,
          ),
        )
      }

    val uiState =
      buildUiState(
        effectiveRestrictions = localBundle,
        managedRestrictions = Bundle(),
        rawManagedRestrictions = Bundle(),
        managedRuntimeStructure = RuntimeManagedConfigStructure.EMPTY.label,
        managedShapeHighlights = emptyList(),
        localOverrideJson = """{"my_bundle_array_key":[{"my_bool_key_in_bundle_array":true}]}""",
        localOverrideFormat = OverrideFormat.UNFLATTENED.label,
        localShapeHighlights = listOf("my_bundle_array_key: no item wrapper key"),
        keyedAppStatesStatus = "No keyed app states reported yet",
        keyedAppStatesUpdatedAt = "",
        source = "test",
      )

    val item = uiState.effectiveItems.first { it.key == "my_bundle_array_key" }

    assertFalse(item.effectiveHasSchemaError)
    assertEquals(null, item.effectiveSchemaChipLabel)
    assertEquals(null, item.effectiveSchemaMessage)
  }

  @Test
  fun buildUiStateTreatsExtraEffectiveKeysAsLocalSimulationOnly() {
    val managedBundle =
      Bundle().apply {
        putString("extra_key", "managed")
      }
    val localBundle =
      Bundle().apply {
        putString("extra_key", "local")
      }

    val uiState =
      buildUiState(
        effectiveRestrictions = localBundle,
        managedRestrictions = managedBundle,
        rawManagedRestrictions = managedBundle,
        managedRuntimeStructure = RuntimeManagedConfigStructure.CANONICAL.label,
        managedShapeHighlights = emptyList(),
        localOverrideJson = """{"extra_key":"local"}""",
        localOverrideFormat = OverrideFormat.UNFLATTENED.label,
        localShapeHighlights = emptyList(),
        keyedAppStatesStatus = "No keyed app states reported yet",
        keyedAppStatesUpdatedAt = "",
        source = "test",
      )

    val item = uiState.effectiveItems.first { it.key == "extra_key" }

    assertEquals("Additional local simulation key.", item.description)
  }

  @Test
  fun emptyManagedConfigSignatureSentinelIsDistinctFromRealBundleSignature() {
    val signature = feedbackSignature(Bundle().apply { putString("my_string_key", "value") })

    assertFalse(signature == EMPTY_MANAGED_CONFIG_SIGNATURE)
  }

  @Test
  fun detectOverrideFormatRecognizesEmptyPayload() {
    assertEquals(OverrideFormat.EMPTY, detectOverrideFormat("  "))
    assertEquals(OverrideFormat.EMPTY, detectOverrideFormat("{}"))
  }

  @Test
  fun parseJsonBundleRejectsOversizedPayload() {
    val payload = """{"my_string_key":"${"a".repeat(32_760)}"}"""

    val error = runCatching { parseJsonBundle(payload) }.exceptionOrNull()

    assertTrue(error?.message?.contains("JSON exceeds 32 KB limit.") == true)
  }

  @Test
  fun parseJsonBundleRejectsExcessiveBundleArrayIndex() {
    val payload = """{"my_bundle_array_key[32].my_string_key_in_bundle_array":"too-far"}"""

    val error = runCatching { parseJsonBundle(payload) }.exceptionOrNull()

    assertTrue(error?.message?.contains("Bundle array index exceeds supported maximum.") == true)
  }

  @Test
  fun mergeBundlesReplacesScalarValuesAndPreservesNestedOverrides() {
    val base =
      Bundle().apply {
        putString("my_string_key", "base")
        putBundle(
          "my_bundle_key",
          Bundle().apply {
            putString("my_string_key_in_bundle", "base-bundle")
            putBoolean("my_bool_key_in_bundle", false)
          },
        )
      }
    val override =
      Bundle().apply {
        putString("my_string_key", "override")
        putBundle(
          "my_bundle_key",
          Bundle().apply {
            putString("my_string_key_in_bundle", "override-bundle")
            putBoolean("my_bool_key_in_bundle", true)
          },
        )
      }

    val merged = mergeBundles(base, override)

    assertEquals("override", merged.getString("my_string_key"))
    assertEquals("override-bundle", merged.getBundle("my_bundle_key")?.getString("my_string_key_in_bundle"))
    assertTrue(merged.getBundle("my_bundle_key")?.getBoolean("my_bool_key_in_bundle") == true)
  }

  @Test
  fun parseJsonBundleConvertsFlatArrayValuesToStringArray() {
    val parsed =
      parseJsonBundle(
        """
        {
          "my_multiselect_key": ["one", "two", "three"]
        }
        """.trimIndent(),
      )

    assertArrayEquals(arrayOf("one", "two", "three"), parsed.bundle.getStringArray("my_multiselect_key"))
  }

  @Test
  fun parseJsonBundleCoercesWholeNumberDoublesToInt() {
    val parsed =
      parseJsonBundle(
        """
        {
          "my_integer_key": 37,
          "my_integer_key_double": 37.0,
          "my_integer_key_negative": -2147483648
        }
        """.trimIndent(),
      )

    assertEquals(37, parsed.bundle.getInt("my_integer_key"))
    assertEquals(37, parsed.bundle.getInt("my_integer_key_double"))
    assertEquals(Int.MIN_VALUE, parsed.bundle.getInt("my_integer_key_negative"))
  }

  @Test
  fun parseJsonBundleStoresFractionalDoublesAsString() {
    val parsed =
      parseJsonBundle(
        """
        {
          "my_string_key": 3.14,
          "my_string_key_b": 0.5
        }
        """.trimIndent(),
      )

    assertEquals("3.14", parsed.bundle.getString("my_string_key"))
    assertEquals("0.5", parsed.bundle.getString("my_string_key_b"))
  }

  @Test
  fun parseJsonBundlePreservesLargeIntegersAsLong() {
    val parsed =
      parseJsonBundle("""{"my_long_key": 9999999999}""")

    assertEquals(9_999_999_999L, parsed.bundle.getLong("my_long_key"))
  }

  @Test
  fun normalizeRuntimeManagedConfigLeavesCanonicalBundleArrayUntouched() {
    val raw =
      Bundle().apply {
        putParcelableArray(
          "my_bundle_array_key",
          arrayOf(
            Bundle().apply {
              putString("my_string_key_in_bundle_array", "array-item-1")
              putBoolean("my_bool_key_in_bundle_array", true)
            } as Parcelable,
          ),
        )
      }

    val normalized = normalizeRuntimeManagedConfig(raw)
    val array = normalized.normalizedBundle.parcelableArrayForKey("my_bundle_array_key")
    val firstItem = array?.firstOrNull() as? Bundle

    assertEquals(RuntimeManagedConfigStructure.CANONICAL, normalized.structure)
    assertNotNull(firstItem)
    assertEquals("array-item-1", firstItem?.getString("my_string_key_in_bundle_array"))
    assertTrue(firstItem?.getBoolean("my_bool_key_in_bundle_array") == true)
    assertEquals(
      listOf(
        BundleArrayShapeHighlight(
          parentKey = "my_bundle_array_key",
          wrapperKey = "my_bundle_array_item",
          variant = BundleArrayShapeVariant.DIRECT_ITEM_BUNDLES,
        ),
      ),
      normalized.shapeHighlights,
    )
  }

  @Test
  fun normalizeRuntimeManagedConfigNormalizesFlattenedPathStyleBundleArrayKeys() {
    val raw =
      Bundle().apply {
        putString("my_bundle_array_key[0].my_string_key_in_bundle_array", "array-item-1")
        putBoolean("my_bundle_array_key[0].my_bool_key_in_bundle_array", true)
        putString("my_bundle_array_key[1].my_string_key_in_bundle_array", "array-item-2")
      }

    val normalized = normalizeRuntimeManagedConfig(raw)
    val array = normalized.normalizedBundle.parcelableArrayForKey("my_bundle_array_key")
    val firstItem = array?.getOrNull(0) as? Bundle
    val secondItem = array?.getOrNull(1) as? Bundle

    assertEquals(RuntimeManagedConfigStructure.FLATTENED_PATH_STYLE, normalized.structure)
    assertNotNull(firstItem)
    assertNotNull(secondItem)
    assertEquals("array-item-1", firstItem?.getString("my_string_key_in_bundle_array"))
    assertTrue(firstItem?.getBoolean("my_bool_key_in_bundle_array") == true)
    assertEquals("array-item-2", secondItem?.getString("my_string_key_in_bundle_array"))
    assertEquals(
      BundleArrayShapeVariant.DIRECT_ITEM_BUNDLES,
      normalized.shapeHighlights.single { it.parentKey == "my_bundle_array_key" }.variant,
    )
  }

  @Test
  fun normalizeRuntimeManagedConfigMarksMixedRuntimeStructures() {
    val raw =
      Bundle().apply {
        putBundle(
          "my_bundle_key",
          Bundle().apply {
            putString("my_string_key_in_bundle", "canonical")
          },
        )
        putString("my_bundle_array_key[0].my_string_key_in_bundle_array", "flattened")
      }

    val normalized = normalizeRuntimeManagedConfig(raw)
    val nestedBundle = normalized.normalizedBundle.getBundle("my_bundle_key")
    val array = normalized.normalizedBundle.parcelableArrayForKey("my_bundle_array_key")
    val firstItem = array?.firstOrNull() as? Bundle

    assertEquals(RuntimeManagedConfigStructure.MIXED, normalized.structure)
    assertEquals("canonical", nestedBundle?.getString("my_string_key_in_bundle"))
    assertEquals("flattened", firstItem?.getString("my_string_key_in_bundle_array"))
  }

  @Test
  fun normalizeRuntimeManagedConfigUnwrapsBundleArrayItemWrapperKey() {
    val raw =
      Bundle().apply {
        putParcelableArray(
          "my_bundle_array_key",
          arrayOf(
            Bundle().apply {
              putBundle(
                "my_bundle_array_item",
                Bundle().apply {
                  putString("my_string_key_in_bundle_array", "wrapped-item")
                  putBoolean("my_bool_key_in_bundle_array", true)
                },
              )
            } as Parcelable,
          ),
        )
      }

    val normalized = normalizeRuntimeManagedConfig(raw)
    val array = normalized.normalizedBundle.parcelableArrayForKey("my_bundle_array_key")
    val firstItem = array?.firstOrNull() as? Bundle

    assertEquals("wrapped-item", firstItem?.getString("my_string_key_in_bundle_array"))
    assertTrue(firstItem?.getBoolean("my_bool_key_in_bundle_array") == true)
    assertEquals(
      BundleArrayShapeVariant.WRAPPED_ITEM_BUNDLE_KEY,
      normalized.shapeHighlights.single { it.parentKey == "my_bundle_array_key" }.variant,
    )
  }

  @Test
  fun parseJsonBundleUnwrapsBundleArrayItemWrapperKey() {
    val parsed =
      parseJsonBundle(
        """
        {
          "my_bundle_array_key": [
            {
              "my_bundle_array_item": {
                "my_string_key_in_bundle_array": "wrapped-json-item",
                "my_bool_key_in_bundle_array": true
              }
            }
          ]
        }
        """.trimIndent(),
      )

    val array = parsed.bundle.parcelableArrayForKey("my_bundle_array_key")
    val firstItem = array?.firstOrNull() as? Bundle

    assertEquals("wrapped-json-item", firstItem?.getString("my_string_key_in_bundle_array"))
    assertTrue(firstItem?.getBoolean("my_bool_key_in_bundle_array") == true)
    assertEquals(
      BundleArrayShapeVariant.WRAPPED_ITEM_BUNDLE_KEY,
      parsed.shapeHighlights.single { it.parentKey == "my_bundle_array_key" }.variant,
    )
  }

  @Test
  fun buildUiStateMarksLocalOverrideInactiveWhenThereIsAnError() {
    val uiState =
      buildUiState(
        effectiveRestrictions = Bundle(),
        managedRestrictions = Bundle(),
        rawManagedRestrictions = Bundle(),
        managedRuntimeStructure = RuntimeManagedConfigStructure.EMPTY.label,
        managedShapeHighlights = emptyList(),
        localOverrideJson = """{"my_string_key":"broken"}""",
        localOverrideFormat = OverrideFormat.INVALID.label,
        localShapeHighlights = emptyList(),
        keyedAppStatesStatus = "Failed",
        keyedAppStatesUpdatedAt = "",
        source = "Unit test",
        localOverrideError = "Parse failed",
      )

    assertFalse(uiState.localOverrideActive)
    assertEquals("Parse failed", uiState.localOverrideError)
  }
}
