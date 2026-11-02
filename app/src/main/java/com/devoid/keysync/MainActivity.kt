package com.devoid.keysync

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.devoid.keysync.data.local.MumuProfileLibrary
import com.devoid.keysync.ui.GameProfilesScreen
import com.devoid.keysync.MainActivityViewModel.UiState
import com.devoid.keysync.model.ThemePreference
import com.devoid.keysync.ui.AboutScreen
import com.devoid.keysync.ui.MainScreen
import com.devoid.keysync.ui.SettingsScreen
import com.devoid.keysync.ui.theme.KeySyncTheme
import com.devoid.keysync.util.applyAppLanguage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        val INTENT_ACTION_SETTINGS = "navigateToSettings"
    }

    private val TAG = this::class.simpleName
    private val viewModel: MainActivityViewModel by viewModels()
    private val navigateTo = mutableStateOf<Any?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        intent.action?.let {
            if (it == INTENT_ACTION_SETTINGS) {
                navigateTo.value = SettingsScreen
            }
        }
        setContent {
            val mumuLibraries by viewModel.mumuLibraries.collectAsStateWithLifecycle()
            val diagnosticsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                uri?.let { viewModel.exportTouchDiagnostics(it) { success ->
                    Toast.makeText(this@MainActivity, getString(if (success) R.string.backup_exported else R.string.backup_failed), Toast.LENGTH_SHORT).show()
                } }
            }
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                uri?.let {
                    viewModel.exportMappingBackup(it) { success ->
                        Toast.makeText(
                            this@MainActivity,
                            getString(if (success) R.string.backup_exported else R.string.backup_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    viewModel.importMappingBackup(it) { success ->
                        Toast.makeText(
                            this@MainActivity,
                            getString(if (success) R.string.backup_imported else R.string.backup_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            var confirmImport by remember { mutableStateOf(false) }
            val uiState by viewModel.inputBackendState.collectAsStateWithLifecycle()
            val appConfig by viewModel.appConfig.collectAsStateWithLifecycle()
            val rootConnectionStatus by viewModel.rootConnectionStatus.collectAsStateWithLifecycle()
            val rootCaptureStatus by viewModel.rootCaptureStatus.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val navController = rememberNavController()
            KeySyncTheme(
                darkTheme = when (appConfig.themePreference) {
                    ThemePreference.LIGHT -> false
                    ThemePreference.DARK -> true
                    else -> isSystemInDarkTheme()
                },
                dynamicColor = appConfig.themePreference == ThemePreference.SYSTEM_DYNAMIC
            ) {
                NavHost(navController = navController, startDestination = MainScreen,
                    enterTransition = {
                        fadeIn(
                            animationSpec = tween(
                                300, easing = LinearEasing
                            )
                        ) + slideIntoContainer(
                            animationSpec = tween(300, easing = EaseIn),
                            towards = AnimatedContentTransitionScope.SlideDirection.Start
                        )
                    },
                    popExitTransition = {
                        fadeOut(
                            animationSpec = tween(
                                300, easing = LinearEasing
                            )
                        ) + slideOutOfContainer(
                            animationSpec = tween(300, easing = EaseOut),
                            towards = AnimatedContentTransitionScope.SlideDirection.End
                        )
                    }) {
                    composable<MainScreen>(
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None }) {
                        MainScreen(
                            viewModel = viewModel,
                            snackbarHostState = snackbarHostState,
                            appName = getString(R.string.app_name),
                            onNavigateToSettings = {
                                navController.navigate(route = SettingsScreen)
                            },
                            onManageProfiles = { navController.navigate(GameProfilesScreen(it)) }
                        ) { packageName ->
                            if (checkUiState(
                                    uiState,
                                    viewModel.viewModelScope,
                                    snackbarHostState
                                )
                            ) {
                                viewModel.launchPackage(packageName, packageManager)
//                                launchService()
                            }
                        }
                    }
                    composable<SettingsScreen>(
                        popEnterTransition = { EnterTransition.None }) {
                        SettingsScreen(
                            appConfig = appConfig,
                            rootConnectionStatus = rootConnectionStatus,
                            rootCaptureStatus = rootCaptureStatus,
                            onRetryRoot = viewModel::retryRootConnection,
                            onExportBackup = { exportLauncher.launch("keysync-mapping-backup.json") },
                            onImportBackup = { confirmImport = true },
                            onExportTouchDiagnostics = { diagnosticsLauncher.launch("keysync-touch-diagnostics.json") },
                            onNavigateBack = navController::popBackStack,
                            onNavigateAbout = { navController.navigate(route = AboutScreen) },
                            onSave = {
                                val languageChanged =
                                    it.languagePreference != viewModel.appConfig.value.languagePreference
                                viewModel.saveKeyConfig(it)
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.saved_successfully),
                                    Toast.LENGTH_SHORT
                                ).show()
                                navController.navigate(route = MainScreen)
                                if (languageChanged) {
                                    applyAppLanguage(it.languagePreference)
                                }
                            })
                    }
                    composable<AboutScreen> {
                        AboutScreen(
                            onNavigateBack = navController::popBackStack,
                            onOpenGithub = viewModel::openGithub
                        )
                    }
                    composable<GameProfilesScreen> { entry ->
                        val targetPackage = entry.toRoute<GameProfilesScreen>().packageName
                        GameProfilesScreen(
                            packageName = targetPackage,
                            library = mumuLibraries[targetPackage] ?: MumuProfileLibrary(),
                            viewModel = viewModel,
                            onNavigateBack = navController::popBackStack
                        )
                    }
                }
                if (confirmImport) {
                    AlertDialog(
                        onDismissRequest = { confirmImport = false },
                        title = { Text(stringResource(R.string.restore_backup)) },
                        text = { Text(stringResource(R.string.restore_backup_warning)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmImport = false
                                importLauncher.launch(arrayOf("application/json", "text/plain"))
                            }) { Text(stringResource(R.string.restore)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmImport = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }
                if (navigateTo.value != null) {
                    navController.navigate(route = navigateTo.value!!)
                    navigateTo.value = null
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.action?.let {
            if (it == INTENT_ACTION_SETTINGS) {
                navigateTo.value = SettingsScreen
            }
        }
    }

    private fun checkShizukuPermission(): Boolean {
        val isGranted = if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            ContextCompat.checkSelfPermission(
                this,
                ShizukuProvider.PERMISSION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
        return isGranted
    }

    private fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun checkUiState(
        uiState: UiState,
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState
    ): Boolean {
        when (uiState) {
            UiState.RootUnavailable -> {
                scope.launch {
                    snackbarHostState.showSnackbar(getString(R.string.root_backend_unavailable))
                }
                return false
            }

            UiState.RootRunning -> return checkOverlayPermission(
                scope,
                snackbarHostState
            )

            UiState.ShizukuUnavailable -> {
                scope.launch {
                    snackbarHostState.showSnackbar(getString(R.string.shizuku_not_running))
                }
                return false
            }

            UiState.ShizukuRunning -> {
                if (!checkOverlayPermission(scope, snackbarHostState)) return false
                if (!checkShizukuPermission()) {
                    if (viewModel.requestShizukuPermission()) {
                        return false
                    }
                    scope.launch {
                        val result = snackbarHostState
                            .showSnackbar(
                                message = getString(R.string.grant_permissions),
                                actionLabel = getString(R.string.open_settings),
                                duration = SnackbarDuration.Short
                            )
                        when (result) {
                            SnackbarResult.ActionPerformed -> {
                                val settingIntent =
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                val uri = Uri.fromParts(
                                    "package",
                                    packageName,
                                    null
                                )
                                settingIntent.setData(uri)
                                startActivity(settingIntent)
                            }

                            else -> {}
                        }
                    }
                    return false
                }

                return true
            }
        }
    }

    private fun checkOverlayPermission(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState
    ): Boolean {
        if (checkOverlayPermission()) return true

        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = getString(R.string.grant_permissions),
                actionLabel = getString(R.string.open_settings),
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                val settingIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                settingIntent.setData(uri)
                startActivity(settingIntent)
            }
        }
        return false
    }
}

@Serializable
object MainScreen

@Serializable
object SettingsScreen

@Serializable
object AboutScreen

@Serializable
data class GameProfilesScreen(val packageName: String)
