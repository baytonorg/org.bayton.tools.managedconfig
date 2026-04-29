package org.bayton.tools.managedconfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import org.bayton.tools.managedconfig.theme.BaytonManagedConfigTheme

class MainActivity : ComponentActivity() {
  private val managedConfigViewModel: ManagedConfigViewModel by viewModels()
  private var receiverRegistered = false
  private val restrictionsManager by lazy { getSystemService(RestrictionsManager::class.java) }

  private val restrictionsReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        managedConfigViewModel.refreshRestrictions("Restrictions changed broadcast")
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.auto(LIGHT_SYSTEM_BAR_SCRIM, DARK_SYSTEM_BAR_SCRIM),
      navigationBarStyle = SystemBarStyle.auto(LIGHT_SYSTEM_BAR_SCRIM, DARK_SYSTEM_BAR_SCRIM),
    )

    managedConfigViewModel.loadInstalledApps()
    managedConfigViewModel.restoreUiState(
      selectedImportedPackage = savedInstanceState?.getString(STATE_SELECTED_IMPORTED_PACKAGE),
      localOverrideJson = savedInstanceState?.getString(STATE_LOCAL_OVERRIDE_JSON),
    )
    managedConfigViewModel.handleIncomingIntent(intent)

    setContent {
      val uiState by managedConfigViewModel.uiState.collectAsStateWithLifecycle()

      LaunchedEffect(Unit) {
        managedConfigViewModel.uiEvents.collectLatest { event ->
          when (event) {
            is ManagedConfigUiEvent.ToastMessage ->
              Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
          }
        }
      }

      BaytonManagedConfigTheme {
        ManagedConfigScreen(
          uiState = uiState,
          knownConfigs = managedConfigDefinitions,
          onApplyLocalOverride = managedConfigViewModel::applyLocalOverride,
          onClearLocalOverride = managedConfigViewModel::clearLocalOverride,
          onLoadSampleOverride = managedConfigViewModel::loadSampleOverride,
          onLoadInvalidSampleOverride = managedConfigViewModel::loadInvalidSampleOverride,
          onSelectImportedSchemaPackage = managedConfigViewModel::selectImportedSchemaPackage,
        )
      }
    }
  }

  override fun onStart() {
    super.onStart()
    if (!receiverRegistered) {
      ContextCompat.registerReceiver(
        this,
        restrictionsReceiver,
        IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
      receiverRegistered = true
    }
  }

  override fun onResume() {
    super.onResume()
    managedConfigViewModel.refreshRestrictions("Activity resume")
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    managedConfigViewModel.handleIncomingIntent(intent)
  }

  override fun onStop() {
    if (receiverRegistered) {
      unregisterReceiver(restrictionsReceiver)
      receiverRegistered = false
    }
    super.onStop()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(STATE_SELECTED_IMPORTED_PACKAGE, managedConfigViewModel.selectedImportedPackage())
    outState.putString(STATE_LOCAL_OVERRIDE_JSON, managedConfigViewModel.currentOverrideJson())
  }
}

internal const val EXTRA_MANAGED_CONFIG_JSON = "managed_config_json"
internal const val EMPTY_MANAGED_CONFIG_SIGNATURE = "__empty_managed_config__"
private const val STATE_SELECTED_IMPORTED_PACKAGE = "state_selected_imported_package"
private const val STATE_LOCAL_OVERRIDE_JSON = "state_local_override_json"
private const val LIGHT_SYSTEM_BAR_SCRIM = 0xFFF7F7F7.toInt()
private const val DARK_SYSTEM_BAR_SCRIM = 0xFF141218.toInt()
