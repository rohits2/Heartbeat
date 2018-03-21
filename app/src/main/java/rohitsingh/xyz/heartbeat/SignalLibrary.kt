package rohitsingh.xyz.heartbeat

import java.lang.Math.abs

/**
 * Created by rohit on 12/26/17.
 */
fun weightedAverage(prev: Float, new: Float, prevWeight: Float): Float {
    return prev * prevWeight + new - prevWeight * new;
}

fun maxPool(array: FloatArray, window: Int): FloatArray {
    var pooled = FloatArray(array.size)
    for (i in 0 until array.size) {
        var start = if (i - window < 0) 0 else i - window
        var max = Float.MIN_VALUE;
        (start until i + 1)
                .asSequence()
                .filter { array[it] > max }
                .forEach { max = array[it] }
        pooled[i] = max
    }
    return pooled;
}

fun movingAverage(data: FloatArray, inertia: Float): FloatArray {
    val averagedArray = data.clone()
    averagedArray.reduce({ prev: Float, new: Float -> weightedAverage(prev, new, inertia) })
    return averagedArray
}


fun doesIntersect(x1: Float, y1: Float, x2: Float, y2: Float, m: Float, b: Float): Boolean {
    val lineY1 = m * x1 + b
    val lineY2 = m * x2 + b
    return (lineY1 <= y1 && lineY2 >= y2) || (lineY1 >= y1 && lineY2 <= y2)
}