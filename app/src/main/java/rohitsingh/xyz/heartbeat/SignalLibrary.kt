package rohitsingh.xyz.heartbeat

/**
 * Created by rohit on 12/26/17.
 */
fun weightedAverage(prev: Float, new: Float, prevWeight: Float): Float{
    return prev*prevWeight+new-prevWeight*new;
}
 fun maxPool(array: FloatArray, window: Int): FloatArray {
    var pooled = FloatArray(array.size)
    for (i in 0 until array.size) {
        var start = if (i - window < 0) 0 else i - window
        var max = Float.MIN_VALUE;
        for (j in start until i + 1) {
            if (array[j] > max) {
                max = array[j]
            }
        }
        pooled[i] = max
    }
    return pooled;
}
fun movingAverage(data: FloatArray, inertia: Float): FloatArray{
    var averagedArray = data.clone()
    averagedArray.reduce({prev: Float, new: Float->weightedAverage(prev, new, inertia)})
    return averagedArray
}