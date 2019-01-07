package rohitsingh.xyz.heartbeat

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.support.v8.renderscript.RenderScript
import android.util.Log
import android.view.Surface
import android.widget.ImageView
import android.widget.Toast
import com.paramsen.noise.Noise
import org.nield.kotlinstatistics.median
import org.nield.kotlinstatistics.standardDeviation
import java.lang.Math.*
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
const val HISTORY_BUFFER_SIZE = 200 //The size of the buffers storing brightness and timestamp.
const val EVENT_TRIGGER = 32 // The number of samples before a pulse update is triggered
const val PRELOAD_THRESHOLD = 30 // The number of samples to discard while the camera powers on
const val FRAME_WIDTH = 240
const val FRAME_HEIGHT = 120

const val HZ = 1 / 60f   //This converts from hertz to the timestamp units (seconds, in this case)
const val LOW_CUTOFF_FREQ = 30 * HZ
const val HIGH_CUTOFF_FREQ = 240 * HZ
const val PULSE_SAMPLES_REQUIRED = 3
const val DEVIANCE_BIAS = 10 // This is added to the standard deviation to artificially increase it

class PulseProvider(private val context: Context, private var cameraView: ImageView) {
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
    private val bitmapper by lazy { ImageConverter(context) }
    private var eventCounter = -PRELOAD_THRESHOLD
    private var currentPulse: Float = 0f
    private var beatListeners = ArrayList<HeartbeatListener>()

    // Variables for signal extraction
    private var pulses = ArrayList<Float>()

    init {
        holdsOpenCamera = true
        val rs = RenderScript.create(context)
        openCamera(reader.surface)
        reader.setOnImageAvailableListener({ getFrame() }, null)
    }

    val pulse get() = currentPulse
    val pulseError get() = (DEVIANCE_BIAS + pulses.standardDeviation()) / sqrt(pulses.size.toDouble())

    fun addHeartbeatListener(listener: HeartbeatListener) {
        beatListeners.add(listener)
    }

    fun removeHeartbeatListener(listener: HeartbeatListener): Boolean {
        return beatListeners.remove(listener)
    }

    fun end(){
        if (holdsOpenCamera) {
            holdsOpenCamera = false
            camera.close()
        }
        pulses.clear()
    }

    fun pause() {
        if (holdsOpenCamera) {
            holdsOpenCamera = false
            camera.close()
        }
        pulses.clear()
    }

    fun resume() {
        if (!holdsOpenCamera) {
            holdsOpenCamera = true
            openCamera(reader.surface)
            eventCounter = -PRELOAD_THRESHOLD
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
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 8333333L)
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 50)
                captureBuilder.set(CaptureRequest.BLACK_LEVEL_LOCK, true)
                captureBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                captureBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true)

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
        eventCounter++
        val image = reader.acquireLatestImage() ?: return
        val (redBuf, _, _) = image.planes
        val currentRedBuffer = convertByteBufferToShortArray(redBuf.buffer)
        var brightness = computeMeanValue(currentRedBuffer)
        Log.d(loggingTag, "${brightness}")
        if (eventCounter % EVENT_TRIGGER == 0) {
            handlePulse()
        }
        if (eventCounter > 0) {
            if (timestampHistory[0] == 0f) {
                timestampHistory[0] = nsToS(image.timestamp)
            }
            append(brightness, nsToS(image.timestamp) - startTime)
            for (listener in beatListeners) {
                listener.onHeartbeat(nsToS(image.timestamp) - startTime, brightness) // Offset image timestamp by start time to get more reasonable numbers
            }
        }
        image.close()
    }

    private fun handlePulse() {
        if (!timestampHistory.hasBeenFilled) {
            Log.d(loggingTag, "Can't compute pulse because the timestamp buffer has not filled!")
            return
        }
        Log.d(loggingTag, "Computing pulse...")
        pulses.add(computeFrequency())
        if (pulses.size > PULSE_SAMPLES_REQUIRED) {
            currentPulse = pulses.median().toFloat()
            for (listener in beatListeners) {
                listener.onNewPulse(currentPulse)
                Log.d(loggingTag, "Pushing to listener!")
            }
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
        val noise = Noise.real()
                .optimized()
                .init(HISTORY_BUFFER_SIZE, true) //input size == 4096, internal output array
        val complexFft = noise.fft(brightnessHistory.getZeroedArray())
        val realFft = FloatArray(HISTORY_BUFFER_SIZE / 2)
        for (i in 0 until HISTORY_BUFFER_SIZE / 2 - 1) {
            realFft[i] = complexFft[2 * i] * complexFft[2 * i] + complexFft[2 * i + 1] * complexFft[2 * i + 1]
        }
        val interval = timestampHistory.averageGap()
        val freqRange = 1f / (2f * interval / 60f)
        val lowCut: Int = floor(LOW_CUTOFF_FREQ * HISTORY_BUFFER_SIZE * interval).toInt()
        val highCut: Int = ceil(HIGH_CUTOFF_FREQ * HISTORY_BUFFER_SIZE * interval).toInt()
        var maxA = 0f
        var maxF = LOW_CUTOFF_FREQ
        for (i in lowCut until highCut) {
            if (realFft[i] > maxA) {
                maxA = realFft[i]
                maxF = (freqRange * (i / (HISTORY_BUFFER_SIZE / 2f))).toFloat()
            }
        }
        Log.d(loggingTag, "Freq peak at ${maxF} (${maxA})")
        return maxF
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

    private fun printArray(arrayName: String, array: FloatArray) {
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