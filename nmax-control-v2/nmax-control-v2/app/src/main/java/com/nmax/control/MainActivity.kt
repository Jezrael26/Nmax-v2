package com.nmax.control

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class MainActivity : Activity() {
    private lateinit var link: NmaxLink
    private lateinit var statusTv: TextView
    private lateinit var logTv: TextView
    private lateinit var scroll: ScrollView
    private lateinit var adapter: ArrayAdapter<String>
    private val devices = ArrayList<BluetoothDevice>()
    private val labels = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        link = NmaxLink(this,
            onLog = { s -> logTv.append(s + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } },
            onStatus = { s -> statusTv.text = s },
            onFound = { name, dev ->
                devices.add(dev); labels.add("$name  (${dev.address})"); adapter.notifyDataSetChanged()
            })

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        statusTv = TextView(this).apply { text = "Idle"; textSize = 18f }
        root.addView(statusTv)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        row.addView(btn("Scan") { if (ensurePerms()) { devices.clear(); labels.clear(); adapter.notifyDataSetChanged(); link.startScan() } })
        row.addView(btn("Disconnect") { link.disconnect(); statusTv.text = "Disconnected" })
        row.addView(btn("Copy log") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("log", logTv.text))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        })
        root.addView(row)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val list = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setOnItemClickListener { _, _, pos, _ -> link.connect(devices[pos]) }
        }
        root.addView(list)

        scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 2f) }
        logTv = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        scroll.addView(logTv)
        root.addView(scroll)

        setContentView(root)
        ensurePerms()
    }

    private fun ensurePerms(): Boolean {
        val need = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return false }
        return true
    }

    override fun onDestroy() { link.disconnect(); super.onDestroy() }
}
