package co.logos.noderemote

import android.os.Bundle
import android.widget.ImageButton
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import androidx.appcompat.app.AppCompatActivity

/**
 * Portrait scanner with a title and a back button.
 *
 * ZXing's stock CaptureActivity is declared landscape in the library manifest and shows
 * no chrome, so the user lands in a rotated viewfinder with no obvious way out. This
 * replaces it: same CaptureManager, our own layout, portrait pinned in OUR manifest.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var capture: CaptureManager
    private lateinit var barcodeView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        barcodeView = findViewById(R.id.zxing_barcode_scanner)
        // The stock status text sits under the viewfinder and duplicates our own hint.
        barcodeView.setStatusText("")

        findViewById<ImageButton>(R.id.scan_back).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()
    }

    override fun onResume() { super.onResume(); capture.onResume() }
    override fun onPause() { super.onPause(); capture.onPause() }
    override fun onDestroy() { super.onDestroy(); capture.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); capture.onSaveInstanceState(outState)
    }
}
