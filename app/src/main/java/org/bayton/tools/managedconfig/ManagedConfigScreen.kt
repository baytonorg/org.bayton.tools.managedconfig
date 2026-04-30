package org.bayton.tools.managedconfig

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.bayton.tools.managedconfig.theme.BaytonManagedConfigTheme
import org.bayton.tools.managedconfig.theme.BaytonGreen
import kotlinx.coroutines.launch
import android.widget.ImageView

private const val RESTRICTIONS_MANAGER_TAB = 0
private const val LOCAL_SIMULATION_TAB = 1

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ManagedConfigScreen(
  uiState: ManagedConfigUiState,
  knownConfigs: List<ManagedConfigDefinition>,
  onEditorValueChange: (String) -> Unit,
  onApplyLocalOverride: () -> Unit,
  onClearLocalOverride: () -> Unit,
  onLoadSampleOverride: () -> Unit,
  onLoadInvalidSampleOverride: () -> Unit,
  onLoadEmmPayload: () -> Unit,
  onSelectImportedSchemaPackage: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val pagerState = rememberPagerState(initialPage = RESTRICTIONS_MANAGER_TAB, pageCount = { 2 })
  val coroutineScope = rememberCoroutineScope()

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface),
  ) {
      PrimaryTabRow(
        selectedTabIndex = pagerState.currentPage,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onBackground,
      ) {
        Tab(
          selected = pagerState.currentPage == RESTRICTIONS_MANAGER_TAB,
          onClick = {
            coroutineScope.launch {
              pagerState.animateScrollToPage(RESTRICTIONS_MANAGER_TAB)
            }
          },
          text = { Text("EMM validation") },
        )
        Tab(
          selected = pagerState.currentPage == LOCAL_SIMULATION_TAB,
          onClick = {
            coroutineScope.launch {
              pagerState.animateScrollToPage(LOCAL_SIMULATION_TAB)
            }
          },
          text = { Text("Local validation") },
        )
      }

      HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
      ) { page ->
        when (page) {
          RESTRICTIONS_MANAGER_TAB ->
            RestrictionsManagerPage(
              uiState = uiState,
              knownConfigs = knownConfigs,
            )

          LOCAL_SIMULATION_TAB ->
            LocalSimulationPage(
              uiState = uiState,
              onEditorValueChange = onEditorValueChange,
              onApplyLocalOverride = onApplyLocalOverride,
              onClearLocalOverride = onClearLocalOverride,
              onLoadSampleOverride = onLoadSampleOverride,
              onLoadInvalidSampleOverride = onLoadInvalidSampleOverride,
              onLoadEmmPayload = onLoadEmmPayload,
              onSelectImportedSchemaPackage = onSelectImportedSchemaPackage,
            )

          else -> error("Unsupported page index")
        }
      }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValidationSchemaPickerCard(
  importableAppsLoading: Boolean,
  importedSchemaLoading: Boolean,
  importableApps: List<InstalledAppOption>,
  selectedImportedAppPackage: String?,
  onSelectImportedSchemaPackage: (String) -> Unit,
) {
  var filter by remember(importableApps) { mutableStateOf("") }
  var expanded by remember { mutableStateOf(false) }
  val selectedApp = importableApps.firstOrNull { it.packageName == selectedImportedAppPackage }
  val filteredApps =
    importableApps.filter { app ->
      filter.isBlank() ||
        app.label.contains(filter, ignoreCase = true) ||
        app.packageName.contains(filter, ignoreCase = true)
    }.take(8)

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Validation target",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = "Select an installed app to import its manifest-declared managed-config schema. The local JSON below will be validated and rendered against that schema.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      if (importableAppsLoading) {
        Text(
          text = "Loading installed apps…",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      } else if (importedSchemaLoading) {
        Text(
          text = "Loading selected app schema…",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      if (selectedApp != null) {
        DashboardLine(
          label = "Selected",
          value = "${selectedApp.label} (${selectedApp.packageName})",
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
          if (!importableAppsLoading && !importedSchemaLoading) {
            expanded = !expanded
          }
        },
      ) {
        OutlinedTextField(
          value = filter,
          onValueChange = {
            filter = it
            expanded = true
          },
          modifier =
            Modifier
              .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
              .fillMaxWidth(),
          label = { Text("Search apps") },
          placeholder = { Text("Type app name or package") },
          singleLine = true,
          enabled = !importableAppsLoading && !importedSchemaLoading,
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
          colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
          expanded = expanded && filteredApps.isNotEmpty(),
          onDismissRequest = { expanded = false },
          modifier =
            Modifier.background(
              color = MaterialTheme.colorScheme.surface,
              shape = RoundedCornerShape(16.dp),
            ),
        ) {
          filteredApps.forEach { app ->
            DropdownMenuItem(
              text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                  )
                  Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              },
              onClick = {
                filter = app.label
                expanded = false
                onSelectImportedSchemaPackage(app.packageName)
              },
              leadingIcon = {
                app.icon?.let { drawable ->
                  AndroidView(
                    factory = { context ->
                      ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(drawable)
                      }
                    },
                    update = { imageView ->
                      imageView.setImageDrawable(drawable)
                    },
                    modifier = Modifier.size(28.dp),
                  )
                }
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun RestrictionsManagerPage(
  uiState: ManagedConfigUiState,
  knownConfigs: List<ManagedConfigDefinition>,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      PageIntroCard(
        title = "EMM validation",
        body = "Review the live RestrictionsManager payload, inspect the delivered Bundle / Parcelable structure, and confirm keyed app states were sent to the enterprise feedback channel.",
      )
    }

    item {
      RestrictionsManagerStatusCard(
        hasManagedConfig = uiState.hasManagedConfig,
        managedConfiguredCount = uiState.managedConfiguredCount,
        supportedCount = knownConfigs.size,
        managedPayloadFormat = uiState.managedPayloadFormat,
        managedShapeHighlights = uiState.managedShapeHighlights,
        keyedAppStatesStatus = uiState.keyedAppStatesStatus,
        keyedAppStatesUpdatedAt = uiState.keyedAppStatesUpdatedAt,
        source = uiState.source,
        updatedAt = uiState.updatedAt,
      )
    }

    items(uiState.managedItems, key = { item -> item.key }) { item ->
      ManagedConfigCard(item = item)
    }

    item {
      PayloadCard(
        title = "Managed runtime Bundle / Parcelable preview",
        subtitle = "Raw delivered runtime structure before normalization.",
        rawPayload = uiState.managedRuntimePayload,
      )
    }

    item {
      PayloadCard(
        title = "Reconstructed JSON payload",
        subtitle = "Reconstructed JSON payload aligned with Google best practices. May not match received config exactly. Review this version against the runtime Bundle / Parcelable preview above.",
        rawPayload = uiState.managedPayload,
        highlighted = uiState.managedPayloadNormalizedDifference,
      )
    }
  }
}

@Composable
private fun LocalSimulationPage(
  uiState: ManagedConfigUiState,
  onEditorValueChange: (String) -> Unit,
  onApplyLocalOverride: () -> Unit,
  onClearLocalOverride: () -> Unit,
  onLoadSampleOverride: () -> Unit,
  onLoadInvalidSampleOverride: () -> Unit,
  onLoadEmmPayload: () -> Unit,
  onSelectImportedSchemaPackage: (String) -> Unit,
) {
  val listState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val showScrollToTop by remember {
    derivedStateOf {
      listState.firstVisibleItemIndex > 1 || listState.firstVisibleItemScrollOffset > 200
    }
  }

  Box(
    modifier =
      Modifier
        .fillMaxSize(),
  ) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        PageIntroCard(
          title = "Local validation",
          body = "Import an installed app schema, apply local JSON, and compare how different JSON shapes reconstruct into Android-style Bundle / Parcelable data independent of an EMM. With delegated scopes applied, the EMM-derived configuration for an app will also be available for import.",
        )
      }

      item {
        LocalSimulationStatusCard(
          localOverrideActive = uiState.localOverrideActive,
          localAppliedFormat = uiState.localAppliedFormat,
          importableAppsLoading = uiState.importableAppsLoading,
          importedSchemaLoading = uiState.importedSchemaLoading,
          selectedImportedAppLabel = uiState.selectedImportedAppLabel,
          selectedImportedAppPackage = uiState.selectedImportedAppPackage,
          configuredCount = uiState.localValidationConfiguredCount,
          supportedCount = uiState.localValidationSupportedCount,
          importedSchemaError = uiState.importedSchemaError,
          updatedAt = uiState.updatedAt,
        )
      }

      item {
        ValidationSchemaPickerCard(
          importableAppsLoading = uiState.importableAppsLoading,
          importedSchemaLoading = uiState.importedSchemaLoading,
          importableApps = uiState.importableApps,
          selectedImportedAppPackage = uiState.selectedImportedAppPackage,
          onSelectImportedSchemaPackage = onSelectImportedSchemaPackage,
        )
      }

      item {
        LocalOverrideCard(
          editorValue = uiState.localOverrideJson,
          onEditorValueChange = onEditorValueChange,
          onApplyLocalOverride = onApplyLocalOverride,
          onClearLocalOverride = onClearLocalOverride,
          onLoadSampleOverride = onLoadSampleOverride,
          onLoadInvalidSampleOverride = onLoadInvalidSampleOverride,
          onLoadEmmPayload = onLoadEmmPayload,
          seedJson = uiState.localOverrideSeedJson,
          seededFromEmmPayload = uiState.localOverrideSeededFromEmmPayload,
          editorMatchesEmmPayload = uiState.localOverrideMatchesEmmPayload,
          localOverrideFormat = uiState.localOverrideFormat,
          hasUnappliedChanges = uiState.localOverrideUnappliedChanges,
          error = uiState.localOverrideError,
        )
      }

      if (uiState.selectedImportedAppPackage == null && !uiState.importableAppsLoading && !uiState.importedSchemaLoading) {
        item {
          EmptyValidationTargetCard()
        }
      } else {
        items(uiState.localValidationItems, key = { item -> item.key }) { item ->
          EffectiveConfigCard(item = item)
        }
      }

      item {
        PayloadCard(title = "Local applied Bundle / Parcelable preview", rawPayload = uiState.effectiveRuntimePayload)
      }

      item {
        PayloadCard(
          title = "Reconstructed JSON payload",
          subtitle = "Reconstructed JSON payload aligned with Google best practices. May not match submitted JSON. If your configuration errors above, review this version for differences.",
          rawPayload = uiState.effectivePayload,
          highlighted = uiState.effectivePayloadNormalizedDifference,
        )
      }
    }

    if (showScrollToTop) {
      FloatingActionButton(
        onClick = {
          coroutineScope.launch {
            listState.animateScrollToItem(0)
          }
        },
        modifier =
          Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp)
            .size(44.dp),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      ) {
        Icon(
          imageVector = Icons.Filled.KeyboardArrowUp,
          contentDescription = "Scroll to top",
        )
      }
    }
  }
}

@Composable
private fun RestrictionsManagerStatusCard(
  hasManagedConfig: Boolean,
  managedConfiguredCount: Int,
  supportedCount: Int,
  managedPayloadFormat: String,
  managedShapeHighlights: List<String>,
  keyedAppStatesStatus: String,
  keyedAppStatesUpdatedAt: String,
  source: String,
  updatedAt: String,
) {
  val containerColor =
    if (hasManagedConfig) {
      MaterialTheme.colorScheme.secondary
    } else {
      MaterialTheme.colorScheme.primaryContainer
    }
  val contentColor =
    if (hasManagedConfig) {
      Color.White
    } else {
      MaterialTheme.colorScheme.onPrimaryContainer
    }

  Card(
    colors = CardDefaults.cardColors(containerColor = containerColor),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = if (hasManagedConfig) "Managed config received" else "No managed config received",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
      )
      DashboardLine(
        label = "Coverage",
        value = "$managedConfiguredCount keys across $supportedCount features",
        contentColor = contentColor,
      )
      DashboardLine(
        label = "Feedback",
        value =
          if (keyedAppStatesUpdatedAt.isNotBlank()) {
            "${keyedAppStatesStatus.removePrefix("Reported ").removeSuffix(".")} @ $keyedAppStatesUpdatedAt"
          } else {
            keyedAppStatesStatus.removePrefix("Reported ").removeSuffix(".")
          },
        contentColor = contentColor,
      )
      if (updatedAt.isNotBlank()) {
        DashboardLine(
          label = "Updated",
          value = updatedAt,
          contentColor = contentColor,
        )
      }
    }
  }
}

@Composable
private fun LocalSimulationStatusCard(
  localOverrideActive: Boolean,
  localAppliedFormat: String,
  importableAppsLoading: Boolean,
  importedSchemaLoading: Boolean,
  selectedImportedAppLabel: String?,
  selectedImportedAppPackage: String?,
  configuredCount: Int,
  supportedCount: Int,
  importedSchemaError: String?,
  updatedAt: String,
) {
  val containerColor =
    if (localOverrideActive) {
      MaterialTheme.colorScheme.secondaryContainer
    } else {
      MaterialTheme.colorScheme.primaryContainer
    }
  val contentColor =
    if (localOverrideActive) {
      MaterialTheme.colorScheme.onSecondaryContainer
    } else {
      MaterialTheme.colorScheme.onPrimaryContainer
    }

  Card(
    colors = CardDefaults.cardColors(containerColor = containerColor),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = if (localOverrideActive) "Validation payload active" else "Validation payload inactive",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
      )
      DashboardLine(
        label = "App",
        value =
          if (importableAppsLoading) {
            "Loading installed apps"
          } else if (importedSchemaLoading) {
            "Loading selected app schema"
          } else if (selectedImportedAppLabel != null && selectedImportedAppPackage != null) {
            "$selectedImportedAppLabel (${selectedImportedAppPackage})"
          } else {
            "Select an installed app to validate"
          },
        contentColor = contentColor,
      )
      DashboardLine(
        label = "Coverage",
        value = "$configuredCount keys across $supportedCount fields",
        contentColor = contentColor,
      )
      DashboardLine(
        label = "Shape",
        value = localAppliedFormat,
        contentColor = contentColor,
      )
      if (!importedSchemaError.isNullOrBlank()) {
        DashboardLine(
          label = "Schema",
          value = importedSchemaError,
          contentColor = contentColor,
        )
      }
      if (updatedAt.isNotBlank()) {
        DashboardLine(
          label = "Updated",
          value = updatedAt,
          contentColor = contentColor,
        )
      }
    }
  }
}

@Composable
private fun EmptyValidationTargetCard() {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "Select an app to validate",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = "Choose an installed app above to import its schema, or tap Load sample to select Managed Config Tool and populate its sample payload.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }
  }
}

@Composable
private fun PageIntroCard(
  title: String,
  body: String,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }
  }
}

@Composable
private fun ManagedConfigCard(item: ManagedConfigItem) {
  val containerColor =
    if (item.managedHasSchemaError) {
      MaterialTheme.colorScheme.errorContainer
    } else {
      MaterialTheme.colorScheme.primaryContainer
    }
  val contentColor =
    if (item.managedHasSchemaError) {
      MaterialTheme.colorScheme.onErrorContainer
    } else {
      MaterialTheme.colorScheme.onPrimaryContainer
    }
  Card(
    colors = CardDefaults.cardColors(containerColor = containerColor),
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.padding(start = (item.depth * 12).dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = item.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
      )
      Text(
        text = item.description,
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
      )
      TypeChip(typeLabel = item.typeLabel, contentColor = contentColor)
      if (item.managedHasSchemaError && !item.managedSchemaChipLabel.isNullOrBlank()) {
        AssistChip(
          onClick = {},
          enabled = false,
          label = { Text(item.managedSchemaChipLabel, maxLines = 1) },
          colors =
            AssistChipDefaults.assistChipColors(
              containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
              disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
              labelColor = MaterialTheme.colorScheme.onErrorContainer,
              disabledLabelColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
          border = null,
        )
      }
      if (item.managedHasSchemaError && !item.managedSchemaMessage.isNullOrBlank()) {
        Text(
          text = "Google expects direct bundle_array item bundles. This delivered config includes an extra bundle key inside each item.",
          style = MaterialTheme.typography.bodyMedium,
          color = contentColor,
        )
        Text(
          text = item.managedSchemaMessage,
          style = MaterialTheme.typography.bodySmall,
          color = contentColor,
        )
      }
      HorizontalDivider()
      if (item.isManagedConfigured) {
        PayloadText(formatPayloadWithKey(item.payloadKey, item.managedValue))
      } else {
        Text(
          text = "No configuration received",
          style = MaterialTheme.typography.bodyMedium,
          color = contentColor,
        )
      }
    }
  }
}

@Composable
private fun EffectiveConfigCard(item: ManagedConfigItem) {
  val containerColor =
    if (item.effectiveHasSchemaError) {
      MaterialTheme.colorScheme.errorContainer
    } else {
      MaterialTheme.colorScheme.primaryContainer
    }
  val contentColor =
    if (item.effectiveHasSchemaError) {
      MaterialTheme.colorScheme.onErrorContainer
    } else {
      MaterialTheme.colorScheme.onPrimaryContainer
    }
  Card(
    colors = CardDefaults.cardColors(containerColor = containerColor),
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.padding(start = (item.depth * 12).dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = item.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
      )
      Text(
        text = item.description,
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
      )
      TypeChip(typeLabel = item.typeLabel, contentColor = contentColor)
      if (item.effectiveHasSchemaError && !item.effectiveSchemaChipLabel.isNullOrBlank()) {
        AssistChip(
          onClick = {},
          enabled = false,
          label = { Text(item.effectiveSchemaChipLabel, maxLines = 1) },
          colors =
            AssistChipDefaults.assistChipColors(
              containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
              disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
              labelColor = MaterialTheme.colorScheme.onErrorContainer,
              disabledLabelColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
          border = null,
        )
      }
      if (item.effectiveHasSchemaError && !item.effectiveSchemaMessage.isNullOrBlank()) {
        Text(
        text = "Google expects direct bundle_array item bundles. This validation payload includes an extra bundle key inside each item.",
          style = MaterialTheme.typography.bodyMedium,
          color = contentColor,
        )
        Text(
          text = item.effectiveSchemaMessage,
          style = MaterialTheme.typography.bodySmall,
          color = contentColor,
        )
      }
      HorizontalDivider()
      if (item.isEffectiveConfigured) {
        PayloadText(formatPayloadWithKey(item.payloadKey, item.effectiveValue))
      } else {
        Text(
          text = "No configuration received",
          style = MaterialTheme.typography.bodyMedium,
          color = contentColor,
        )
      }
    }
  }
}

@Composable
private fun LocalOverrideCard(
  editorValue: String,
  onEditorValueChange: (String) -> Unit,
  onApplyLocalOverride: () -> Unit,
  onClearLocalOverride: () -> Unit,
  onLoadSampleOverride: () -> Unit,
  onLoadInvalidSampleOverride: () -> Unit,
  onLoadEmmPayload: () -> Unit,
  seedJson: String?,
  seededFromEmmPayload: Boolean,
  editorMatchesEmmPayload: Boolean,
  localOverrideFormat: String,
  hasUnappliedChanges: Boolean,
  error: String?,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Validation input",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        if (seededFromEmmPayload) {
          AssistChip(
            onClick = {},
            enabled = false,
            label = {
              Text(
                text = "EMM payload",
                fontFamily = FontFamily.Monospace,
              )
            },
            colors =
              AssistChipDefaults.assistChipColors(
                disabledContainerColor =
                  if (seedJson != null && editorMatchesEmmPayload) BaytonGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.secondaryContainer,
                disabledLabelColor =
                  if (seedJson != null && editorMatchesEmmPayload) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSecondaryContainer,
              ),
          )
        }
      }
      Text(
        text = "Add or edit JSON here to validate config application locally. Changes here will not affect the target app in any way, this is purely used to visualise how the JSON config maps to the app-supported restrictions. ",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      Text(
        text = "Validation input is stored for the current app session and restored across configuration changes or process recreation.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
      )
      Text(
        text = "Detected validation format: $localOverrideFormat",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
      )
      OutlinedTextField(
        value = editorValue,
        onValueChange = onEditorValueChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 8,
        maxLines = 14,
        label = { Text("Managed config JSON") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
      )
      if (!error.isNullOrBlank()) {
        Text(
          text = error,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }
      if (hasUnappliedChanges) {
        Text(
          text = "Editor has unapplied changes",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          LongPressTextButton(
            text = "Load sample",
            onClick = onLoadSampleOverride,
            onLongClick = onLoadInvalidSampleOverride,
          )
          if (seededFromEmmPayload) {
            TextButton(onClick = onLoadEmmPayload) {
              Text("Load EMM")
            }
          }
        }
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = onClearLocalOverride) {
            Icon(
              imageVector = Icons.Filled.Delete,
              contentDescription = "Clear editor",
              tint = MaterialTheme.colorScheme.error,
            )
          }
          Button(onClick = onApplyLocalOverride) {
            Text("Apply")
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressTextButton(
  text: String,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .clip(RoundedCornerShape(20.dp))
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        )
        .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}

@Composable
private fun PayloadCard(
  title: String,
  rawPayload: String,
  subtitle: String? = null,
  highlighted: Boolean = false,
) {
  Card(
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (highlighted) {
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
          } else {
            MaterialTheme.colorScheme.primaryContainer
          },
      ),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
      }
      PayloadText(rawPayload)
    }
  }
}

private fun formatPayloadWithKey(key: String, payload: String): String {
  val lines = payload.lines()
  if (lines.size == 1) return "$key: ${lines.first()}"
  if (lines.first().startsWith("$key[") || lines.first().startsWith("$key:")) return payload

  return buildString {
    append(key)
    append(":\n")
    lines.forEachIndexed { index, line ->
      append("  ")
      append(line)
      if (index != lines.lastIndex) append('\n')
    }
  }
}

@Composable
private fun TypeChip(typeLabel: String, contentColor: Color) {
  Box(
    modifier =
      Modifier
        .border(
          width = 1.dp,
          color = contentColor.copy(alpha = 0.2f),
          shape = RoundedCornerShape(999.dp),
        ).padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(
      text = typeLabel,
      style = MaterialTheme.typography.labelMedium,
      fontFamily = FontFamily.Monospace,
      color = contentColor,
    )
  }
}

@Composable
private fun DashboardLine(
  label: String,
  value: String,
  contentColor: Color,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = label,
      modifier = Modifier.width(84.dp),
      style = MaterialTheme.typography.labelLarge,
      color = contentColor.copy(alpha = 0.8f),
    )
    Text(
      text = value,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color = contentColor,
    )
  }
}

@Composable
private fun PayloadText(text: String) {
  val scrollState = rememberScrollState()
  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .background(
          color = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(16.dp),
        ).horizontalScroll(scrollState)
        .padding(14.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      fontFamily = FontFamily.Monospace,
      softWrap = false,
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun ManagedConfigScreenPreview() {
  BaytonManagedConfigTheme {
    ManagedConfigScreen(
      uiState =
        ManagedConfigUiState(
          hasManagedConfig = true,
          source = "Preview data",
          updatedAt = "2026-04-28 12:34:56",
          managedConfiguredCount = 2,
          effectiveConfiguredCount = 3,
          managedItems =
            listOf(
              ManagedConfigItem(
                key = "my_bool_key",
                title = "My BOOL",
                description = "Boolean baseline managed configuration.",
                typeLabel = "bool",
                isManagedConfigured = true,
                isEffectiveConfigured = true,
                managedValue = "true",
                effectiveValue = "true",
              ),
            ),
          effectiveItems =
            listOf(
              ManagedConfigItem(
                key = "my_bool_key",
                title = "My BOOL",
                description = "Boolean baseline managed configuration.",
                typeLabel = "bool",
                isManagedConfigured = true,
                isEffectiveConfigured = true,
                managedValue = "true",
                effectiveValue = "true",
              ),
              ManagedConfigItem(
                key = "my_bundle_key",
                title = "My BUNDLE",
                description = "Nested managed configuration bundle.",
                typeLabel = "bundle",
                isManagedConfigured = false,
                isEffectiveConfigured = true,
                managedValue = "Not set by RestrictionsManager",
                effectiveValue = "my_bundle_key:\n  my_string_key_in_bundle: value",
              ),
            ),
          effectivePayload = "{\n  \"my_bool_key\": true\n}",
          managedPayload = "{\n  \"my_bool_key\": true\n}",
          effectiveRuntimePayload = "Bundle {\n  my_bool_key = true\n}",
          managedRuntimePayload = "Bundle {\n  my_bool_key = true\n}",
          effectivePayloadNormalizedDifference = false,
          managedPayloadNormalizedDifference = false,
          managedPayloadFormat = "Google runtime bundle / parcelable format",
          localOverrideJson = "{\n  \"my_bool_key\": true\n}",
          localOverrideActive = true,
          localOverrideFormat = "Unflattened nested JSON",
          localAppliedFormat = "Unflattened nested JSON",
          keyedAppStatesStatus = "Reported 9 keyed app states to the Android Enterprise feedback channel.",
          keyedAppStatesUpdatedAt = "2026-04-28 12:34:56",
        ),
      knownConfigs = managedConfigDefinitions,
      onEditorValueChange = {},
      onApplyLocalOverride = {},
      onClearLocalOverride = {},
      onLoadSampleOverride = {},
      onLoadInvalidSampleOverride = {},
      onLoadEmmPayload = {},
      onSelectImportedSchemaPackage = {},
    )
  }
}
