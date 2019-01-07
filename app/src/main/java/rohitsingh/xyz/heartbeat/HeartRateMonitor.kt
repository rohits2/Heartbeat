package rohitsingh.xyz.heartbeat

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.GridLabelRenderer
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries

const val MAX_DATA_POINTS = 1024
const val CAMERA_REQUEST_CODE = 1021 // literally no reason for this choice
const val SMOOTHING_FACTOR = 0.7f

class HeartRateMonitor : AppCompatActivity(), PulseProvider.HeartbeatListener {
    private val loggingTag: String = "HeartRateMonitor"

    private lateinit var provider: PulseProvider

    private val graph by lazy { findViewById<GraphView>(R.id.graph) }
    private val pulseView by lazy { findViewById<TextView>(R.id.pulse) }
    private val pulseError by lazy { findViewById<TextView>(R.id.pulseError) }
    private val progressCircle by lazy { findViewById<ProgressBar>(R.id.progressBar) }
    private val cameraView by lazy { findViewById<ImageView>(R.id.imageView) }
    private val mmHg = LineGraphSeries<DataPoint>()
    private var movingAverage = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_heart_rate_monitor)
        setupPermissions()
        setupGraphing()
    }

    override fun onPause() {
        super.onPause()
        provider.pause()
        mmHg.resetData(arrayOf())
    }

    override fun onResume() {
        super.onResume()
        provider.resume()
    }

    private fun setupGraphing() {
        graph.addSeries(mmHg)
        graph.viewport.isXAxisBoundsManual = true
        graph.viewport.setMinX(0.0)
        graph.viewport.setMaxX(3.0)
        graph.viewport.isScrollable = false
        graph.gridLabelRenderer.gridStyle = GridLabelRenderer.GridStyle.NONE
        graph.gridLabelRenderer.isVerticalLabelsVisible = false
        graph.gridLabelRenderer.isHorizontalLabelsVisible = false
        mmHg.color = Color.RED
        mmHg.isDrawBackground = true
        mmHg.backgroundColor = 0xFFCCCC
        mmHg.thickness = 10
        provider.addHeartbeatListener(this)
    }

    @SuppressLint("SetTextI18n")
    override fun onNewPulse(pulse: Float) {
        pulseView.text = "${pulse.toInt()} bpm"
        pulseError.text = "±${provider.pulseError.toInt()}"
        if (provider.pulseError < 2) {
            provider.pause()
            progressCircle.visibility = View.INVISIBLE
        }
    }

    override fun onHeartbeat(timestamp: Float, brightness: Float) {
        if (movingAverage == 0f) {
            movingAverage = brightness
        }
        movingAverage = weightedAverage(movingAverage, brightness, SMOOTHING_FACTOR)
        mmHg.appendData(DataPoint(timestamp.toDouble(), movingAverage.toDouble()), true, MAX_DATA_POINTS)
    }

    private fun setupPermissions() {
        val permissionCheck = checkSelfPermission(android.Manifest.permission.CAMERA)
        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            provider = PulseProvider(this, cameraView)
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Ensure the request code is one sent from the camera permission
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.contains(PackageManager.PERMISSION_GRANTED)) {
                provider = PulseProvider(this, cameraView)
            } else {
                Toast.makeText(this, "The app needs your camera to watch your pulse!", Toast.LENGTH_LONG).show()
                setupPermissions()
            }
        }
    }
}
