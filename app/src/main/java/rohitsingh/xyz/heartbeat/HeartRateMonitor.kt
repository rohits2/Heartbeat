package rohitsingh.xyz.heartbeat

import android.content.pm.PackageManager
import android.graphics.Color
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.GridLabelRenderer
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import java.io.DataOutput

const val MAX_DATA_POINTS = 1024
const val CAMERA_REQUEST_CODE = 1021 // literally no reason for this choice

class HeartRateMonitor : AppCompatActivity(), PulseProvider.HeartbeatListener {


    private val loggingTag: String = "HeartRateMonitor"

    private lateinit var provider: PulseProvider

    private val graph: GraphView by lazy { findViewById<GraphView>(R.id.graph) }
    private val mmHg = LineGraphSeries<DataPoint>()
    private var movingAverage = 0f
    private val SMOOTHING_FACTOR = 0.7f;
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_heart_rate_monitor)
        setupPermissions()
        setupGraphing()
    }

    private fun setupGraphing() {
        graph.addSeries(mmHg)
        graph.viewport.isXAxisBoundsManual = true
        graph.viewport.setMinX(0.0)
        graph.viewport.setMaxX(5.0)
        graph.viewport.isScrollable = false
        graph.gridLabelRenderer.gridStyle = GridLabelRenderer.GridStyle.NONE
        graph.gridLabelRenderer.isVerticalLabelsVisible = false
        graph.gridLabelRenderer.isHorizontalLabelsVisible = false
        mmHg.color = Color.RED
        mmHg.thickness = 30
        provider.addHeartbeatListener(this)
    }

    override fun onHeartbeat(timestamp: Long, brightness: Float) {
        val timestampSecs = timestamp.toDouble() / 1e9
        movingAverage = weightedAverage(movingAverage, brightness, SMOOTHING_FACTOR)
        mmHg.appendData(DataPoint(timestampSecs, movingAverage.toDouble()), true, MAX_DATA_POINTS)
    }

    private fun setupPermissions() {
        val permissionCheck = checkSelfPermission(android.Manifest.permission.CAMERA);
        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            provider = PulseProvider(this)
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Ensure the request code is one sent from the camera permission
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.contains(PackageManager.PERMISSION_GRANTED)) {
                provider = PulseProvider(this)
            } else {
                Toast.makeText(this, "The app needs your camera to watch your pulse!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
