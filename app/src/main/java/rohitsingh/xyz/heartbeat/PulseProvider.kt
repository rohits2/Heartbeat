package rohitsingh.xyz.heartbeat

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.widget.Toast
import org.nield.kotlinstatistics.simpleRegression
import org.nield.kotlinstatistics.standardDeviation
import java.nio.ByteBuffer

/**
 * PulseProvider connects to the camera and tries to measure the pulse of skin against the camera.
 * It allows clients to register to receive a raw feed of the blood pressure proxy, as well as
 * updates to the completed pulse.
 * This uses the camera flash, which can cause LED burnout if it is left on for too long.  To fix this,
 * the methods pause() and resume() are provided, which disconnect and reconnect the camera.
 * Created by rohit on 9/4/17.
 */
const val MAX_IMAGES = 4 //The maximum length of the ImageReader queue.
const val HISTORY_BUFFER_SIZE = 256 //The size of the buffers storing brightness and timestamp.
const val EVENT_TRIGGER = 32 // The number of samples before a pulse update is triggered
const val FRAME_WIDTH = 480
const val FRAME_HEIGHT = 240

const val TRENDLINE_INERTIA = 0.99f
const val LOW_PASS_INERTIA = 0.7f
const val ESTIMATED_MAGNITUDE = 70f //This is used to make the moving-average adjuster converge quickly.

class PulseProvider(private val context: Context) {
    // Globals for camera capture
    private lateinit var camera: CameraDevice
    private val reader = ImageReader.newInstance(FRAME_WIDTH, FRAME_HEIGHT, ImageFormat.YUV_420_888, MAX_IMAGES)
    private val handler = Handler()
    private var holdsOpenCamera = false

    // Constant for logging
    private val loggingTag: String = "PulseProvider"

    // Buffers and variables for raw data capture
    private val startTime by lazy { timestampHistory[0] }
    private val brightnessHistory = RingFloatArray(HISTORY_BUFFER_SIZE)
    private val timestampHistory = RingFloatArray(HISTORY_BUFFER_SIZE)

    // Interface variables
    private var eventCounter = 0
    private var currentPulse: Float = 0f
    private var beatListeners = ArrayList<HeartbeatListener>()

    // Variables for signal extraction
    private var trendlineAvg = ESTIMATED_MAGNITUDE
    private var lowPassAvg = 0f
    private var pulses = ArrayList<Float>()

    init {
        holdsOpenCamera = true
        openCamera(reader.surface)
        reader.setOnImageAvailableListener({ getFrame() }, null)
    }

    val pulse get() = currentPulse
    val pulseError get() = pulses.standardDeviation()

    fun addHeartbeatListener(listener: HeartbeatListener) {
        beatListeners.add(listener)
    }

    fun removeHeartbeatListener(listener: HeartbeatListener): Boolean {
        return beatListeners.remove(listener)
    }

    fun pause() {
        if (holdsOpenCamera) {
            holdsOpenCamera = false
            camera.close()
        }
    }

    fun resume() {
        if (!holdsOpenCamera) {
            holdsOpenCamera = true
            openCamera(reader.surface)
        }
    }

    private fun openCamera(readerSurface: Surface) {
        val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val rearCameraID = findRearCameraID(cameraManager)
        Log.d(loggingTag, "Discovered camera with ID $rearCameraID")
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "The camera is required for the pulse scan!", Toast.LENGTH_LONG).show()
        }
        cameraManager.openCamera(rearCameraID, object : CameraDevice.StateCallback() {
            override fun onError(device: CameraDevice?, errorCode: Int) {
                throw CameraAccessException(errorCode)
            }

            override fun onOpened(device: CameraDevice?) {
                camera = device ?: throw CameraAccessException(CameraAccessException.CAMERA_ERROR)
                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureBuilder.addTarget(readerSurface)
                captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)

                camera.createCaptureSession(listOf(readerSurface), CameraSessionCallback(captureBuilder.build()), handler)
                Log.d(loggingTag, "Acquired camera!")
            }

            override fun onDisconnected(device: CameraDevice?) {
                throw CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED)
            }
        }, null)
    }

    private fun findRearCameraID(cameraManager: CameraManager): String {
        for (camera in cameraManager.cameraIdList) {
            val characteristic = cameraManager.getCameraCharacteristics(camera)
            if (characteristic.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return camera
            }
        }
        throw CameraNotFoundException("No camera could be found!")
    }

    private fun getFrame() {
        eventCounter++;
        val image = reader.acquireLatestImage() ?: return
        val (redBuf, _, _) = image.planes
        val currentRedBuffer = convertByteBufferToShortArray(redBuf.buffer)
        var brightness = computeMeanValue(currentRedBuffer)
        if (eventCounter % EVENT_TRIGGER == 0) {
            handlePulse()
        }
        trendlineAvg = weightedAverage(trendlineAvg, brightness, TRENDLINE_INERTIA)
        brightness -= trendlineAvg
        lowPassAvg = weightedAverage(lowPassAvg, brightness, LOW_PASS_INERTIA)
        if (timestampHistory[0] == 0f) {
            timestampHistory[0] = nsToS(image.timestamp)
        }
        append(lowPassAvg, nsToS(image.timestamp) - startTime)
        for (listener in beatListeners) {
            listener.onHeartbeat(nsToS(image.timestamp) - startTime, brightness) // Offset image timestamp by start time to get more reasonable numbers
        }
        image.close()
    }

    private fun handlePulse() {
        printArray("BrightnessHistory", brightnessHistory)
        printArray("TimestampHistory", timestampHistory)
        pulses.add(computeFrequency())
        currentPulse = pulses.average().toFloat()
        for (listener in beatListeners) {
            listener.onNewPulse(currentPulse)
        }
    }

    private fun append(brightness: Float, timestamp: Float) {
        brightnessHistory.push(brightness)
        timestampHistory.push(timestamp)
    }

    private fun computeMeanValue(array: ShortArray): Float {
        return array.map { it.toLong() }.sum() / array.size.toFloat()
    }

    private fun computeFrequency(): Float {
        val r = (0 until HISTORY_BUFFER_SIZE).map { timestampHistory[it] to brightnessHistory[it] }.simpleRegression()
        Log.d(loggingTag, "Regression complete.  Parameters b=${r.intercept}, m=${r.slope}")
        val meanInterval = timestampHistory.averageGap()
        val totalMinInSegment = meanInterval * HISTORY_BUFFER_SIZE / 60
        Log.d(loggingTag, "Mean interval computed as $meanInterval s for total history length of $totalMinInSegment min")

        var bpPeaks = (0 until brightnessHistory.size).map{
              if(timestampHistory[it]*r.slope+r.intercept > brightnessHistory[it]){
                  0
              } else brightnessHistory[it]
        }
        brightnessHistory.localMaximaIndices().map { timestampHistory[it] }

        val numIntersects = (0 until HISTORY_BUFFER_SIZE - 1).map {
            if (doesIntersect(timestampHistory[it], brightnessHistory[it], timestampHistory[it + 1], brightnessHistory[it + 1], r.slope.toFloat(), r.intercept.toFloat())) 1 else 0
        }.sum()
        val bpm = numIntersects / 2 / totalMinInSegment
        Log.d(loggingTag, "Trendline intersect freq $bpm bpm")
        return bpm.toFloat()
    }

    private fun convertByteBufferToShortArray(buffer: ByteBuffer): ShortArray {
        val redByteBuffer = ByteArray(buffer.capacity(), { _ -> 0.toByte() })
        buffer.get(redByteBuffer)
        return ShortArray(redByteBuffer.size, { i -> redByteBuffer[i].toShort() })
    }

    private fun printArray(arrayName: String, array: RingFloatArray) {
        val sb = StringBuilder()
        for (i in array) {
            sb.append(i.toString())
            sb.append(", ")
        }
        Log.d(loggingTag, "$arrayName: " + sb.toString())
    }

    private fun nsToS(ns: Long): Float {
        return (ns / 1e9).toFloat()
    }

    class CameraNotFoundException(message: String?) : Exception(message)
    class CameraNotOpenableException(message: String?) : Exception(message)
    inner class CameraSessionCallback(private val captureRequest: CaptureRequest) : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(p0: CameraCaptureSession?) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onConfigured(nullableCaptureSession: CameraCaptureSession?) {
            val captureSession: CameraCaptureSession = nullableCaptureSession ?: throw CameraNotOpenableException("Camera could not be configured (was null).")
            captureSession.setRepeatingRequest(captureRequest, null, handler)
        }

    }

    interface HeartbeatListener {
        fun onHeartbeat(timestamp: Float, brightness: Float)
        fun onNewPulse(pulse: Float)
    }
}