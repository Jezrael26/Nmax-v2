package com.nmax.control

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Phase 1: scan -> connect -> subscribe -> auth handshake -> "AUTHENTICATED".
 * Hango sa SDMV (VehicleTelemetryService): auth packet = 60 bytes, sinusulat sa
 * Nordic-UART RX (6e400002), sagot ng bike ay notify sa 6e400003.
 * Lahat ng callback ay ipinapasa sa main thread, kaya safe i-update ang UI.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class NmaxLink(
    private val ctx: Context,
    private val onLog: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFound: (String, BluetoothDevice) -> Unit,
) {
    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // app -> bike
        val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // bike -> app
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val PREFIXES = listOf("YSCCU_", "YCCU_")
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("nmax", Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var deviceName = ""
    private var mtuPayload = 20
    private var seq = 0
    private var attempt = 0
    private var scanning = false
    private val seen = HashSet<String>()

    // ---- GATT operation queue (isa-isa lang ang puwedeng tumakbo) ----
    private val ops = ArrayDeque<() -> Boolean>()
    private var opRunning = false
    private var opId = 0

    private fun log(s: String) = onLog(s)
    private fun status(s: String) = onStatus(s)

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        if (!opRunning) next()
    }

    private fun next() {
        val op = ops.removeFirstOrNull()
        if (op == null) { opRunning = false; return }
        opRunning = true
        val id = ++opId
        if (!op()) { log("! GATT op failed, next"); next(); return }
        // safety: kung walang callback (hal. write-no-response), tuloy pa rin pagkatapos ng 3s
        main.postDelayed({ if (opRunning && opId == id) { log("! op timeout, next"); next() } }, 3000)
    }

    // ---------------- SCAN ----------------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: try { r.device.name } catch (e: Exception) { null } ?: return
            if (PREFIXES.none { name.uppercase().startsWith(it) }) return
            if (seen.add(r.device.address)) {
                log("Found $name  ${r.device.address}  rssi=${r.rssi}")
                onFound(name, r.device)
            }
        }
        override fun onScanFailed(code: Int) { log("! scan failed code=$code"); status("Scan failed ($code)") }
    }

    fun startScan() {
        if (!adapter.isEnabled) { status("I-on ang Bluetooth muna"); return }
        if (scanning) return
        seen.clear()
        scanning = true
        status("Scanning para sa YCCU_ ...")
        adapter.bluetoothLeScanner.startScan(
            null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        main.postDelayed({ stopScan() }, 15000)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter.bluetoothLeScanner.stopScan(scanCb) } catch (_: Exception) {}
        status("Scan stopped")
    }

    // ---------------- CONNECT ----------------
    fun connect(dev: BluetoothDevice) {
        stopScan()
        disconnect()
        deviceName = try { dev.name ?: "" } catch (_: Exception) { "" }
        attempt = 0
        log("Connecting ${dev.address} ($deviceName) bond=${dev.bondState}")
        status("Connecting...")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) status("connectGatt returned null")
    }

    fun disconnect() {
        ops.clear(); opRunning = false
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                log("connectionState status=$st new=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status("Connected (GATT). Requesting MTU...")
                    if (!g.requestMtu(247)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    status("Disconnected (status=$st)")
                    ops.clear(); opRunning = false
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            main.post {
                if (st == BluetoothGatt.GATT_SUCCESS) mtuPayload = maxOf(20, mtu - 3)
                log("MTU=$mtu status=$st payload=$mtuPayload")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                log("servicesDiscovered status=$st")
                for (s in g.services) {
                    log("SVC ${s.uuid}")
                    for (c in s.characteristics) log("   CHR ${c.uuid} props=${props(c.properties)}")
                }
                val svc = g.getService(NUS_SERVICE)
                val tx = svc?.getCharacteristic(NUS_TX)
                val rx = svc?.getCharacteristic(NUS_RX)
                if (tx == null || rx == null) {
                    status("Walang NUS service (6e400001) sa bike. Tingnan ang log.")
                    return@post
                }
                status("Subscribing...")
                enqueue {
                    g.setCharacteristicNotification(tx, true)
                    val d = tx.getDescriptor(CCCD)
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
                sendAuth(g, rx, updateBonding = !prefs.getBoolean("authed_before", false))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post { log("descriptorWrite ${d.characteristic.uuid} status=$st"); next() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post { log("write done status=$st"); next() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            main.post { handleNotify(g, c.uuid, v) }
        }
    }

    // ---------------- AUTH ----------------
    private fun phoneUuid(): String {
        var u = prefs.getString("phone_uuid", null)
        if (u == null) {
            u = UUID.randomUUID().toString().replace("-", "").lowercase()
            prefs.edit().putString("phone_uuid", u).apply()
        }
        return u
    }

    /** "YCCU_<id>" -> 14-char CCU ID (pad ng '0'), gaya ng SDMV v0() */
    private fun ccuId(): String {
        val up = deviceName.uppercase()
        val id = when {
            up.startsWith("YSCCU_") -> deviceName.substring(6)
            up.startsWith("YCCU_") -> deviceName.substring(5)
            else -> ""
        }
        return if (id.length > 14) id.substring(0, 14) else id.padEnd(14, '0')
    }

    fun buildAuth(updateBonding: Boolean): ByteArray {
        val p = ByteArray(60)
        p[0] = 0xAA.toByte(); p[1] = 0x01; p[2] = 0x7F; p[3] = 0x00; p[4] = 0x35
        fun put(off: Int, len: Int, s: String?) {
            val b = s?.toByteArray(Charsets.US_ASCII)
            for (i in 0 until len) p[off + i] = if (b != null && i < b.size) b[i] else 0x30 // '0' fill
        }
        put(5, 14, if (updateBonding) ccuId() else null)  // CCU ID (kapag updateBonding lang)
        put(19, 6, null)                                  // 6-byte field: hindi pa alam, '0' muna
        put(25, 32, phoneUuid())                          // phone UUID (32 hex chars)
        p[57] = if (updateBonding) 1 else 0
        p[58] = (seq++ and 0xFF).toByte()
        var sum = 0
        for (i in 0 until 59) sum += p[i].toInt() and 0xFF
        p[59] = ((0x100 - (sum and 0xFF)) and 0xFF).toByte()
        return p
    }

    private fun sendAuth(g: BluetoothGatt, rx: BluetoothGattCharacteristic, updateBonding: Boolean) {
        val pkt = buildAuth(updateBonding)
        log("AUTH (updateBonding=$updateBonding, attempt=$attempt): ${hex(pkt)}")
        val noRsp = (rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 &&
            (rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
        var off = 0
        while (off < pkt.size) {
            val chunk = pkt.copyOfRange(off, minOf(pkt.size, off + mtuPayload))
            off += chunk.size
            enqueue {
                rx.writeType = if (noRsp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                               else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                rx.value = chunk
                g.writeCharacteristic(rx)
            }
        }
        status("Authenticating...")
    }

    private fun handleNotify(g: BluetoothGatt, uuid: UUID, v: ByteArray) {
        log("NOTIFY $uuid: ${hex(v)}")
        if (v.isEmpty() || (v[0].toInt() and 0xFF) != 0x5A) return
        // hanapin ang 7F 01; ang flag ay nasa i+3 (gaya ng SDMV l0())
        var i = 1
        while (i < v.size - 2) {
            if ((v[i].toInt() and 0xFF) == 0x7F && v[i + 1].toInt() == 1) break
            i++
        }
        if (i >= v.size - 2 || i + 3 >= v.size) { log("! 0x5A response pero walang 7F 01 marker"); return }
        val flag = v[i + 3].toInt() and 0xFF
        log("startProcessingFlg=$flag")
        if (flag == 1) {
            prefs.edit().putBoolean("authed_before", true).apply()
            status("AUTHENTICATED ✅")
        } else if (attempt == 0) {
            attempt = 1
            log("Auth failed, retry once with updateBonding=true")
            val rx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_RX)
            if (rx != null) sendAuth(g, rx, updateBonding = true)
        } else {
            status("Auth FAILED (flag=$flag). Tingnan ang log; baka kailangan ng approval sa bike display.")
        }
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
    private fun props(p: Int): String {
        val l = ArrayList<String>()
        if (p and 0x02 != 0) l += "READ"
        if (p and 0x04 != 0) l += "WRITE_NR"
        if (p and 0x08 != 0) l += "WRITE"
        if (p and 0x10 != 0) l += "NOTIFY"
        if (p and 0x20 != 0) l += "INDICATE"
        return l.joinToString("|")
    }
}
