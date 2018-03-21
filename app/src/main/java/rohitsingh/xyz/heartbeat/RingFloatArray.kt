package rohitsingh.xyz.heartbeat

/**
 * Created by rohit on 12/27/17.
 */
class RingFloatArray(val size: Int):Iterable<Float>{
    override fun iterator(): Iterator<Float> {
        return (0 until size).map{
            this[it]
        }.iterator()
    }

    var currentOffset: Int = 0
    private val backingBuffer = FloatArray(size)
    operator fun get(i: Int): Float{
        return backingBuffer[(i+currentOffset)%size]
    }
    operator fun set(i: Int, b:Float){
        backingBuffer[(i+currentOffset)%size] = b
    }
    fun push(b: Float){
        backingBuffer[currentOffset] = b
        currentOffset++
        currentOffset %= size
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