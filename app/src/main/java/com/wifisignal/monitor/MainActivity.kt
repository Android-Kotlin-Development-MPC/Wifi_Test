package com.wifisignal.monitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wifisignal.monitor.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiManager: WifiManager
    private var pollJob: Job? = null
    private var rssiHistory = ArrayDeque<Int>()
    private var ringtone: Ringtone? = null
    private var lowAlertShown = false

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.RSSI_CHANGED_ACTION,
                WifiManager.WIFI_STATE_CHANGED_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION
            )
        }

        binding.btnRefresh.setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        registerReceiver(wifiReceiver, filter)
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(wifiReceiver)
        pollJob?.cancel()
        stopAlertTone()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) refresh()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                refresh()
                delay(1000L)
            }
        }
    }

    private fun refresh() {
        if (!hasLocationPermission()) {
            binding.tvSsid.text = getString(R.string.permission_needed)
            return
        }

        @Suppress("MissingPermission")
        val info = wifiManager.connectionInfo ?: run {
            setDisconnected()
            return
        }

        val ssid = info.ssid?.removeSurrounding("\"") ?: ""
        if (ssid.isEmpty() || ssid == "<unknown ssid>") {
            setDisconnected()
            return
        }

        val rssi = info.rssi
        rssiHistory.addLast(rssi)
        if (rssiHistory.size > 30) rssiHistory.removeFirst()
        val avgRssi = rssiHistory.average().toInt()

        val percent = signalPercent(rssi)

        binding.tvSsid.text = ssid
        binding.tvBssid.text = getString(R.string.bssid_fmt, info.bssid ?: "-")
        binding.tvRssi.text = getString(R.string.rssi_fmt, rssi, avgRssi)
        binding.tvStrength.text = getString(R.string.strength_fmt, percent)
        binding.tvSpeed.text = getString(R.string.speed_fmt, info.linkSpeed)
        binding.tvFrequency.text = getString(
            R.string.freq_fmt,
            info.frequency,
            bandName(info.frequency)
        )
        binding.tvIp.text = getString(R.string.ip_fmt, ipToString(info.ipAddress))
        binding.tvChannel.text = getString(R.string.channel_fmt, channelFromFreq(info.frequency))

        val level = WifiManager.calculateSignalLevel(rssi, 5)
        binding.tvQuality.text = qualityLabel(level)
        binding.tvQuality.setTextColor(qualityColor(level))
        renderBars(level)
        checkLowSignal(percent)

        binding.cardConnected.visibility = View.VISIBLE
        binding.tvStatus.setText(R.string.status_connected)
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_green_dark)
        )
    }

    private fun setDisconnected() {
        binding.cardConnected.visibility = View.GONE
        binding.tvSsid.setText(R.string.not_connected)
        binding.tvRssi.setText(R.string.dash)
        binding.tvStrength.setText(R.string.dash)
        binding.tvSpeed.setText(R.string.dash)
        binding.tvFrequency.setText(R.string.dash)
        binding.tvBssid.setText(R.string.dash)
        binding.tvIp.setText(R.string.dash)
        binding.tvChannel.setText(R.string.dash)
        binding.tvQuality.setText(R.string.dash)
        binding.barsContainer.removeAllViews()
        binding.tvStatus.setText(R.string.status_disconnected)
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
    }

    private fun signalPercent(rssi: Int): Int = ((rssi + 100) * 2).coerceIn(0, 100)

    private fun checkLowSignal(percent: Int) {
        val below = percent < LOW_SIGNAL_THRESHOLD
        if (below && !lowAlertShown) {
            lowAlertShown = true
            binding.tvStrength.setTextColor(
                ContextCompat.getColor(this, R.color.quality_weak)
            )
            Toast.makeText(
                this,
                getString(R.string.low_signal_toast, percent),
                Toast.LENGTH_LONG
            ).show()
            playAlertTone()
        } else if (!below) {
            if (lowAlertShown) stopAlertTone()
            lowAlertShown = false
            binding.tvStrength.setTextColor(
                ContextCompat.getColor(this, R.color.quality_good)
            )
        }
    }

    private fun playAlertTone() {
        stopAlertTone()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri != null) {
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        }
    }

    private fun stopAlertTone() {
        ringtone?.let {
            if (it.isPlaying) it.stop()
        }
        ringtone = null
    }

    private fun renderBars(level: Int) {
        binding.barsContainer.removeAllViews()
        for (i in 1..4) {
            val bar = View(this)
            val lp = android.widget.LinearLayout.LayoutParams(
                resources.displayMetrics.widthPixels / 20,
                i * resources.displayMetrics.heightPixels / 40
            ).apply {
                marginEnd = (4 * resources.displayMetrics.density).toInt()
                gravity = android.view.Gravity.BOTTOM
            }
            bar.layoutParams = lp
            bar.setBackgroundColor(
                if (i <= level) qualityColor(level)
                else ContextCompat.getColor(this, R.color.bar_inactive)
            )
            binding.barsContainer.addView(bar)
        }
    }

    private fun qualityLabel(level: Int): String = when (level) {
        4 -> getString(R.string.quality_excellent)
        3 -> getString(R.string.quality_good)
        2 -> getString(R.string.quality_fair)
        1 -> getString(R.string.quality_weak)
        else -> getString(R.string.quality_none)
    }

    private fun qualityColor(level: Int): Int = ContextCompat.getColor(
        this,
        when (level) {
            4 -> R.color.quality_excellent
            3 -> R.color.quality_good
            2 -> R.color.quality_fair
            else -> R.color.quality_weak
        }
    )

    private fun bandName(freqMhz: Int): String = when {
        freqMhz in 2400..2500 -> "2.4 GHz"
        freqMhz in 4900..5900 -> "5 GHz"
        freqMhz >= 5925 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "6 GHz"
        else -> "?"
    }

    private fun channelFromFreq(freqMhz: Int): Int = when {
        freqMhz in 2412..2484 -> (freqMhz - 2412) / 5 + 1
        freqMhz in 5170..5825 -> (freqMhz - 5170) / 5 + 34
        freqMhz >= 5955 -> (freqMhz - 5955) / 5 + 1
        else -> 0
    }

    private fun ipToString(ip: Int): String =
        if (ip == 0) "-" else String.format(
            "%d.%d.%d.%d",
            ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF
        )

    companion object {
        private const val REQUEST_LOCATION = 1001
        private const val LOW_SIGNAL_THRESHOLD = 20
    }
}
