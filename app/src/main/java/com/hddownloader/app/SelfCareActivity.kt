package com.hddownloader.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hddownloader.app.databinding.ActivitySelfCareBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Self Care screen — app ki sehat user ke apne haath me:
 * Repair Engine, Health Check (+ FIX ALL), Send Report, Fresh Start.
 * Approved design (v2.1 mockups) wali 4 cards + results card.
 */
class SelfCareActivity : AppCompatActivity() {

    private lateinit var b: ActivitySelfCareBinding

    /** Aakhri health check ka natija — Send Report isi se banta hai. */
    private var lastItems: List<HealthItem> = emptyList()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySelfCareBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.cardRepair.setOnClickListener { if (!busy) doRepair() }
        b.cardHealth.setOnClickListener { if (!busy) runHealthCheck() }
        b.cardReport.setOnClickListener { if (!busy) sendReport() }
        b.cardFresh.setOnClickListener { if (!busy) confirmFreshStart() }
        b.btnFixAll.setOnClickListener { if (!busy) doRepair() }

        // Screen khulte hi halki si sehat-report khud chala do
        runHealthCheck()
    }

    // ------------------------------------------------------------ health check

    private fun runHealthCheck() {
        setBusy(true, "Check ho raha hai…")
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val list = YtEngine.healthChecks(applicationContext).toMutableList()
                list += HealthItem(
                    "Internet", isInternetOn(),
                    if (isInternetOn()) "connection maujood hai" else "internet band hai"
                )
                list += HealthItem("App version", true, appVersionName())
                list
            }
            lastItems = items
            renderChecks(items)
            setBusy(false, null)
        }
    }

    private fun renderChecks(items: List<HealthItem>) {
        b.llChecks.removeAllViews()
        val density = resources.displayMetrics.density
        items.forEachIndexed { i, item ->
            val tv = TextView(this).apply {
                text = (if (item.ok) "✅  " else "⚠️  ") + item.label + " — " + item.detail
                setTextColor(Color.parseColor("#14202B"))
                textSize = 15f
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            }
            b.llChecks.addView(tv)
            if (i < items.lastIndex) {
                val div = View(this).apply {
                    setBackgroundColor(Color.parseColor("#E3EAF4"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt()
                    )
                }
                b.llChecks.addView(div)
            }
        }
        b.cardResults.visibility = View.VISIBLE
        // FIX ALL sirf tab jab engine wali koi cheez kharab ho (internet/version
        // repair se theek nahi hote — un par button dikhana jhoot hoga)
        val engineBad = items.any {
            !it.ok && it.label != "Internet" && it.label != "App version"
        }
        b.btnFixAll.visibility = if (engineBad) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ repair / fresh

    private fun doRepair() {
        setBusy(true, "Repair ho raha hai… thora intezar karo")
        lifecycleScope.launch {
            var repairError: String? = null
            withContext(Dispatchers.IO) {
                try {
                    YtEngine.repair(applicationContext)
                } catch (e: Exception) {
                    repairError = e.message ?: e.javaClass.simpleName
                }
            }
            val items = withContext(Dispatchers.IO) {
                YtEngine.healthChecks(applicationContext)
            }
            lastItems = items
            renderChecks(items)
            val stillBad = items.any { !it.ok }
            setBusy(false, null)
            when {
                repairError != null ->
                    toast("Repair me masla aya: $repairError — Send Report se report bhej do")
                stillBad ->
                    toast("Repair ho gaya, lekin kuch cheez abhi bhi theek nahi — report bhej do")
                else ->
                    toast("Repair complete ✓ — sab theek hai")
            }
        }
    }

    private fun confirmFreshStart() {
        AlertDialog.Builder(this)
            .setTitle("Fresh Start?")
            .setMessage(
                "Engine ka saara data saaf karke bilkul fresh engine banega. " +
                    "Tumhari app settings mehfooz rahengi. Pakka karun?"
            )
            .setPositiveButton("Fresh Start") { _, _ -> doFreshStart() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doFreshStart() {
        setBusy(true, "Fresh Start ho raha hai… thora intezar karo")
        lifecycleScope.launch {
            var err: String? = null
            withContext(Dispatchers.IO) {
                try {
                    YtEngine.freshStart(applicationContext)
                } catch (e: Exception) {
                    err = e.message ?: e.javaClass.simpleName
                }
            }
            setBusy(false, null)
            if (err != null) {
                toast("Fresh Start me masla aya: $err")
            } else {
                toast("Fresh Start ho gaya ✓ — engine bilkul naya hai")
            }
            runHealthCheck()
        }
    }

    // ------------------------------------------------------------ report

    private fun sendReport() {
        setBusy(true, "Report tayyar ho raha hai…")
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                sb.append("Expert Solutions Downloader — Report\n")
                sb.append("App version: ").append(appVersionName()).append("\n")
                sb.append("Phone: ").append(Build.MANUFACTURER).append(" ")
                    .append(Build.MODEL).append("\n")
                sb.append("Android: ").append(Build.VERSION.RELEASE).append("\n\n")
                try {
                    val diag = YtEngine.diagnostics(applicationContext)
                    if (diag.isNotBlank()) sb.append(diag).append("\n\n")
                } catch (_: Exception) { }
                sb.append("Health check:\n")
                val items = if (lastItems.isNotEmpty()) lastItems
                else YtEngine.healthChecks(applicationContext)
                for (item in items) {
                    sb.append(if (item.ok) "OK  " else "BAD ")
                        .append(item.label).append(" — ").append(item.detail).append("\n")
                }
                sb.toString().take(4000)
            }
            setBusy(false, null)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Expert Solutions Downloader — Report")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            try {
                startActivity(Intent.createChooser(intent, "Send Report"))
            } catch (_: Exception) {
                toast("Share karne wala koi app nahi mila")
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun setBusy(on: Boolean, status: String?) {
        busy = on
        b.progressSelf.visibility = if (on) View.VISIBLE else View.GONE
        if (status != null) {
            b.tvSelfStatus.text = status
            b.tvSelfStatus.visibility = View.VISIBLE
        } else {
            b.tvSelfStatus.visibility = View.GONE
        }
        val alpha = if (on) 0.55f else 1f
        b.cardRepair.alpha = alpha
        b.cardHealth.alpha = alpha
        b.cardReport.alpha = alpha
        b.cardFresh.alpha = alpha
        b.btnFixAll.isEnabled = !on
    }

    private fun isInternetOn(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun appVersionName(): String {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
