package com.hyouka.spacebudsz

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        private const val GATT_CONNECT_TIMEOUT_MS = 10_000L
        private const val GATT_DISCOVERY_TIMEOUT_MS = 10_000L
        private const val MAX_GATT_ATTEMPTS = 2
    }

    private val bluetoothManager: BluetoothManager? =
        activity.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _device = MutableStateFlow<BluetoothDevice?>(null)
    val device = _device.asStateFlow()

    private val _bluetoothConnected = MutableStateFlow(false)
    val bluetoothConnected = _bluetoothConnected.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting = _connecting.asStateFlow()

    private val _status = MutableStateFlow("Connect SpaceBuds Z from Android Bluetooth settings")
    val status = _status.asStateFlow()

    private val _gatt = MutableStateFlow<List<GattRow>>(emptyList())
    val gatt = _gatt.asStateFlow()

    private var gattConnection: BluetoothGatt? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gattTimeoutRunnable: Runnable? = null
    private var gattDiscoveryTimeoutRunnable: Runnable? = null
    private var gattAttempt = 0
    private var gattUnavailable = false
    private var disposed = false
    private var receiverRegistered = false
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(_context: Context, intent: Intent) {
            if (disposed || !canConnect()) return

            val action = intent.action
            val state = intent.getIntExtra(
                BluetoothProfile.EXTRA_STATE,
                BluetoothProfile.STATE_DISCONNECTED
            )
            val connectedEvent =
                action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                    (
                        (
                            action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ||
                                action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED
                            ) &&
                            state == BluetoothProfile.STATE_CONNECTED
                        )

            val eventDevice = intent.getBluetoothDeviceExtra()
            mainHandler.post {
                if (disposed) return@post

                if (connectedEvent && eventDevice != null && isSpaceBuds(eventDevice)) {
                    handleBluetoothConnected(eventDevice)
                } else {
                    refreshSystemConnection()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (disposed) {
                runCatching { adapter?.closeProfileProxy(profile, proxy) }
                return
            }

            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy as? BluetoothA2dp
                BluetoothProfile.HEADSET -> headsetProxy = proxy as? BluetoothHeadset
            }
            mainHandler.post {
                if (!disposed) refreshSystemConnection()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (disposed) return

            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }
            mainHandler.post {
                if (!disposed) refreshSystemConnection()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureProfileProxies() {
        if (!canConnect()) return
        adapter ?: return

        if (a2dpProxy == null) {
            runCatching {
                adapter?.getProfileProxy(activity, profileListener, BluetoothProfile.A2DP)
            }
        }
        if (headsetProxy == null) {
            runCatching {
                adapter?.getProfileProxy(activity, profileListener, BluetoothProfile.HEADSET)
            }
        }
    }

    private fun canConnect(): Boolean =
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    private fun isSpaceBuds(device: BluetoothDevice): Boolean =
        isSupportedSpaceBudsName(
            runCatching {
                if (canConnect()) device.name else null
            }.getOrNull()
        )

    private fun safeName(device: BluetoothDevice): String =
        runCatching {
            if (canConnect()) device.name else null
        }.getOrNull() ?: "Unknown AirBuds"

    fun startMonitoring() {
        if (disposed) return

        if (!canConnect()) {
            _status.value = "Bluetooth permission is required"
            return
        }

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }

            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    activity.registerReceiver(
                        bluetoothReceiver,
                        filter,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    @Suppress("DEPRECATION")
                    activity.registerReceiver(bluetoothReceiver, filter)
                }
                receiverRegistered = true
            }.onFailure {
                _status.value =
                    "Bluetooth monitoring could not start: " + it.javaClass.simpleName
            }
        }

        ensureProfileProxies()
        refreshSystemConnection()
    }

    private fun refreshSystemConnection() {
        if (!canConnect()) {
            _bluetoothConnected.value = false
            closeGatt()
            _status.value = "Bluetooth permission is required"
            return
        }

        val bt = adapter
        if (bt == null) {
            _bluetoothConnected.value = false
            closeGatt()
            _status.value = "Bluetooth adapter unavailable"
            return
        }

        if (!bt.isEnabled) {
            _bluetoothConnected.value = false
            closeGatt()
            _status.value = "Bluetooth is turned off"
            return
        }

        val connectedDevices = buildList {
            a2dpProxy?.let { proxy ->
                addAll(runCatching { proxy.connectedDevices }.getOrDefault(emptyList()))
            }
            headsetProxy?.let { proxy ->
                addAll(runCatching { proxy.connectedDevices }.getOrDefault(emptyList()))
            }
            addAll(
                runCatching {
                    bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
                }.getOrDefault(emptyList())
            )
        }

        val candidate = connectedDevices
            .distinctBy { it.address }
            .firstOrNull(::isSpaceBuds)

        if (candidate == null) {
            _device.value = null
            _bluetoothConnected.value = false
            closeGatt()
            _status.value = "Connect SpaceBuds Z from Android Bluetooth settings"
            return
        }

        if (candidate == null) {
            _device.value = null
            _bluetoothConnected.value = false
            closeGatt()
            _status.value = "Connect SpaceBuds Z from Android Bluetooth settings"
            return
        }

        handleBluetoothConnected(candidate)
    }

    private fun handleBluetoothConnected(candidate: BluetoothDevice) {
        if (disposed || !canConnect()) return

        _device.value = candidate
        _bluetoothConnected.value = true

        when {
            _connected.value -> {
                _status.value = "Bluetooth connected. GATT is ready."
            }
            _connecting.value -> {
                _status.value = "Bluetooth connected. Preparing device diagnostics..."
            }
            gattUnavailable -> {
                _status.value = "Bluetooth connected. GATT is unavailable on this device."
            }
            else -> {
                _status.value = "Bluetooth connected: " + safeName(candidate)
                prepareGatt(candidate)
            }
        }
    }

    fun refresh() {
        gattUnavailable = false
        startMonitoring()
        mainHandler.post {
            if (!disposed) refreshSystemConnection()
        }
    }

    private fun prepareGatt(target: BluetoothDevice) {
        if (disposed || (_connecting.value && gattConnection != null)) return

        val existing = gattConnection
        if (
            existing != null &&
            existing.device.address == target.address &&
            _connected.value
        ) return

        cancelGattTimeouts()
        existing?.let { oldGatt ->
            runCatching { oldGatt.disconnect() }
            runCatching { oldGatt.close() }
        }
        gattConnection = null

        _connected.value = false
        _connecting.value = true
        _gatt.value = emptyList()
        gattAttempt = 0
        gattUnavailable = false
        _status.value = "Bluetooth connected. Opening device diagnostics..."

        startGattAttempt(target, 0)
    }

    private fun startGattAttempt(target: BluetoothDevice, attempt: Int) {
        if (disposed || !canConnect()) {
            _connecting.value = false
            if (!disposed) _status.value = "Bluetooth permission is required"
            return
        }

        cancelGattTimeouts()
        gattAttempt = attempt

        val transport = if (attempt == 0) {
            BluetoothDevice.TRANSPORT_AUTO
        } else {
            BluetoothDevice.TRANSPORT_LE
        }
        val transportName = if (attempt == 0) "AUTO" else "LE"

        val gatt = runCatching {
            connectGattCompat(target, transport)
        }.getOrElse {
            if (attempt + 1 < MAX_GATT_ATTEMPTS) {
                _status.value =
                    "GATT $transportName could not start. Retrying with LE..."
                mainHandler.post {
                    if (!disposed) startGattAttempt(target, attempt + 1)
                }
            } else {
                markGattUnavailable(target)
            }
            null
        }

        if (gatt == null) return

        gattConnection = gatt
        _status.value =
            "Bluetooth connected. Opening GATT with transport $transportName..."

        gattTimeoutRunnable = Runnable {
            if (!disposed && _connecting.value && gattConnection === gatt) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                gattConnection = null

                if (attempt + 1 < MAX_GATT_ATTEMPTS) {
                    _status.value =
                        "GATT $transportName timed out. Retrying with LE..."
                    mainHandler.post {
                        if (!disposed) startGattAttempt(target, attempt + 1)
                    }
                } else {
                    markGattUnavailable(target)
                }
            }
        }.also { mainHandler.postDelayed(it, GATT_CONNECT_TIMEOUT_MS) }
    }

    private fun markGattUnavailable(target: BluetoothDevice) {
        cancelGattTimeouts()
        if (!disposed) {
            _connecting.value = false
            _connected.value = false
            gattUnavailable = true
            _device.value = target
            _bluetoothConnected.value = true
            _status.value = "Bluetooth connected. GATT is unavailable on this device."
        }
    }

    @Suppress("DEPRECATION")
    private fun connectGattCompat(
        target: BluetoothDevice,
        transport: Int
    ): BluetoothGatt? {
        return if (Build.VERSION.SDK_INT >= 37) {
            val settings = BluetoothGattConnectionSettings.Builder()
                .setAutoConnectEnabled(false)
                .setTransport(transport)
                .build()
            target.connectGatt(settings, activity.mainExecutor, callback)
        } else {
            target.connectGatt(
                activity,
                false,
                callback,
                transport
            )
        }
    }

    private fun cancelGattTimeout() {
        gattTimeoutRunnable?.let(mainHandler::removeCallbacks)
        gattTimeoutRunnable = null
    }

    private fun cancelGattDiscoveryTimeout() {
        gattDiscoveryTimeoutRunnable?.let(mainHandler::removeCallbacks)
        gattDiscoveryTimeoutRunnable = null
    }

    private fun cancelGattTimeouts() {
        cancelGattTimeout()
        cancelGattDiscoveryTimeout()
    }

    private fun closeGatt() {
        cancelGattTimeouts()
        gattConnection?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gattConnection = null
        _connected.value = false
        _connecting.value = false
        _gatt.value = emptyList()
    }

    fun disconnect() {
        closeGatt()
        refreshSystemConnection()
    }

    fun close() {
        if (disposed) return
        disposed = true

        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(bluetoothReceiver) }
            receiverRegistered = false
        }
        closeGatt()
        @Suppress("DEPRECATION")
        runCatching {
            a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        }
        a2dpProxy = null
        headsetProxy = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (disposed || gattConnection !== gatt) {
                runCatching { gatt.close() }
                return
            }

            cancelGattTimeout()

            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                _connecting.value = false
                _connected.value = false
                _status.value = "Bluetooth connected. Discovering GATT services..."

                if (!canConnect() || !gatt.discoverServices()) {
                    runCatching { gatt.close() }
                    gattConnection = null
                    markGattUnavailable(gatt.device)
                    return
                }

                gattDiscoveryTimeoutRunnable = Runnable {
                    if (!disposed && gattConnection === gatt && !_connected.value) {
                        runCatching { gatt.disconnect() }
                        runCatching { gatt.close() }
                        gattConnection = null
                        markGattUnavailable(gatt.device)
                    }
                }.also { mainHandler.postDelayed(it, GATT_DISCOVERY_TIMEOUT_MS) }
            } else {
                val target = gatt.device
                val shouldRetry = gattConnection === gatt &&
                    gattAttempt + 1 < MAX_GATT_ATTEMPTS

                cancelGattDiscoveryTimeout()
                runCatching { gatt.close() }
                if (gattConnection === gatt) {
                    gattConnection = null
                }

                if (shouldRetry) {
                    _status.value =
                        "GATT failed on transport " +
                            (if (gattAttempt == 0) "AUTO" else "LE") +
                            " (status=" + status + "). Retrying with LE..."
                    mainHandler.post {
                        if (!disposed) startGattAttempt(target, gattAttempt + 1)
                    }
                } else {
                    markGattUnavailable(target)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (disposed || gattConnection !== gatt) return

            cancelGattDiscoveryTimeout()

            if (status != BluetoothGatt.GATT_SUCCESS || !canConnect()) {
                runCatching { gatt.close() }
                gattConnection = null
                markGattUnavailable(gatt.device)
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

            _connected.value = true
            _connecting.value = false
            gattUnavailable = false
            _status.value =
                "Bluetooth connected. GATT services discovered: " +
                    _gatt.value.size + " characteristics"
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            if (disposed || gattConnection !== gatt || !canConnect()) return

            _connected.value = false
            _status.value = "Bluetooth connected. GATT database changed; rediscovering services..."
            if (gatt.discoverServices()) {
                cancelGattDiscoveryTimeout()
                gattDiscoveryTimeoutRunnable = Runnable {
                    if (!disposed && gattConnection === gatt && !_connected.value) {
                        markGattUnavailable(gatt.device)
                    }
                }.also { mainHandler.postDelayed(it, GATT_DISCOVERY_TIMEOUT_MS) }
            }
        }
    }


}

internal fun isSupportedSpaceBudsName(name: String?): Boolean {
    val normalized = name
        ?.replace(" ", "")
        ?.replace("-", "")
        ?.lowercase()
        ?: return false

    return normalized.isNotBlank() && (
        normalized.contains("spacebudsz") ||
            normalized.contains("otw625l") ||
            normalized.contains("otw625")
        )
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
            controller.startMonitoring()
        } else {
            controller.disconnect()
        }
    }

    DisposableEffect(Unit) {
        controller.startMonitoring()
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
                                } ?: "SpaceBuds Z not connected",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(status)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (!hasBluetoothConnectPermission(activity)) {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.BLUETOOTH_CONNECT
                                            )
                                        )
                                    } else {
                                        controller.refresh()
                                    }
                                }) { Text("Refresh") }

                                OutlinedButton(onClick = {
                                    runCatching {
                                        activity.startActivity(
                                            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                        )
                                    }
                                }) { Text("Bluetooth settings") }
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
                                    Slider(
                                        value = bass,
                                        onValueChange = { bass = it },
                                        enabled = false
                                    )
                                    Text("Device bass control is unavailable until a verified protocol exists")
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
                                "Discovers services and lists characteristic UUIDs and properties. " +
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

@Suppress("DEPRECATION")
private fun Intent.getBluetoothDeviceExtra(): BluetoothDevice? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java
        )
    } else {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
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
            trailingContent = {
                Switch(
                    checked = checked,
                    onCheckedChange = onChecked,
                    enabled = false
                )
            }
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
