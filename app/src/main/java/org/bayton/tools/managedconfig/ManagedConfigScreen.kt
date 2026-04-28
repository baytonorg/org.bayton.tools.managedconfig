package org.bayton.tools.managedconfig

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.bayton.tools.managedconfig.theme.BaytonManagedConfigTheme
import kotlinx.coroutines.launch

private const val RESTRICTIONS_MANAGER_TAB = 0
private const val LOCAL_SIMULATION_TAB = 1

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ManagedConfigScreen(
  uiState: ManagedConfigUiState,
  knownConfigs: List<ManagedConfigDefinition>,
  onApplyLocalOverride: (String) -> Unit,
  onClearLocalOverride: () -> Unit,
  onLoadSampleOverride: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var editorValue by remember(uiState.localOverrideJson) { mutableStateOf(uiState.localOverrideJson) }
  val pagerState = rememberPagerState(initialPage = RESTRICTIONS_MANAGER_TAB, pageCount = { 2 })
  val coroutineScope = rememberCoroutineScope()

  Surface(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .safeDrawingPadding()
          .navigationBarsPadding(),
    ) {
      PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
        Tab(
          selected = pagerState.currentPage == RESTRICTIONS_MANAGER_TAB,
          onClick = {
            coroutineScope.launch {
              pagerState.animateScrollToPage(RESTRICTIONS_MANAGER_TAB)
            }
          },
          text = { Text("RestrictionsManager") },
        )
        Tab(
          selected = pagerState.currentPage == LOCAL_SIMULATION_TAB,
          onClick = {
            coroutineScope.launch {
              pagerState.animateScrollToPage(LOCAL_SIMULATION_TAB)
            }
          },
          text = { Text("Local simulation") },
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

          else ->
            LocalSimulationPage(
              uiState = uiState,
              knownConfigs = knownConfigs,
              editorValue = editorValue,
              onEditorValueChange = { editorValue = it },
              onApplyLocalOverride = { onApplyLocalOverride(editorValue) },
              onClearLocalOverride = {
                editorValue = ""
                onClearLocalOverride()
              },
              onLoadSampleOverride = onLoadSampleOverride,
            )
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
        title = "Reconstructed JSON payload",
        subtitle = "Android does not receive JSON directly, so this is inferred from the Parcelable payload.",
        rawPayload = uiState.managedPayload,
      )
    }

    item {
      PayloadCard(
        title = "Managed runtime Bundle / Parcelable preview",
        subtitle = "Raw delivered runtime structure before normalization.",
        rawPayload = uiState.managedRuntimePayload,
      )
    }
  }
}

@Composable
private fun LocalSimulationPage(
  uiState: ManagedConfigUiState,
  knownConfigs: List<ManagedConfigDefinition>,
  editorValue: String,
  onEditorValueChange: (String) -> Unit,
  onApplyLocalOverride: () -> Unit,
  onClearLocalOverride: () -> Unit,
  onLoadSampleOverride: () -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      LocalSimulationStatusCard(
        localOverrideActive = uiState.localOverrideActive,
        localOverrideFormat = uiState.localOverrideFormat,
        localShapeHighlights = uiState.localShapeHighlights,
        effectiveConfiguredCount = uiState.effectiveConfiguredCount,
        supportedCount = knownConfigs.size,
        source = uiState.source,
        updatedAt = uiState.updatedAt,
      )
    }

    item {
      LocalOverrideCard(
        editorValue = editorValue,
        onEditorValueChange = onEditorValueChange,
        onApplyLocalOverride = onApplyLocalOverride,
        onClearLocalOverride = onClearLocalOverride,
        onLoadSampleOverride = onLoadSampleOverride,
        localOverrideFormat = uiState.localOverrideFormat,
        error = uiState.localOverrideError,
      )
    }

    items(uiState.effectiveItems, key = { item -> item.key }) { item ->
      EffectiveConfigCard(item = item)
    }

    item {
      PayloadCard(
        title = "Reconstructed JSON payload",
        subtitle = "Reverse-mapped JSON from the Parcelable payload. Check it matches the JSON provided.",
        rawPayload = uiState.effectivePayload,
      )
    }

    item {
      PayloadCard(title = "Local simulation Bundle / Parcelable preview", rawPayload = uiState.effectiveRuntimePayload)
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
      org.bayton.tools.managedconfig.theme.BaytonGreen
    } else {
      MaterialTheme.colorScheme.secondaryContainer
    }
  val contentColor =
    if (hasManagedConfig) {
      androidx.compose.ui.graphics.Color.White
    } else {
      MaterialTheme.colorScheme.onSecondaryContainer
    }

  Card(
    colors = CardDefaults.cardColors(containerColor = containerColor),
    shape = RoundedCornerShape(28.dp),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = if (hasManagedConfig) "Managed config received from RestrictionsManager" else "No managed config from RestrictionsManager",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
      )
      DashboardLine(
        label = "Coverage",
        value = "$managedConfiguredCount keys across $supportedCount features",
        contentColor = contentColor,
      )
      if (hasManagedConfig && keyedAppStatesStatus != "No keyed app states reported yet") {
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
private fun LocalSimulationStatusCard(
  localOverrideActive: Boolean,
  localOverrideFormat: String,
  localShapeHighlights: List<String>,
  effectiveConfiguredCount: Int,
  supportedCount: Int,
  source: String,
  updatedAt: String,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    shape = RoundedCornerShape(28.dp),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = if (localOverrideActive) "Local simulation active" else "Local simulation inactive",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
      )
      DashboardLine(
        label = "Coverage",
        value = "$effectiveConfiguredCount keys across $supportedCount features",
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      DashboardLine(
        label = "Shape",
        value = localOverrideFormat,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      if (updatedAt.isNotBlank()) {
        DashboardLine(
          label = "Updated",
          value = updatedAt,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
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
    shape = RoundedCornerShape(24.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = item.title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
      )
      Text(
        text = item.description,
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
      )
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
        PayloadText(formatPayloadWithKey(item.key, item.managedValue))
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
    shape = RoundedCornerShape(24.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = item.title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
      )
      Text(
        text = item.description,
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
      )
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
          text = "Google expects direct bundle_array item bundles. This simulation includes an extra bundle key inside each item.",
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
        PayloadText(formatPayloadWithKey(item.key, item.effectiveValue))
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
  localOverrideFormat: String,
  error: String?,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    shape = RoundedCornerShape(24.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Simulation input",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = "Paste JSON here to simulate app restrictions locally for QA. Google runtime managed config reaches the app as unflattened Bundle / Parcelable data. This simulator accepts either unflattened nested JSON or flattened keys such as my_bundle_key.my_string_key_in_bundle and my_bundle_array_key[0].my_string_key_in_bundle_array.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      Text(
        text = "Simulation is session-only and clears when the process is restarted.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.tertiary,
      )
      Text(
        text = "Detected simulation format: $localOverrideFormat",
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
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(onClick = onApplyLocalOverride) {
          Text("Apply")
        }
        TextButton(onClick = onLoadSampleOverride) {
          Text("Load sample")
        }
        TextButton(onClick = onClearLocalOverride) {
          Text("Clear")
        }
      }
    }
  }
}

@Composable
private fun PayloadLabel(
  text: String,
  color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.secondary,
) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = color,
  )
}

@Composable
private fun PayloadCard(title: String, rawPayload: String, subtitle: String? = null) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    shape = RoundedCornerShape(24.dp),
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
          color = MaterialTheme.colorScheme.outline,
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
private fun DashboardLine(
  label: String,
  value: String,
  contentColor: androidx.compose.ui.graphics.Color,
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
          color = MaterialTheme.colorScheme.background,
          shape = RoundedCornerShape(18.dp),
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
                isManagedConfigured = true,
                isEffectiveConfigured = true,
                managedValue = "true",
                effectiveValue = "true",
              ),
              ManagedConfigItem(
                key = "my_bundle_key",
                title = "My BUNDLE",
                description = "Nested managed configuration bundle.",
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
          managedPayloadFormat = "Google runtime bundle / parcelable format",
          localOverrideJson = "{\n  \"my_bool_key\": true\n}",
          localOverrideActive = true,
          localOverrideFormat = "Unflattened nested JSON",
          keyedAppStatesStatus = "Reported 9 keyed app states to the Android Enterprise feedback channel.",
          keyedAppStatesUpdatedAt = "2026-04-28 12:34:56",
        ),
      knownConfigs = managedConfigDefinitions,
      onApplyLocalOverride = {},
      onClearLocalOverride = {},
      onLoadSampleOverride = {},
    )
  }
}
