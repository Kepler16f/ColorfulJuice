package com.colorfuljuice.bluetoothbattery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.colorfuljuice.bluetoothbattery.ui.screens.DeviceDetailScreen
import com.colorfuljuice.bluetoothbattery.ui.screens.DeviceListScreen
import com.colorfuljuice.bluetoothbattery.ui.screens.SettingsScreen
import com.colorfuljuice.bluetoothbattery.ui.theme.BluetoothBatteryTheme

class MainActivity : ComponentActivity() {

    private var permissionsGrantedState = mutableStateOf(false)
    private var viewModel: BluetoothBatteryViewModel? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        permissionsGrantedState.value = allGranted
        if (allGranted) {
            viewModel?.loadPairedDevices()
            // 首次授予蓝牙权限的这次启动也要补问通知权限,别拖到下次冷启动
            maybeRequestNotificationPermission()
        }
    }

    // 通知权限(Android 13+):低电量提醒用。只在首次启动问一次,拒绝也不再骚扰。
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGrantedState.value = hasAllPermissions()

        setContent {
            val vm: BluetoothBatteryViewModel = viewModel()
            viewModel = vm

            val language by vm.currentLanguage.collectAsState()
            val themeMode by vm.themeMode.collectAsState()
            val useDynamicColor by vm.useDynamicColor.collectAsState()
            val permissionsGranted by remember { permissionsGrantedState }

            val darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            LaunchedEffect(language) {
                applyLanguage(language)
            }

            LaunchedEffect(Unit) {
                if (!permissionsGranted) {
                    checkAndRequestPermissions()
                } else {
                    vm.loadPairedDevices()
                    maybeRequestNotificationPermission()
                }
            }

            BluetoothBatteryTheme(darkTheme = darkTheme, dynamicColor = useDynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (permissionsGranted) {
                        MainApp(vm)
                    } else {
                        PermissionsRequiredScreen(
                            onRequestPermissions = { checkAndRequestPermissions() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = hasAllPermissions()
        permissionsGrantedState.value = granted
        if (granted) {
            viewModel?.checkBluetoothState()
            viewModel?.loadPairedDevices()
        }
    }

    private fun applyLanguage(language: String) {
        val locale = when (language) {
            "zh" -> java.util.Locale.SIMPLIFIED_CHINESE
            "zh-rTW" -> java.util.Locale.TRADITIONAL_CHINESE
            "en" -> java.util.Locale.ENGLISH
            else -> java.util.Locale.getDefault()
        }
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun hasAllPermissions(): Boolean {
        return getRequiredPermissions().isEmpty()
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return permissions
    }

    private fun checkAndRequestPermissions(): Boolean {
        val missing = getRequiredPermissions()
        return if (missing.isEmpty()) {
            permissionsGrantedState.value = true
            true
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
            false
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        // 只问一次:拒了就静默关闭低电量提醒,不再打断用户
        val prefs = getSharedPreferences("settings_mirror", MODE_PRIVATE)
        if (prefs.getBoolean("notif_perm_asked", false)) return
        prefs.edit().putBoolean("notif_perm_asked", true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: BluetoothBatteryViewModel) {
    val navController = rememberNavController()
    val currentRoute by navController.currentBackStackEntryAsState()
    val currentDestination = currentRoute?.destination?.route
    // 详情页有独立的页头/返回逻辑,主框架的顶栏和底部导航只在两个主页签显示
    val showBars = currentDestination == "devices" || currentDestination == "settings"

    Scaffold(
        topBar = {
            if (showBars) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    actions = {
                        if (currentDestination == "devices") {
                            IconButton(onClick = { viewModel.connectAllDevices() }) {
                                Icon(
                                    imageVector = Icons.Default.BluetoothConnected,
                                    contentDescription = stringResource(R.string.device_connect_all)
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showBars) {
                NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_devices)) },
                    selected = currentDestination == "devices",
                    onClick = {
                        if (currentDestination != "devices") {
                            navController.navigate("devices") {
                                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                    selected = currentDestination == "settings",
                    onClick = {
                        if (currentDestination != "settings") {
                            navController.navigate("settings") {
                                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = "devices",
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("devices") {
                DeviceListScreen(
                    viewModel = viewModel,
                    onOpenDevice = { address ->
                        navController.navigate("device?address=${android.net.Uri.encode(address)}")
                    }
                )
            }
            composable("settings") {
                SettingsScreen(viewModel = viewModel)
            }
            composable(
                "device?address={address}",
                arguments = listOf(
                    androidx.navigation.navArgument("address") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                DeviceDetailScreen(
                    viewModel = viewModel,
                    address = entry.arguments?.getString("address").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
fun PermissionsRequiredScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Bluetooth,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.device_bluetooth_disabled),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.device_bluetooth_disabled_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermissions) {
            Text("Grant Permissions")
        }
        Spacer(modifier = Modifier.height(12.dp))
        val context = androidx.compose.ui.platform.LocalContext.current
        OutlinedButton(
            onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.fromParts("package", "com.colorfuljuice.bluetoothbattery", null)
                context.startActivity(intent)
            }
        ) {
            Text("Open Settings")
        }
    }
}
