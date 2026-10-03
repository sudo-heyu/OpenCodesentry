package app.opencodesentry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import app.opencodesentry.databinding.ActivityScanBinding
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen QR scanner for the bridge's pairing link.
 *
 * Uses the bundled ML Kit model (no Play Services, no network) so it works on
 * the same ROMs the rest of the app targets. On a hit the URL is returned via
 * [EXTRA_URL]; anything that is not a `/auth/connect/…` link is ignored.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var handled = false
    private var warnedInvalid = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                toast(getString(R.string.scan_permission))
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnClose.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                val provider = runCatching { future.get() }.getOrNull()
                if (provider == null) {
                    toast(getString(R.string.scan_no_camera))
                    finish()
                    return@addListener
                }
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.onFailure {
                    toast(getString(R.string.scan_no_camera))
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (handled || media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        BarcodeScanning.getClient()
            .process(image)
            .addOnSuccessListener { barcodes ->
                if (handled) return@addOnSuccessListener
                val link = barcodes.firstNotNullOfOrNull { barcode ->
                    barcode.rawValue?.let(Pairing::parse)
                }
                if (link != null) {
                    handled = true
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, link.url))
                    finish()
                } else if (!warnedInvalid && barcodes.any { !it.rawValue.isNullOrBlank() }) {
                    warnedInvalid = true
                    toast(getString(R.string.scan_invalid))
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val EXTRA_URL = "pairing_url"
    }
}
