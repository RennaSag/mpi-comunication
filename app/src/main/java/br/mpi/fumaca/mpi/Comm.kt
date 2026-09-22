package br.mpi.fumaca.mpi

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Comunicador (Seção 3.2.3 e Capítulo 7): define um contexto de comunicação e um
 * grupo ordenado de processos. Mensagens enviadas em um contexto nunca são
 * recebidas em outro.
 *
 * - Intra-comunicador (MPI_COMM_WORLD / MPI_COMM_SELF): os processos do próprio grupo.
 * - Inter-comunicador (resultado de MPI_Comm_accept / MPI_Comm_connect, Seção 11.9):
 *   `dest` e `source` são ranks no grupo REMOTO.
 */
class Comm internal constructor(
    val name: String,
    internal val rank: Int,
    internal val size: Int,
    /** Tamanho do grupo remoto; 0 para intra-comunicadores. */
    internal val remoteSize: Int,
    internal val contextId: Int,
    private val channel: TcpChannel?,
) {
    internal val isInter get() = channel != null
    internal val engine = MatchingEngine()

    /** Garante a ordem das mensagens (não-ultrapassagem, Seção 3.5). */
    private val sender: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mpi-send-$name").apply { isDaemon = true }
    }

    @Volatile
    internal var freed = false
        private set

    init {
        channel?.attach(this)
    }

    /** Número de processos que podem ser destino de um send neste comunicador. */
    internal val targetSize get() = if (isInter) remoteSize else size

    internal fun isend(msg: Message): Request {
        val req = Request(Request.Kind.SEND, this)
        if (freed) throw MpiException(MPI.ERR_COMM, "comunicador $name já foi desconectado")
        sender.execute {
            try {
                if (channel != null) channel.write(msg) else engine.deliver(msg) // envio para si mesmo
                req.status.MPI_SOURCE = rank
                req.status.MPI_TAG = msg.tag
                req.status.nbytes = msg.data.size
            } catch (e: MpiException) {
                req.error = e
            } catch (e: IOException) {
                req.error = MpiException(MPI.ERR_OTHER, "falha ao enviar: ${e.message}")
            }
            req.complete()
        }
        return req
    }

    /** MPI_Comm_disconnect: espera as comunicações pendentes e libera o comunicador. */
    internal fun disconnect() {
        if (freed) return
        freed = true
        sender.shutdown()
        sender.awaitTermination(5, TimeUnit.SECONDS)
        channel?.close(sendGoodbye = true)
        engine.fail(MpiException(MPI.ERR_COMM, "comunicador $name foi desconectado"))
    }

    override fun toString() = name
}

/**
 * Requisição de uma operação não-bloqueante (Seção 3.7). Completada por
 * MPI.Wait / MPI.Test, ou cancelada por MPI.Cancel.
 */
class Request internal constructor(internal val kind: Kind, internal val comm: Comm) {
    internal enum class Kind { SEND, RECV }

    // Parâmetros de um receive: padrão de casamento do envelope (Seção 3.2.4).
    internal var buf: Any? = null
    internal var count = 0
    internal var datatype: Datatype? = null
    internal var source = MPI.ANY_SOURCE
    internal var tag = MPI.ANY_TAG

    internal val status = Status()
    @Volatile internal var error: MpiException? = null
    private val latch = CountDownLatch(1)

    internal val isComplete get() = latch.count == 0L

    internal fun complete() = latch.countDown()
    internal fun await() = latch.await()

    /** Um receive casa com a mensagem se source, tag e contexto casam (curingas permitidos). */
    internal fun matches(m: Message) =
        m.contextId == comm.contextId &&
            (source == MPI.ANY_SOURCE || source == m.source) &&
            (tag == MPI.ANY_TAG || tag == m.tag)

    internal fun deliver(m: Message) {
        status.MPI_SOURCE = m.source
        status.MPI_TAG = m.tag
        status.nbytes = m.data.size
        val dt = datatype!!
        try {
            if (m.datatypeId != dt.id) {
                throw MpiException(MPI.ERR_TYPE, "tipo recebido não casa com $dt (Seção 3.3.1)")
            }
            if (m.data.size > count * dt.extent) {
                throw MpiException(MPI.ERR_TRUNCATE, "mensagem de ${m.data.size} bytes não cabe no buffer")
            }
            dt.unpack(m.data, buf!!)
        } catch (e: MpiException) {
            status.MPI_ERROR = e.errorClass
            error = e
        }
        complete()
    }

    internal fun fail(e: MpiException) {
        status.MPI_ERROR = e.errorClass
        error = e
        complete()
    }
}

/**
 * Motor de casamento de mensagens. Mantém a fila de mensagens inesperadas (chegaram
 * antes de um receive) e a fila de receives postados, sempre em ordem de chegada — o
 * que garante a regra de não-ultrapassagem da Seção 3.5.
 */
internal class MatchingEngine {
    private val lock = Object()
    private val unexpected = ArrayDeque<Message>()
    private val posted = ArrayList<Request>()
    private var failure: MpiException? = null

    fun deliver(m: Message) {
        synchronized(lock) {
            val it = posted.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (r.matches(m)) {
                    it.remove()
                    r.deliver(m)
                    return
                }
            }
            unexpected.addLast(m)
            lock.notifyAll()
        }
    }

    fun post(r: Request) {
        synchronized(lock) {
            val it = unexpected.iterator()
            while (it.hasNext()) {
                val m = it.next()
                if (r.matches(m)) {
                    it.remove()
                    r.deliver(m)
                    return
                }
            }
            failure?.let { r.fail(it); return }
            posted.add(r)
        }
    }

    fun cancel(r: Request): Boolean = synchronized(lock) {
        val removed = posted.remove(r)
        if (removed) {
            r.status.cancelled = true
            r.complete()
        }
        removed
    }

    /** MPI_Probe / MPI_Iprobe (Seção 3.8.1): consulta sem receber. */
    fun probe(contextId: Int, source: Int, tag: Int, blocking: Boolean): Status? {
        synchronized(lock) {
            while (true) {
                val m = unexpected.firstOrNull {
                    it.contextId == contextId &&
                        (source == MPI.ANY_SOURCE || source == it.source) &&
                        (tag == MPI.ANY_TAG || tag == it.tag)
                }
                if (m != null) {
                    return Status().apply {
                        MPI_SOURCE = m.source
                        MPI_TAG = m.tag
                        nbytes = m.data.size
                    }
                }
                failure?.let { throw it }
                if (!blocking) return null
                lock.wait()
            }
        }
    }

    /** O processo remoto saiu: todos os receives pendentes terminam com erro. */
    fun fail(e: MpiException) {
        synchronized(lock) {
            if (failure == null) failure = e
            posted.forEach { it.fail(e) }
            posted.clear()
            lock.notifyAll()
        }
    }
}

/**
 * Canal TCP de um inter-comunicador. Cada mensagem vai com um cabeçalho de tamanho
 * fixo que codifica o envelope, como sugerido no "Advice to implementors" da
 * Seção 3.2.3:
 *
 *   MAGIC | tipo de quadro | contexto | source | dest | tag | datatype | nbytes | dados...
 *
 * Uma thread de progresso (Seção 2.9) lê os quadros e entrega ao motor de casamento.
 */
internal class TcpChannel(private val socket: Socket) {
    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private lateinit var comm: Comm

    @Volatile private var closed = false

    fun attach(c: Comm) {
        comm = c
        Thread({ progressLoop() }, "mpi-progress-${c.name}").apply { isDaemon = true }.start()
    }

    fun write(m: Message): Unit = synchronized(out) {
        if (closed) throw MpiException(MPI.ERR_OTHER, "conexão encerrada")
        out.writeInt(Wire.MAGIC)
        out.writeInt(Wire.FRAME_MSG)
        out.writeInt(m.contextId)
        out.writeInt(m.source)
        out.writeInt(m.dest)
        out.writeInt(m.tag)
        out.writeInt(m.datatypeId)
        out.writeInt(m.data.size)
        out.write(m.data)
        out.flush()
    }

    private fun progressLoop() {
        var reason = "o processo remoto encerrou a conexão"
        try {
            while (!closed) {
                if (inp.readInt() != Wire.MAGIC) throw IOException("quadro inválido")
                when (inp.readInt()) {
                    Wire.FRAME_MSG -> {
                        val ctx = inp.readInt()
                        val src = inp.readInt()
                        val dst = inp.readInt()
                        val tag = inp.readInt()
                        val dt = inp.readInt()
                        val n = inp.readInt()
                        val data = ByteArray(n).also { inp.readFully(it) }
                        // Mensagens de outro contexto não pertencem a este comunicador.
                        if (ctx == comm.contextId) comm.engine.deliver(Message(ctx, src, dst, tag, dt, data))
                    }
                    Wire.FRAME_DISCONNECT -> {
                        reason = "o processo remoto chamou MPI_Comm_disconnect"
                        break
                    }
                    else -> throw IOException("tipo de quadro desconhecido")
                }
            }
        } catch (_: EOFException) {
        } catch (e: IOException) {
            if (!closed) reason = "conexão perdida: ${e.message}"
        }
        close(sendGoodbye = false)
        comm.engine.fail(MpiException(MPI.ERR_OTHER, reason))
    }

    fun close(sendGoodbye: Boolean) {
        synchronized(out) {
            if (closed) return
            closed = true
            if (sendGoodbye) {
                try {
                    out.writeInt(Wire.MAGIC)
                    out.writeInt(Wire.FRAME_DISCONNECT)
                    out.flush()
                } catch (_: IOException) {
                }
            }
        }
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

/** Constantes do protocolo de rede desta implementação. */
internal object Wire {
    const val MAGIC = 0x4D504921 // "MPI!"
    const val VERSION = 1
    const val FRAME_MSG = 1
    const val FRAME_DISCONNECT = 2
    const val FRAME_HELLO = 3   // cliente -> servidor em MPI_Comm_connect
    const val FRAME_WELCOME = 4 // servidor -> cliente em MPI_Comm_accept
}
