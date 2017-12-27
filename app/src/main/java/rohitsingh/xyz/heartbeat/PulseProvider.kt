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
import java.lang.Math.abs
import java.lang.Math.sqrt
import java.nio.ByteBuffer

/**
 * Created by rohit on 9/4/17.
 */
const val MAX_IMAGES = 4 //The maximum length of the ImageReader queue.
const val HISTORY_BUFFER_SIZE = 128 //The size of the buffers storing brightness and timestamp. Must be a power of two for the FFT algorithm.
const val FRAME_WIDTH = 480
const val FRAME_HEIGHT = 240

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
    private val brightnessHistory: FloatArray = FloatArray(HISTORY_BUFFER_SIZE)
    private val timestampHistory: LongArray = LongArray(HISTORY_BUFFER_SIZE)
    private var bufferIndex = 0

    // Interface variables
    private var currentPulse: Float = 0f
    private var beatListeners = ArrayList<HeartbeatListener>()

    // Signal extraction state
    private val fourier = FFT(HISTORY_BUFFER_SIZE)
    val zeros = DoubleArray(HISTORY_BUFFER_SIZE)

    init {
        holdsOpenCamera = true
        openCamera(reader.surface)
        reader.setOnImageAvailableListener({ _ -> getCameraDelta() }, null)
    }

    val pulse get() = currentPulse

    fun addHeartbeatListener(listener: HeartbeatListener) {
        beatListeners.add(listener)
    }

    fun removeHeartbeatListener(listener: HeartbeatListener): Boolean {
        return beatListeners.remove(listener)
    }

    fun pause(){
        if(holdsOpenCamera) {
            holdsOpenCamera = false
            camera.close()
        }
    }

    fun restart(){
        if(!holdsOpenCamera) {
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

    private fun getCameraDelta() {
        val image = reader.acquireLatestImage() ?: return
        val (redBuf, _, _) = image.planes
        val currentRedBuffer = convertByteBufferToShortArray(redBuf.buffer)
        val diff = computeMeanValue(currentRedBuffer)
        if (bufferIndex == 0) {
            val sb = StringBuilder()
            for (i in brightnessHistory) {
                sb.append(i.toString())
                sb.append(", ")
            }
            Log.d(loggingTag, "RBuf: " + sb.toString())
            currentPulse = computeFrequency()
            for (listener in beatListeners) {
                listener.onNewPulse(currentPulse)
            }
        }
        append(diff, image.timestamp)
        for (listener in beatListeners) {
            listener.onHeartbeat(image.timestamp - startTime, diff) // Offset image timestamp by start time to get more reasonable numbers
        }
        image.close()
    }

    private fun append(brightness: Float, timestamp: Long) {
        brightnessHistory[bufferIndex] = brightness
        timestampHistory[bufferIndex] = timestamp
        bufferIndex += 1
        bufferIndex %= HISTORY_BUFFER_SIZE
    }

    private fun computeMeanValue(array: ShortArray): Float {
        return array.map { it.toLong() }.sum() / array.size.toFloat()
    }

    private fun computeMeanDifference(array1: ShortArray, array2: ShortArray): Float {
        if (array1.size != array2.size) {
            throw ArrayStoreException("Arrays have different sizes and cannot be diffed!")
        }
        val totalDiff: Long = (0 until array1.size)
                .map { abs(array1[it].toLong() - array2[it].toLong()) }
                .sum()
        return totalDiff / array1.size.toFloat()
    }

    private fun computeFrequency(): Float {
        val brightnessFFT = DoubleArray(HISTORY_BUFFER_SIZE)
        (0 until HISTORY_BUFFER_SIZE).map { brightnessFFT[it] = brightnessHistory[it].toDouble() }
        fourier.fft(brightnessFFT, zeros)
        (0 until HISTORY_BUFFER_SIZE).map { brightnessFFT[it] = 2.0/ HISTORY_BUFFER_SIZE * sqrt(brightnessFFT[it] * brightnessFFT[it] + zeros[it] * zeros[it]) }
        zeros.map { 0 }
        var max = 0.0
        var argmax = 0
        for (i in 1 until HISTORY_BUFFER_SIZE / 2) {
            if (brightnessFFT[i] > max) {
                max = brightnessFFT[i]
                argmax = i
            }
        }
        val sb = StringBuilder()
        for (i in brightnessFFT) {
            sb.append(i.toString())
            sb.append(", ")
        }
        Log.d(loggingTag, "FFTBuf: " + sb.toString())
        val meanInterval = averageDistance(timestampHistory) / 1e9;
        Log.d(loggingTag, "Mean interval computed as $meanInterval")
        val bpm = argmax * HISTORY_BUFFER_SIZE * meanInterval.toFloat()
        Log.d(loggingTag, "FFT-derived freq $bpm bpm")
        return bpm
    }

    private fun convertByteBufferToShortArray(buffer: ByteBuffer): ShortArray {
        val redByteBuffer = ByteArray(buffer.capacity(), { _ -> 0.toByte() })
        buffer.get(redByteBuffer)
        return ShortArray(redByteBuffer.size, { i -> redByteBuffer[i].toShort() })
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
        fun onHeartbeat(timestamp: Long, brightness: Float)
        fun onNewPulse(pulse: Float)
    }
}