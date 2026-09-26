package com.hyouka.spacebudsz

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

data class GattRow(val service: String, val characteristic: String, val readable: Boolean, val writable: Boolean, val notifiable: Boolean)

class SpaceBudsController(private val activity: ComponentActivity) {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val _device = MutableStateFlow<BluetoothDevice?>(null)
    val device = _device.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _gatt = MutableStateFlow<List<GattRow>>(emptyList())
    val gatt = _gatt.asStateFlow()
    private var gattConnection: BluetoothGatt? = null

    private fun canConnect() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun findPaired() {
        if (!canConnect()) return
        _device.value = adapter?.bondedDevices?.firstOrNull {
            val name = it.name ?: return@firstOrNull false
            name.contains("SpaceBuds", true) || name.contains("OTW-625", true)
        }
    }

    fun connect() {
        val target = _device.value ?: return
        if (!canConnect()) return
        gattConnection?.close()
        gattConnection = target.connectGatt(activity, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gattConnection?.disconnect()
        gattConnection?.close()
        gattConnection = null
        _connected.value = false
        _gatt.value = emptyList()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            _connected.value = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
            if (_connected.value) gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            _gatt.value = gatt.services.flatMap { service ->
                service.characteristics.map { c ->
                    GattRow(
                        service.uuid.toString(),
                        c.uuid.toString(),
                        c.properties and 0x02 != 0,
                        c.properties and 0x08 != 0 || c.properties and 0x10 != 0,
                        c.properties and 0x10 != 0
                    )
                }
            }
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
    val gatt by controller.gatt.collectAsState()
    var page by remember { mutableIntStateOf(0) }
    var anc by remember { mutableStateOf(false) }
    var transparency by remember { mutableStateOf(false) }
    var game by remember { mutableStateOf(false) }
    var spatial by remember { mutableStateOf(false) }
    var bass by remember { mutableFloatStateOf(0.5f) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { controller.findPaired() }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("SpaceBuds Z") }, navigationIcon = { Icon(Icons.Default.Headphones, null) }) },
            bottomBar = {
                NavigationBar {
                    val labels = listOf("Home", "Sound", "Device", "GATT")
                    val icons = listOf(Icons.Default.Headphones, Icons.Default.Settings, Icons.Default.Bluetooth, Icons.Default.Info)
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
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    ElevatedCard {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(device?.name ?: "SpaceBuds Z not detected", style = MaterialTheme.typography.headlineSmall)
                            Text(if (connected) "Connected" else "Not connected")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (!canRequestBluetooth(activity)) {
                                        permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                                    } else controller.findPaired()
                                }) { Text("Find") }
                                Button(enabled = device != null && !connected, onClick = controller::connect) { Text("Connect") }
                                OutlinedButton(enabled = connected, onClick = controller::disconnect) { Text("Disconnect") }
                            }
                        }
                    }
                }
                when (page) {
                    0 -> {
                        item { Text("Controls", style = MaterialTheme.typography.titleLarge) }
                        item { ControlCard("ANC", "Up to 30 dB advertised noise reduction", anc) { anc = it } }
                        item { ControlCard("Transparency", "Control surface; vendor command protocol is not public", transparency) { transparency = it } }
                        item { ControlCard("Game Mode", "Low-latency mode advertised by Oraimo", game) { game = it } }
                        item { ControlCard("Sound360", "Spatial audio", spatial) { spatial = it } }
                        item { ControlCard("HavyBass", "Bass tuning profile", bass > 0.5f) { bass = if (it) 0.75f else 0.5f } }
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
                        item { ControlCard("Sound360", "Spatial audio", spatial) { spatial = it } }
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
                        item { Text("GATT diagnostics", style = MaterialTheme.typography.titleLarge) }
                        item { Text("Reads services and characteristics exposed by the connected device. No undocumented vendor commands are sent.") }
                        gatt.forEach { row ->
                            item {
                                ElevatedCard {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(row.service, style = MaterialTheme.typography.labelSmall)
                                        Text(row.characteristic)
                                        Text("read=" + row.readable + "  write=" + row.writable + "  notify=" + row.notifiable, style = MaterialTheme.typography.bodySmall)
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

private fun canRequestBluetooth(activity: ComponentActivity): Boolean =
    ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ControlCard(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
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
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) })
    }
}
