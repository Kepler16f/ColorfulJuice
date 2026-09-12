package com.colorfuljuice.bluetoothbattery.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.colorfuljuice.bluetoothbattery.R

class WidgetConfigureActivity : AppCompatActivity() {

    private var widgetId = -1
    private var widgetMode = "1x3"
    private var selectedAddresses = mutableListOf<String>()
    private lateinit var adapter: DeviceListAdapter
    private lateinit var btnConfirm: Button

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_select)

        // Determine widget type from intent action
        widgetMode = when {
            intent.action?.contains("2X2_DUAL") == true -> "2x2_dual"
            intent.action?.contains("2X2_SINGLE") == true -> "2x2_single"
            intent.action?.contains("1X3") == true -> "1x3"
            else -> intent.getStringExtra("widget_mode") ?: "1x3"
        }
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)

        if (widgetId == -1) {
            finish()
            return
        }

        val title = findViewById<TextView>(R.id.select_title)
        val btnCancel = findViewById<Button>(R.id.btn_cancel)
        btnConfirm = findViewById(R.id.btn_confirm)

        val maxSelect = if (widgetMode == "2x2_dual") 2 else 1
        title.text = if (maxSelect == 2) "选择两个设备" else "选择设备"

        val devices = getPairedDevices()

        adapter = DeviceListAdapter(devices, maxSelect) { selected ->
            selectedAddresses = selected.toMutableList()
            btnConfirm.isEnabled = selected.isNotEmpty()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.device_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        btnConfirm.setOnClickListener {
            if (selectedAddresses.isEmpty()) {
                Toast.makeText(this, "请选择设备", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save config via broadcast
            val action = when (widgetMode) {
                "1x3" -> Widget1x3.ACTION_CONFIGURE
                "2x2_single" -> Widget2x2Single.ACTION_CONFIGURE
                "2x2_dual" -> Widget2x2Dual.ACTION_CONFIGURE
                else -> Widget1x3.ACTION_CONFIGURE
            }

            val broadcastIntent = Intent(this, Widget1x3::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                when (widgetMode) {
                    "2x2_dual" -> {
                        putExtra("device_address_1", selectedAddresses.getOrNull(0))
                        putExtra("device_address_2", selectedAddresses.getOrNull(1))
                    }
                    else -> {
                        putExtra("device_address", selectedAddresses.firstOrNull())
                    }
                }
            }

            // Also save directly
            when (widgetMode) {
                "1x3" -> {
                    WidgetHelper.saveDeviceAddress(this, widgetId, selectedAddresses.firstOrNull())
                    WidgetHelper.saveWidgetType(this, widgetId, "1x3")
                }
                "2x2_single" -> {
                    WidgetHelper.saveDeviceAddress(this, widgetId, selectedAddresses.firstOrNull())
                    WidgetHelper.saveWidgetType(this, widgetId, "2x2_single")
                }
                "2x2_dual" -> {
                    WidgetHelper.getPrefs(this).edit()
                        .putString("widget_${widgetId}_1", selectedAddresses.getOrNull(0))
                        .putString("widget_${widgetId}_2", selectedAddresses.getOrNull(1))
                        .apply()
                    WidgetHelper.saveWidgetType(this, widgetId, "2x2_dual")
                }
            }

            // Notify the widget to update
            val updateIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                val componentName = when (widgetMode) {
                    "1x3" -> android.content.ComponentName(this@WidgetConfigureActivity, Widget1x3::class.java)
                    "2x2_single" -> android.content.ComponentName(this@WidgetConfigureActivity, Widget2x2Single::class.java)
                    "2x2_dual" -> android.content.ComponentName(this@WidgetConfigureActivity, Widget2x2Dual::class.java)
                    else -> android.content.ComponentName(this@WidgetConfigureActivity, Widget1x3::class.java)
                }
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
                component = componentName
            }
            sendBroadcast(updateIntent)

            val resultValue = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            setResult(RESULT_OK, resultValue)
            Toast.makeText(this, "设备已添加到桌面", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getPairedDevices(): List<PairedDeviceInfo> {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = bluetoothManager?.adapter ?: return emptyList()
        return btAdapter.bondedDevices?.map { device ->
            val name = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    device.alias ?: device.name ?: "Unknown"
                } else {
                    device.name ?: "Unknown"
                }
            } catch (e: Exception) {
                "Unknown"
            }
            val battery = try {
                val method = device.javaClass.getMethod("getBatteryLevel")
                val level = method.invoke(device) as? Int
                if (level != null && level in 0..100) level else null
            } catch (e: Exception) {
                null
            }
            PairedDeviceInfo(device, name, device.address, battery)
        } ?: emptyList()
    }

    data class PairedDeviceInfo(
        val device: BluetoothDevice,
        val name: String,
        val address: String,
        val battery: Int?
    )

    inner class DeviceListAdapter(
        private val devices: List<PairedDeviceInfo>,
        private val maxSelect: Int,
        private val onSelectionChanged: (List<String>) -> Unit
    ) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

        private val selectedPositions = mutableSetOf<Int>()

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.item_icon)
            val name: TextView = itemView.findViewById(R.id.item_name)
            val address: TextView = itemView.findViewById(R.id.item_address)
            val battery: TextView = itemView.findViewById(R.id.item_battery)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_widget_device, parent, false)
            return ViewHolder(view)
        }

        @SuppressLint("MissingPermission")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.name.text = device.name
            holder.address.text = device.address
            holder.battery.text = if (device.battery != null) "${device.battery}%" else "?"

            val type = WidgetHelper.getDeviceType(this@WidgetConfigureActivity, device.address)
            holder.icon.setImageResource(WidgetHelper.getDeviceTypeIconRes(type))

            val isSelected = selectedPositions.contains(position)
            holder.itemView.alpha = if (isSelected) 1.0f else 0.6f
            holder.itemView.setBackgroundColor(
                if (isSelected) 0x204CAF50.toInt() else 0x00000000
            )

            holder.itemView.setOnClickListener {
                if (selectedPositions.contains(position)) {
                    selectedPositions.remove(position)
                } else {
                    if (selectedPositions.size >= maxSelect) {
                        val first = selectedPositions.first()
                        selectedPositions.remove(first)
                    }
                    selectedPositions.add(position)
                }
                notifyDataSetChanged()
                val selected = selectedPositions.map { devices[it].address }
                onSelectionChanged(selected)
            }
        }

        override fun getItemCount() = devices.size
    }
}
