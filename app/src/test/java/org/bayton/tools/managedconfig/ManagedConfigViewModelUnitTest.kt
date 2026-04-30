package org.bayton.tools.managedconfig

import android.os.Bundle
import android.content.RestrictionEntry
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagedConfigViewModelUnitTest {
  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun loadEmmPayloadPopulatesEditorWithoutApplyingIt() = runTest(dispatcher) {
    var fetchCount = 0
    val viewModel =
      createViewModel(
        onFetchManagedRestrictions = { fetchCount += 1 },
        fetchResult = ManagedRestrictionsFetchResult(isAvailable = true, bundle = managedBundle()),
      )

    viewModel.initialize()
    viewModel.selectImportedSchemaPackage(TEST_PACKAGE)
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.localOverrideSeededFromEmmPayload)
    assertEquals("", viewModel.uiState.value.localOverrideJson)
    assertFalse(viewModel.uiState.value.localOverrideActive)
    assertEquals(1, fetchCount)

    viewModel.loadEmmPayloadIntoEditor()
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.localOverrideMatchesEmmPayload)
    assertTrue(viewModel.uiState.value.localOverrideUnappliedChanges)
    assertFalse(viewModel.uiState.value.localOverrideActive)
    assertEquals(2, fetchCount)

    viewModel.applyCurrentEditor()

    assertTrue(viewModel.uiState.value.localOverrideActive)
    assertFalse(viewModel.uiState.value.localOverrideUnappliedChanges)
    assertTrue(viewModel.uiState.value.effectivePayload.contains("\"my_bool_key\": true"))
  }

  @Test
  fun invalidApplyKeepsPreviouslyAppliedPayloadActive() = runTest(dispatcher) {
    val viewModel = createViewModel()

    viewModel.updateEditorJson("""{"my_bool_key":true}""")
    viewModel.applyCurrentEditor()
    val previousPayload = viewModel.uiState.value.effectivePayload

    viewModel.updateEditorJson("{")
    viewModel.applyCurrentEditor()

    assertTrue(viewModel.uiState.value.localOverrideActive)
    assertEquals(previousPayload, viewModel.uiState.value.effectivePayload)
    assertNotNull(viewModel.uiState.value.localOverrideError)
    assertTrue(viewModel.uiState.value.localOverrideError?.contains("Applied payload unchanged.") == true)
    assertTrue(viewModel.uiState.value.localOverrideUnappliedChanges)
  }

  @Test
  fun selectingFirstSchemaPreservesExistingEditorAndAppliedPayload() = runTest(dispatcher) {
    val viewModel = createViewModel()

    viewModel.updateEditorJson("""{"my_bool_key":true}""")
    viewModel.applyCurrentEditor()
    viewModel.updateEditorJson("""{"my_string_key":"draft"}""")

    viewModel.initialize()
    viewModel.selectImportedSchemaPackage(TEST_PACKAGE)
    advanceUntilIdle()

    assertEquals("""{"my_string_key":"draft"}""", viewModel.uiState.value.localOverrideJson)
    assertTrue(viewModel.uiState.value.localOverrideActive)
    assertTrue(viewModel.uiState.value.effectivePayload.contains("\"my_bool_key\": true"))
  }

  @Test
  fun debugIntentInjectionUpdatesAppliedPayloadWithoutOverwritingDraft() = runTest(dispatcher) {
    val viewModel = createViewModel()

    viewModel.updateEditorJson("""{"my_string_key":"draft"}""")

    viewModel.applyIncomingManagedConfigJson("""{"my_bool_key":true}""")

    assertEquals("""{"my_string_key":"draft"}""", viewModel.uiState.value.localOverrideJson)
    assertTrue(viewModel.uiState.value.localOverrideActive)
    assertTrue(viewModel.uiState.value.localOverrideUnappliedChanges)
    assertTrue(viewModel.uiState.value.effectivePayload.contains("\"my_bool_key\": true"))
  }

  @Test
  fun loadEmmPayloadPreservesWrappedBundleArrayShapeForLocalValidation() = runTest(dispatcher) {
    val viewModel =
      createViewModel(
        importedSchema = importedBundleArraySchema(),
        fetchResult = ManagedRestrictionsFetchResult(isAvailable = true, bundle = wrappedManagedBundle()),
      )

    viewModel.initialize()
    viewModel.selectImportedSchemaPackage(TEST_PACKAGE)
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.localOverrideJson.isEmpty())

    viewModel.loadEmmPayloadIntoEditor()
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.localOverrideJson.contains("my_bundle_array_item"))

    viewModel.applyCurrentEditor()

    val parentItem = viewModel.uiState.value.localValidationItems.first { it.key == "my_bundle_array_key" }
    assertTrue(parentItem.effectiveHasSchemaError)
    assertEquals("Bundle key present", parentItem.effectiveSchemaChipLabel)
    assertTrue(parentItem.effectiveSchemaMessage?.contains("wrapper key my_bundle_array_item") == true)
  }

  private fun createViewModel(
    onFetchManagedRestrictions: (() -> Unit)? = null,
    importedSchema: List<RestrictionEntry> = emptyList(),
    fetchResult: ManagedRestrictionsFetchResult = ManagedRestrictionsFetchResult(isAvailable = false),
  ): ManagedConfigViewModel =
    ManagedConfigViewModel(
      managedConfigRepository =
        object : ManagedConfigRepository {
          override fun currentRestrictions(): Bundle = Bundle()

          override suspend fun importSchema(packageName: String) = importedSchema

          override suspend fun fetchManagedRestrictions(packageName: String): ManagedRestrictionsFetchResult {
            onFetchManagedRestrictions?.invoke()
            return fetchResult
          }
        },
      installedAppsRepository =
        object : InstalledAppsRepository {
          override suspend fun listApps(): List<InstalledAppOption> = listOf(InstalledAppOption("Test app", TEST_PACKAGE, null))

          override suspend fun findApp(packageName: String): InstalledAppOption? =
            InstalledAppOption("Test app", TEST_PACKAGE, null).takeIf { packageName == TEST_PACKAGE }
        },
      keyedAppStatesPublisher =
        object : KeyedAppStatesPublisher {
          override fun publish(states: Collection<androidx.enterprise.feedback.KeyedAppState>) = Unit
        },
      currentPackageName = TEST_PACKAGE,
      isDebuggable = true,
      savedStateHandle = SavedStateHandle(),
      ioDispatcher = dispatcher,
    )

  private fun managedBundle(): Bundle =
    Bundle().apply {
      putBoolean("my_bool_key", true)
    }

  private fun wrappedManagedBundle(): Bundle =
    Bundle().apply {
      putParcelableArray(
        "my_bundle_array_key",
        arrayOf(
          Bundle().apply {
            putBundle(
              "my_bundle_array_item",
              Bundle().apply {
                putBoolean("my_bool_key_in_bundle_array", true)
                putString("my_string_key_in_bundle_array", "array-item-1")
                putString("my_choice_key_in_bundle_array", "another")
              },
            )
          },
        ),
      )
    }

  private fun importedBundleArraySchema(): List<RestrictionEntry> {
    val boolChild = RestrictionEntry("my_bool_key_in_bundle_array", false).apply { setTitle("Show intro card") }
    val stringChild = RestrictionEntry("my_string_key_in_bundle_array", "").apply { setTitle("Intro text") }
    val itemBundle =
      RestrictionEntry.createBundleEntry(
        "my_bundle_array_item",
        arrayOf(boolChild, stringChild),
      ).apply { setTitle("Bundle array item") }
    return listOf(
      RestrictionEntry.createBundleArrayEntry(
        "my_bundle_array_key",
        arrayOf(itemBundle),
      ).apply { setTitle("My bundle array") },
    )
  }
}

private const val TEST_PACKAGE = "com.example.test"
