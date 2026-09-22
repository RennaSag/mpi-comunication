package br.mpi.fumaca.mpi

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Tipo de dado MPI (Seção 3.2.2 do padrão, Tabela 3.2).
 *
 * A mensagem é descrita por (buf, count, datatype): o tamanho é dado em número de
 * ELEMENTOS, não de bytes. Cada tipo sabe converter o buffer da linguagem para bytes
 * na rede (big-endian), o que resolve a conversão de representação entre aparelhos
 * heterogêneos (Seção 3.3.2).
 */
class Datatype internal constructor(
    internal val id: Int,
    val name: String,
    /** Tamanho em bytes de um elemento. */
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

/**
 * Objeto de status (Seção 3.2.5). Assim como em C, os campos públicos são
 * MPI_SOURCE, MPI_TAG e MPI_ERROR; o tamanho da mensagem é obtido com MPI.Get_count.
 */
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

/**
 * Objeto MPI_Info (Capítulo 10): pares (chave, valor) com dicas dependentes da
 * implementação. Esta implementação reconhece em MPI.Open_port as chaves "host" e
 * "port", e em MPI.Comm_connect a chave "timeout" (ms).
 */
class Info internal constructor(private val readOnly: Boolean = false) {
    private val entries = ConcurrentHashMap<String, String>()

    fun set(key: String, value: String) {
        if (readOnly) throw MpiException(MPI.ERR_INFO, "MPI_INFO_NULL não pode ser alterado")
        entries[key] = value
    }

    fun get(key: String): String? = entries[key]
}

/**
 * Erro MPI. Esta implementação usa o comportamento de MPI_ERRORS_RETURN (Seção 9.3),
 * mapeado para exceções — assim como as bindings Java do Open MPI.
 */
class MpiException(val errorClass: Int, message: String) :
    Exception("[${MPI.Error_string(errorClass)}] $message")

/** Uma mensagem = envelope (Seção 3.2.3) + dados (Seção 3.2.2). */
internal class Message(
    val contextId: Int,
    val source: Int,
    val dest: Int,
    val tag: Int,
    val datatypeId: Int,
    val data: ByteArray,
)
