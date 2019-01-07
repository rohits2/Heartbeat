package rohitsingh.xyz.heartbeat

/**
 * Created by rohit on 12/27/17.
 */
class RingFloatArray(val size: Int) : Iterable<Float> {
    override fun iterator(): Iterator<Float> {
        return (0 until size).map {
            this[it]
        }.iterator()
    }

    private var currentOffset = 0
    private var wrappedAround = false
    var hasBeenFilled: Boolean = false
        get() = wrappedAround

    private val backingBuffer = FloatArray(size)
    operator fun get(i: Int): Float {
        return backingBuffer[(i + currentOffset) % size]
    }

    operator fun set(i: Int, b: Float) {
        backingBuffer[(i + currentOffset) % size] = b
    }

    fun push(b: Float) {
        backingBuffer[currentOffset] = b
        currentOffset++
        if (currentOffset >= size) {
            wrappedAround = true
            currentOffset = 0
        }
    }

    fun getBackingArray(): FloatArray {
        return backingBuffer
    }

    fun getZeroedArray(): FloatArray {
        val arr = FloatArray(size)
        var sum = 0f
        for (i in 0 until size) {
            arr[i] = this[i]
            sum += this[i]
        }
        val avg = sum / size
        for (i in 0 until size) {
            arr[i] -= avg
        }
        return arr
    }


    fun localMaximaIndices(): MutableList<Int> {
        val indices = MutableList(1, { 0 })
        (1 until this.size - 1).map {
            if (this[it] > this[it - 1] && this[it] > this[it + 1]) {
                indices.add(it)
            }
        }
        return indices
    }

    fun averageGap(): Double {
        return (0 until this.size - 1).map { Math.abs(this[it + 1] - this[it]) }.average()
    }


}