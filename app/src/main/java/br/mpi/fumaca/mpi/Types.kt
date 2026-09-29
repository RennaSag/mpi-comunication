package br.mpi.fumaca.mpi

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class Datatype internal constructor(
    internal val id: Int,
    val name: String,
    val extent: Int,
) {
    internal fun pack(buf: Any, count: Int): ByteArray {
        checkBuffer(buf, count)
        return when (buf) {
            is IntArray -> ByteBuffer.allocate(count * 4).apply {
                for (i in 0 until count) putInt(buf[i])
            }.array()
            is ByteArray -> buf.copyOf(count)
            else -> throw MpiException(MPI.ERR_BUFFER, "buffer de tipo não suportado")
        }
    }

    internal fun unpack(data: ByteArray, buf: Any) {
        val count = data.size / extent
        checkBuffer(buf, count)
        when (buf) {
            is IntArray -> {
                val bb = ByteBuffer.wrap(data)
                for (i in 0 until count) buf[i] = bb.getInt()
            }
            is ByteArray -> data.copyInto(buf)
        }
    }

    private fun checkBuffer(buf: Any, count: Int) {
        val size = when {
            this === MPI.INT && buf is IntArray -> buf.size
            (this === MPI.CHAR || this === MPI.BYTE) && buf is ByteArray -> buf.size
            else -> throw MpiException(MPI.ERR_TYPE, "buffer incompatível com $name")
        }
        if (count < 0) throw MpiException(MPI.ERR_COUNT, "count negativo: $count")
        if (count > size) throw MpiException(MPI.ERR_BUFFER, "count=$count maior que o buffer ($size)")
    }

    override fun toString() = name
}

@Suppress("PropertyName")
class Status {
    var MPI_SOURCE: Int = MPI.ANY_SOURCE
        internal set
    var MPI_TAG: Int = MPI.ANY_TAG
        internal set
    var MPI_ERROR: Int = MPI.SUCCESS
        internal set
    internal var nbytes: Int = 0
    internal var cancelled: Boolean = false

    override fun toString() = "Status(source=$MPI_SOURCE, tag=$MPI_TAG, error=$MPI_ERROR, bytes=$nbytes)"
}

class Info internal constructor(private val readOnly: Boolean = false) {
    private val entries = ConcurrentHashMap<String, String>()

    fun set(key: String, value: String) {
        if (readOnly) throw MpiException(MPI.ERR_INFO, "MPI_INFO_NULL não pode ser alterado")
        entries[key] = value
    }

    fun get(key: String): String? = entries[key]
}

class MpiException(val errorClass: Int, message: String) :
    Exception("[${MPI.Error_string(errorClass)}] $message")

internal class Message(
    val contextId: Int,
    val source: Int,
    val dest: Int,
    val tag: Int,
    val datatypeId: Int,
    val data: ByteArray,
)
