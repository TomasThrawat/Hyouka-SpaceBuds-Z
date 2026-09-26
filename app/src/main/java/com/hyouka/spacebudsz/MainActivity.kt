package com.hyouka.spacebudsz

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GattRow(
    val service: String,
    val characteristic: String,
    val readable: Boolean,
    val writable: Boolean,
    val notifiable: Boolean
)

class SpaceBudsController(private val activity: ComponentActivity) {
    companion object {
        private const val GATT_CONNECT_TIMEOUT_MS = 15_000L
    }
    private val bluetoothManager: BluetoothManager? =
        activity.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val _device = MutableStateFlow<BluetoothDevice?>(null)
    val device = _device.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _connecting = MutableStateFlow(false)
    val connecting = _connecting.asStateFlow()
    private val _status = MutableStateFlow("Ready")
    val status = _status.asStateFlow()
    private val _gatt = MutableStateFlow<List<GattRow>>(emptyList())
    val gatt = _gatt.asStateFlow()
    private var gattConnection: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gattTimeoutRunnable: Runnable? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (canConnect()) {
                val candidate = proxy.connectedDevices.firstOrNull(::isSpaceBuds)
                if (candidate != null) {
                    _device.value = candidate
                    _status.value = "AirBuds detected: " + safeName(candidate)
                }
            }
            @Suppress("DEPRECATION")
            adapter?.closeProfileProxy(profile, proxy)
        }

        override fun onServiceDisconnected(profile: Int) = Unit
    }

    private fun canConnect() =
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    private fun canScan() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

    private fun isSpaceBuds(device: BluetoothDevice): Boolean {
        val name = runCatching {
            if (canConnect()) device.name else null
        }.getOrNull() ?: return false
        val normalized = name.replace(" ", "").replace("-", "").lowercase()
        return normalized.contains("spacebuds") ||
            normalized.contains("otw625") ||
            normalized.contains("625l")
    }

    private fun safeName(device: BluetoothDevice): String =
        runCatching {
            if (canConnect()) device.name else null
        }.getOrNull() ?: "Unknown AirBuds"

    fun refresh() {
        if (!canConnect()) {
            _status.value = "Bluetooth permission is required"
            return
        }

        val bt = adapter
        if (bt == null) {
            _status.value = "Bluetooth adapter unavailable"
            return
        }

        if (!bt.isEnabled) {
            _status.value = "Bluetooth is turned off"
            return
        }

        val gattConnected = runCatching {
            bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
        }.getOrDefault(emptyList())

        val candidate = (gattConnected + bt.bondedDevices)
            .distinctBy { it.address }
            .firstOrNull(::isSpaceBuds)

        if (candidate != null) {
            _device.value = candidate
            _status.value = "AirBuds detected: " + safeName(candidate)
        } else {
            _status.value = "Looking for connected AirBuds..."
        }

        @Suppress("DEPRECATION")
        bt.getProfileProxy(activity, profileListener, BluetoothProfile.A2DP)
        @Suppress("DEPRECATION")
        bt.getProfileProxy(activity, profileListener, BluetoothProfile.HEADSET)

        if (candidate == null && canScan()) {
            scanForSpaceBuds(autoConnect = false)
        }
    }

    fun connect() {
        if (!canConnect()) {
            _status.value = "Bluetooth permission is required"
            return
        }

        if (adapter?.isEnabled != true) {
            _status.value = "Bluetooth is turned off"
            return
        }

        val target = _device.value
        if (target == null) {
            _status.value = "AirBuds not located yet, starting BLE scan..."
            scanForSpaceBuds(autoConnect = true)
            return
        }

        connectTo(target)
    }

    private fun connectTo(target: BluetoothDevice) {
        stopScan()
        cancelGattTimeout()

        gattConnection?.let { oldGatt ->
            runCatching { oldGatt.disconnect() }
            runCatching { oldGatt.close() }
        }
        gattConnection = null

        _connected.value = false
        _connecting.value = true
        _gatt.value = emptyList()
        _status.value = "Connecting to " + safeName(target) + " over BLE..."

        val gatt = runCatching {
            connectGattCompat(target)
        }.getOrElse {
            _connecting.value = false
            _status.value = "Could not start GATT: " + it.javaClass.simpleName
            null
        }

        gattConnection = gatt

        if (gatt == null) {
            _connecting.value = false
            _status.value = "Could not start GATT connection"
            return
        }

        gattTimeoutRunnable = Runnable {
            if (_connecting.value && gattConnection === gatt) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                gattConnection = null
                _connecting.value = false
                _connected.value = false
                _status.value =
                    "GATT connection timed out after 15 seconds. The earbuds may be connected for audio but not exposing a reachable GATT server."
            }
        }.also { mainHandler.postDelayed(it, GATT_CONNECT_TIMEOUT_MS) }
    }

    private fun scanForSpaceBuds(autoConnect: Boolean) {
        if (!canConnect() || !canScan()) {
            _status.value = "Bluetooth scan permission is required"
            return
        }

        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _status.value = "BLE scanner unavailable"
            return
        }

        stopScan()
        _status.value = "Scanning for SpaceBuds Z..."

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching {
                    if (canConnect()) result.device.name else null
                }.getOrNull() ?: return

                val normalized = name
                    .replace(" ", "")
                    .replace("-", "")
                    .lowercase()

                val match = normalized.contains("spacebuds") ||
                    normalized.contains("otw625") ||
                    normalized.contains("625l")

                if (!match) return

                _device.value = result.device
                _status.value = "AirBuds found: " + name
                stopScan()

                if (autoConnect) {
                    connectTo(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _status.value = "BLE scan failed: " + errorCode
                scanCallback = null
            }
        }

        scanCallback = callback
        runCatching {
            scanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback
            )
        }.onFailure {
            scanCallback = null
            _status.value = "BLE scan could not start: " + it.javaClass.simpleName
        }

        mainHandler.postDelayed({ stopScan() }, 10_000L)
    }

    private fun stopScan() {
        val callback = scanCallback ?: return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanCallback = null
    }

    @Suppress("DEPRECATION")
    private fun connectGattCompat(target: BluetoothDevice): BluetoothGatt? {
        return if (Build.VERSION.SDK_INT >= 37) {
            val settings = BluetoothGattConnectionSettings.Builder()
                .setAutoConnectEnabled(false)
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .build()
            target.connectGatt(settings, activity.mainExecutor, callback)
        } else {
            target.connectGatt(
                activity,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    private fun cancelGattTimeout() {
        gattTimeoutRunnable?.let(mainHandler::removeCallbacks)
        gattTimeoutRunnable = null
    }

    fun disconnect() {
        stopScan()
        cancelGattTimeout()
        gattConnection?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gattConnection = null
        _connected.value = false
        _connecting.value = false
        _gatt.value = emptyList()
        _status.value = "Disconnected"
    }

    fun close() {
        disconnect()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (gattConnection !== gatt) {
                runCatching { gatt.close() }
                return
            }

            cancelGattTimeout()

            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                _connecting.value = false
                _connected.value = true
                _status.value = "GATT connected to " + safeName(gatt.device)
                if (canConnect() && gatt.discoverServices()) {
                    _status.value = "Connected, discovering GATT services..."
                } else {
                    _status.value = "GATT connected, service discovery could not start"
                }
            } else {
                _connecting.value = false
                _connected.value = false
                _status.value =
                    "GATT connection failed: status=" + status + ", state=" + newState
                runCatching { gatt.close() }
                if (gattConnection === gatt) {
                    gattConnection = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || !canConnect()) {
                _status.value = "GATT service discovery failed: status=" + status
                return
            }
            _gatt.value = gatt.services.flatMap { service ->
                service.characteristics.map { characteristic ->
                    val properties = characteristic.properties
                    GattRow(
                        service = service.uuid.toString(),
                        characteristic = characteristic.uuid.toString(),
                        readable = properties and 0x02 != 0,
                        writable = properties and 0x08 != 0 || properties and 0x04 != 0,
                        notifiable = properties and 0x10 != 0 || properties and 0x20 != 0
                    )
                }
            }
            _status.value = "GATT services discovered: " + _gatt.value.size + " characteristics"
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpaceBudsApp(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceBudsApp(activity: ComponentActivity) {
    val controller = remember { SpaceBudsController(activity) }
    val device by controller.device.collectAsState()
    val connected by controller.connected.collectAsState()
    val connecting by controller.connecting.collectAsState()
    val status by controller.status.collectAsState()
    val gatt by controller.gatt.collectAsState()
    var page by remember { mutableIntStateOf(0) }
    var anc by remember { mutableStateOf(false) }
    var transparency by remember { mutableStateOf(false) }
    var game by remember { mutableStateOf(false) }
    var spatial by remember { mutableStateOf(false) }
    var bass by remember { mutableFloatStateOf(0.5f) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            controller.refresh()
        } else {
            controller.disconnect()
        }
    }

    DisposableEffect(Unit) {
        controller.refresh()
        onDispose { controller.close() }
    }

    val darkTheme = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(activity)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(activity)
        else -> if (darkTheme) darkColorScheme() else lightColorScheme()
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SpaceBuds Z") },
                    navigationIcon = { Icon(Icons.Default.Headphones, null) }
                )
            },
            bottomBar = {
                NavigationBar {
                    val labels = listOf("Home", "Sound", "Device", "GATT")
                    val icons = listOf(
                        Icons.Default.Headphones,
                        Icons.Default.Settings,
                        Icons.Default.Bluetooth,
                        Icons.Default.Info
                    )
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = page == index,
                            onClick = { page = index },
                            icon = { Icon(icons[index], null) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    ElevatedCard {
                        Column(
                            Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                device?.let {
                                    safeDeviceName(activity, it)
                                } ?: "SpaceBuds Z not detected",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(status)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (!hasBluetoothConnectPermission(activity)) {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.BLUETOOTH_SCAN,
                                                Manifest.permission.BLUETOOTH_CONNECT
                                            )
                                        )
                                    } else {
                                        controller.refresh()
                                    }
                                }) { Text("Find") }
                                Button(
                                    enabled = !connecting && !connected,
                                    onClick = controller::connect
                                ) { Text("Connect") }
                                OutlinedButton(
                                    enabled = connected || connecting,
                                    onClick = controller::disconnect
                                ) { Text("Disconnect") }
                            }
                        }
                    }
                }

                when (page) {
                    0 -> {
                        item { Text("Controls", style = MaterialTheme.typography.titleLarge) }
                        item {
                            ControlCard(
                                "ANC",
                                "Up to 30 dB advertised noise reduction",
                                anc
                            ) { anc = it }
                        }
                        item {
                            ControlCard(
                                "Transparency",
                                "Control surface; vendor command protocol is not public",
                                transparency
                            ) { transparency = it }
                        }
                        item {
                            ControlCard(
                                "Game Mode",
                                "Low-latency mode advertised by Oraimo",
                                game
                            ) { game = it }
                        }
                        item {
                            ControlCard(
                                "Sound360",
                                "Spatial audio",
                                spatial
                            ) { spatial = it }
                        }
                        item {
                            ControlCard(
                                "HavyBass",
                                "Bass tuning profile",
                                bass > 0.5f
                            ) { bass = if (it) 0.75f else 0.5f }
                        }
                    }
                    1 -> {
                        item { Text("Sound", style = MaterialTheme.typography.titleLarge) }
                        item {
                            ElevatedCard {
                                Column(Modifier.padding(18.dp)) {
                                    Text("Bass level")
                                    Slider(value = bass, onValueChange = { bass = it })
                                    Text(((bass - 0.5f) * 20).toInt().toString() + " dB")
                                }
                            }
                        }
                        item {
                            ControlCard("Sound360", "Spatial audio", spatial) { spatial = it }
                        }
                    }
                    2 -> {
                        item { Text("Device", style = MaterialTheme.typography.titleLarge) }
                        item { InfoCard("Bluetooth", "5.4") }
                        item { InfoCard("Driver", "10 mm dynamic") }
                        item { InfoCard("Playback", "10 h earbuds + 28 h case, ANC off") }
                        item { InfoCard("Playback with ANC", "8.5 h earbuds + 23.5 h case") }
                        item { InfoCard("Water resistance", "IPX5 earbuds; case excluded") }
                        item { InfoCard("Microphones", "4") }
                        item { InfoCard("Fast charging", "10 min for about 180 min playback") }
                        item { InfoCard("Dual-device", "Supported") }
                    }
                    3 -> {
                        item {
                            Text(
                                "GATT diagnostics",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        item {
                            Text(
                                "Reads services and characteristics exposed by the connected device. " +
                                    "No undocumented vendor commands are sent."
                            )
                        }
                        gatt.forEach { row ->
                            item {
                                ElevatedCard {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(row.service, style = MaterialTheme.typography.labelSmall)
                                        Text(row.characteristic)
                                        Text(
                                            "read=" + row.readable +
                                                "  write=" + row.writable +
                                                "  notify=" + row.notifiable,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hasBluetoothConnectPermission(activity: ComponentActivity): Boolean =
    ContextCompat.checkSelfPermission(
        activity,
        Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED

private fun safeDeviceName(activity: ComponentActivity, device: BluetoothDevice): String =
    runCatching {
        if (hasBluetoothConnectPermission(activity)) device.name else null
    }.getOrNull() ?: "Unknown AirBuds"

@Composable
private fun ControlCard(
    title: String,
    detail: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    ElevatedCard {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(detail) },
            trailingContent = { Switch(checked, onChecked) }
        )
    }
}

@Composable
private fun InfoCard(title: String, value: String) {
    ElevatedCard {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(value) }
        )
    }
}
