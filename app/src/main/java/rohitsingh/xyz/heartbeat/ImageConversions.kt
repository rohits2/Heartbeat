package rohitsingh.xyz.heartbeat

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.renderscript.*


class ImageConverter(ctx: Context) {
    private val rs = RenderScript.create(ctx)
    private val yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    fun convert(img: Image, FRAME_WIDTH: Int, FRAME_HEIGHT: Int): Bitmap {
        val nv21 = convertYUV420888ToNV21(img)
        return convertNV21ToBitmap(nv21, FRAME_WIDTH, FRAME_HEIGHT)
    }

    fun convertNV21ToBitmap(data: ByteArray, FRAME_WIDTH: Int, FRAME_HEIGHT: Int): Bitmap {
        var yuvType = Type.Builder(rs, Element.U8(rs)).setX(data.size)
        var input = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)

        var rgbaType = Type.Builder(rs, Element.RGBA_8888(rs)).setX(FRAME_WIDTH).setY(FRAME_HEIGHT)
        var output = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT)

        input.copyFrom(data)

        yuvToRgbIntrinsic.setInput(input)
        yuvToRgbIntrinsic.forEach(output)

        val bmpout = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
        output.copyTo(bmpout)
        return bmpout
    }

    private fun convertYUV420888ToNV21(imgYUV420: Image): ByteArray {
        // Converting YUV_420_888 data to YUV_420_SP (NV21).
        val data: ByteArray
        val buffer0 = imgYUV420.planes[0].buffer
        val buffer2 = imgYUV420.planes[2].buffer
        val buffer0Size = buffer0.remaining()
        val buffer2Size = buffer2.remaining()
        data = ByteArray(buffer0Size + buffer2Size)
        buffer0.get(data, 0, buffer0Size)
        buffer2.get(data, buffer0Size, buffer2Size)
        return data
    }
}