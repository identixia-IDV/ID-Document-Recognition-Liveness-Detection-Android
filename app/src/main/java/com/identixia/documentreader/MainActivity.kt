package com.identixia.documentreader

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.identixia.documentreadersdk.DocumentReaderSDK

/**
 * Sample host for [com.identixia.documentreadersdk.DocumentReaderSDK].
 *
 * Init (background thread): [DocumentReaderSDK.getMachineCode] → [DocumentReaderSDK.setActivation] → [DocumentReaderSDK.init].
 * Replace [LICENSE_KEY] with an `…` key issued for **your** `applicationId`.
 *
 * Home: wide Camera + Gallery / About tiles
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** `…` from Identixia for this app's `applicationId`. */
        private const val LICENSE_KEY =
            "pyyR2AECrdslO86QjttubhRKYJPJkY2yUVyxB8Sl3FZPQWIAAACcXtjl3U11LDEkp9Euqau6IQSeG5leKWraCqutz0r/HIv3Gxc6Y6bU7ICiG67b8344qASs5PnTyPDtG9/6L5QDkychtASXMh2qTpQeOC/P6fhJpf9mCCaSTYh1NWrlgGCyCmcAMGUCMQCyTmnLzoQfqLTNXYbJRyi3AoommNS5g2BFtjQQmWnDvKIHnCQEI+nEt1T7zxDBdNMCMHUPQ8jdH9Tx29zlkve8nWPUG2/8k/4hQmpMTBsHLp0NejIUv+nwyj+L0G3Y7dFd7A=="
    }

    private lateinit var txtStatusNotification: TextView
    private var loadingDialog: AlertDialog? = null
    private var sdkReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        txtStatusNotification = findViewById(R.id.txtStatusNotification)
        findViewById<TextView>(R.id.txtLicenseChip).text = getString(R.string.about_license_loading)

        findViewById<View>(R.id.cardCamera).setOnClickListener {
            if (!ensureReady()) return@setOnClickListener
            startActivity(Intent(this, CameraActivity::class.java))
        }
        findViewById<View>(R.id.cardGallery).setOnClickListener {
            if (!ensureReady()) return@setOnClickListener
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        findViewById<View>(R.id.cardAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        updateStatus(getString(R.string.sdk_loading), R.color.status_info)

        Thread {
            try {
                // License is bound to applicationId. Init loads the engine — keep off the UI thread.
                val machine = DocumentReaderSDK.getMachineCode(this) ?: ""
                val act = DocumentReaderSDK.setActivation(this, LICENSE_KEY)
                val init = if (act == DocumentReaderSDK.SDK_SUCCESS) DocumentReaderSDK.init(this) else act
                sdkReady = init == DocumentReaderSDK.SDK_SUCCESS
                android.util.Log.i("DocumentReaderSDKDemo", "machine=$machine init=$init ready=$sdkReady")
                runOnUiThread {
                    if (sdkReady) {
                        val label = try {
                            LicenseStatus.current().label
                        } catch (_: Throwable) {
                            ""
                        }
                        findViewById<TextView>(R.id.txtLicenseChip).text =
                            if (label.isNotBlank()) getString(R.string.about_license_fmt, label)
                            else getString(R.string.about_license_loading)
                        updateStatus(getString(R.string.sdk_ready), R.color.status_ok)
                    } else {
                        val msg = when (init) {
                            1 -> getString(R.string.sdk_license_invalid)
                            2 -> getString(R.string.sdk_license_expired)
                            3 -> getString(R.string.sdk_not_activated)
                            4 -> getString(R.string.sdk_init_failed)
                            5 -> getString(R.string.sdk_no_database)
                            6 -> getString(R.string.sdk_database_load_error)
                            else -> getString(R.string.sdk_failed) + ": " + init
                        }
                        updateStatus(msg, R.color.status_error)
                    }
                    maybeSelfTest()
                }
            } catch (t: Throwable) {
                android.util.Log.e("DocumentReaderSDKDemo", "init", t)
                runOnUiThread {
                    updateStatus(getString(R.string.sdk_failed) + ": " + (t.message ?: "Engine failed to start"), R.color.status_error)
                }
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeSelfTest()
    }

    private fun updateStatus(message: String, colorResId: Int) {
        txtStatusNotification.text = message
        val color = ContextCompat.getColor(this, colorResId)
        txtStatusNotification.background?.mutate()?.setTint(color)
        txtStatusNotification.setTextColor(ContextCompat.getColor(this, R.color.ix_on_accent))
    }

    private fun ensureReady(): Boolean {
        if (sdkReady) return true
        Toast.makeText(this, R.string.sdk_failed, Toast.LENGTH_SHORT).show()
        return false
    }

    /** Hidden: adb … --es process_path /sdcard/id.jpg
     *  Two-page: --es process_front … --es process_back …
     *  Locate only: --es locate_path /sdcard/id.jpg */
    private fun maybeSelfTest() {
        if (!sdkReady) return
        val quiet = intent?.getStringExtra("selftest_quiet") == "1"
        val locatePath = intent?.getStringExtra("locate_path")
        if (locatePath != null) {
            intent?.removeExtra("locate_path")
            val bmp = BitmapFactory.decodeFile(locatePath)
            if (bmp == null) {
                android.util.Log.e("DocumentReaderSDKDemo", "locate could not decode $locatePath")
                return
            }
            Thread {
                val json = try {
                    DocumentReaderSDK.locateDocument(bmp)
                } catch (t: Throwable) {
                    "{\"msg\":\"${t.message}\"}"
                }
                val pct = ResultParser.documentPercent(json)
                val side = ResultParser.documentSide(json)
                val name = try {
                    org.json.JSONObject(json.trim()).let { root ->
                        val ident = root.optJSONObject("identity") ?: root.optJSONObject("document")
                        ident?.optString("class")?.takeIf { it.isNotBlank() }
                            ?: ident?.optString("type")?.takeIf { it.isNotBlank() }
                            ?: root.optString("documentName", "")
                    }
                } catch (_: Exception) {
                    ""
                }
                val corners = ResultParser.documentCorners(json) != null
                android.util.Log.i(
                    "DocumentReaderSDKDemo",
                    "locate path=$locatePath score=$pct side=$side name=$name corners=$corners head=${json.take(280)}"
                )
            }.start()
            return
        }
        val frontPath = intent?.getStringExtra("process_front")
            ?: intent?.getStringExtra("process_path")
            ?: return
        val backPath = intent?.getStringExtra("process_back")
        intent?.removeExtra("process_path")
        intent?.removeExtra("process_front")
        intent?.removeExtra("process_back")
        val front = BitmapFactory.decodeFile(frontPath)
        if (front == null) {
            android.util.Log.e("DocumentReaderSDKDemo", "self-test could not decode $frontPath")
            return
        }
        val back = backPath?.let { BitmapFactory.decodeFile(it) }
        if (!quiet) showDialog("Processing image")
        Thread {
            val (ocr, deny) = try {
                DocSdkSession.recognize(this, front, back)
            } catch (t: Throwable) {
                "{\"msg\":\"${t.message}\"}" to null
            }
            val name = try {
                    org.json.JSONObject(ocr.trim()).let { root ->
                    val ident = root.optJSONObject("identity") ?: root.optJSONObject("document")
                    ident?.optString("class")?.takeIf { it.isNotBlank() }
                        ?: ident?.optString("type")?.takeIf { it.isNotBlank() }
                        ?: root.optString("documentName", "")
                }
            } catch (_: Exception) {
                ""
            }
            android.util.Log.i(
                "DocumentReaderSDKDemo",
                "recognize path=$frontPath back=${backPath ?: "-"} len=${ocr.length} " +
                    "fields=${ocr.contains("\"fields\"")} checks=${ocr.contains("\"checks\"")} " +
                    "name=$name deny=${deny ?: "-"} head=${ocr.take(240)}"
            )
            runOnUiThread {
                if (!quiet) {
                    dismissDialog()
                    if (!deny.isNullOrBlank()) {
                        Toast.makeText(this, deny, Toast.LENGTH_LONG).show()
                    }
                    ResultActivity.open(this, ocr)
                }
            }
        }.start()
    }

    private fun showDialog(msg: String) {
        if (loadingDialog?.isShowing == true) return
        loadingDialog = AlertDialog.Builder(this)
            .setMessage(msg)
            .setCancelable(false)
            .show()
    }

    private fun dismissDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            try {
                // Unload native engine when this activity is actually finishing.
                DocumentReaderSDK.deinit()
            } catch (_: Throwable) {
            }
        }
    }
}
