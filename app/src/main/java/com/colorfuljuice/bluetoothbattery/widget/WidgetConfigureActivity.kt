package com.colorfuljuice.bluetoothbattery.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
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

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var widgetMode = "1x3"
    private var selectedAddresses = mutableListOf<String>()
    private lateinit var adapter: DeviceListAdapter
    private lateinit var btnConfirm: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Required: set result to CANCELED first so widget isn't added if user cancels
        setResult(RESULT_CANCELED)

        setContentView(R.layout.activity_widget_select)

        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Determine widget type from the provider that is being configured
        widgetMode = resolveWidgetMode(widgetId)

        val title = findViewById<TextView>(R.id.select_title)
        val btnCancel = findViewById<Button>(R.id.btn_cancel)
        btnConfirm = findViewById(R.id.btn_confirm)

        val maxSelect = if (widgetMode == "2x2_dual") 2 else 1
        title.text = if (maxSelect == 2) {
            getString(R.string.widget_select_two_devices)
        } else {
            getString(R.string.widget_select_device)
        }
        btnCancel.text = getString(R.string.widget_cancel)
        btnConfirm.text = getString(R.string.widget_confirm)

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
                Toast.makeText(this, getString(R.string.widget_select_device), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveConfiguration()
        }
    }

    private fun resolveWidgetMode(widgetId: Int): String {
        return try {
            val manager = AppWidgetManager.getInstance(this)
            val info = manager.getAppWidgetInfo(widgetId)
            val provider = info?.provider?.className ?: ""
            when {
                provider.contains("Widget2x2Dual") -> "2x2_dual"
                provider.contains("Widget2x2Single") -> "2x2_single"
                else -> "1x3"
            }
        } catch (e: Exception) {
            "1x3"
        }
    }

    private fun saveConfiguration() {
        when (widgetMode) {
            "2x2_dual" -> {
                WidgetHelper.getPrefs(this).edit()
                    .putString("widget_${widgetId}_1", selectedAddresses.getOrNull(0))
                    .putString("widget_${widgetId}_2", selectedAddresses.getOrNull(1))
                    .apply()
                WidgetHelper.saveWidgetType(this, widgetId, "2x2_dual")
            }
            "2x2_single" -> {
                WidgetHelper.saveDeviceAddress(this, widgetId, selectedAddresses.firstOrNull())
                WidgetHelper.saveWidgetType(this, widgetId, "2x2_single")
            }
            else -> {
                WidgetHelper.saveDeviceAddress(this, widgetId, selectedAddresses.firstOrNull())
                WidgetHelper.saveWidgetType(this, widgetId, "1x3")
            }
        }

        // Trigger a refresh on the correct provider
        val component = when (widgetMode) {
            "2x2_dual" -> ComponentName(this, Widget2x2Dual::class.java)
            "2x2_single" -> ComponentName(this, Widget2x2Single::class.java)
            else -> ComponentName(this, Widget1x3::class.java)
        }
        val updateIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
            this.component = component
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
        sendBroadcast(updateIntent)

        Toast.makeText(this, getString(R.string.widget_added), Toast.LENGTH_SHORT).show()

        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        setResult(RESULT_OK, resultValue)
        finish()
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
                if (isSelected) 0x204CAF50 else 0x00000000
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
