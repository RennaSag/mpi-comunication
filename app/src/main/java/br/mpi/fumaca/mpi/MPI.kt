@file:Suppress("FunctionName", "unused")

package br.mpi.fumaca.mpi

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Implementação mínima do padrão MPI (MPI: A Message-Passing Interface Standard,
 * versão 5.0) em Kotlin puro, sobre TCP, para rodar em celulares Android.
 *
 * Os nomes e a semântica seguem as bindings em C do padrão:
 *   MPI_Send(buf, count, datatype, dest, tag, comm)  ->  MPI.Send(buf, count, datatype, dest, tag, comm)
 *
 * Cada celular é um processo MPI "singleton" (Seção 11.10.2: inicialização singleton, sem mpiexec), com
 * MPI_COMM_WORLD de tamanho 1. Os dois processos foram iniciados de forma independente,
 * por isso a comunicação é estabelecida pelo modelo cliente/servidor da Seção 11.9:
 *
 *   servidor: MPI_Open_port -> MPI_Comm_accept  (celular que roteia o hotspot)
 *   cliente : MPI_Comm_connect(port_name)        (celular conectado ao hotspot)
 *
 * O resultado é um inter-comunicador em que cada lado tem rank 0 e o grupo remoto
 * tem tamanho 1; depois disso toda a troca usa comunicação ponto-a-ponto (Capítulo 3).
 */
object MPI {
    // ---- Constantes (Apêndice A) --------------------------------------------------
    const val SUCCESS = 0
    const val ANY_SOURCE = -1
    const val ANY_TAG = -1
    const val PROC_NULL = -2
    const val UNDEFINED = -32766
    /** Maior tag válida; o padrão exige pelo menos 32767 (Seção 3.2.3). */
    const val TAG_UB = 32767
    const val MAX_PORT_NAME = 256

    const val THREAD_SINGLE = 0
    const val THREAD_FUNNELED = 1
    const val THREAD_SERIALIZED = 2
    const val THREAD_MULTIPLE = 3

    // Classes de erro (Seção 9.4). Os valores são definidos pela implementação.
    const val ERR_BUFFER = 1
    const val ERR_COUNT = 2
    const val ERR_TYPE = 3
    const val ERR_TAG = 4
    const val ERR_COMM = 5
    const val ERR_RANK = 6
    const val ERR_REQUEST = 7
    const val ERR_ROOT = 8
    const val ERR_ARG = 12
    const val ERR_TRUNCATE = 15
    const val ERR_OTHER = 16
    const val ERR_INTERN = 17
    const val ERR_INFO = 28
    const val ERR_PORT = 38

    // Tipos de dados predefinidos (Tabela 3.2).
    val INT = Datatype(1, "MPI_INT", 4)
    val CHAR = Datatype(2, "MPI_CHAR", 1)
    val BYTE = Datatype(3, "MPI_BYTE", 1)

    val INFO_NULL = Info(readOnly = true)

    /** Porta padrão usada por Open_port quando o Info não indica outra. */
    const val DEFAULT_PORT = 50999

    // ---- Estado do processo --------------------------------------------------------
    @Volatile private var initialized = false
    @Volatile private var finalized = false
    private val openPorts = ConcurrentHashMap<String, ServerSocket>()
    private val liveComms = ConcurrentHashMap.newKeySet<Comm>()

    lateinit var COMM_WORLD: Comm
        private set
    lateinit var COMM_SELF: Comm
        private set

    // ---- Inicialização e finalização (Seção 11.2) ------------------------------------

    fun Init() {
        Init_thread(THREAD_SINGLE)
    }

    /** Retorna o nível de thread fornecido; esta implementação é thread-safe (MULTIPLE). */
    @Synchronized
    fun Init_thread(required: Int): Int {
        if (initialized) throw MpiException(ERR_OTHER, "MPI_Init chamado duas vezes")
        if (finalized) throw MpiException(ERR_OTHER, "MPI_Init depois de MPI_Finalize é erro")
        COMM_WORLD = Comm("MPI_COMM_WORLD", rank = 0, size = 1, remoteSize = 0, contextId = 0, channel = null)
        COMM_SELF = Comm("MPI_COMM_SELF", rank = 0, size = 1, remoteSize = 0, contextId = 1, channel = null)
        initialized = true
        return THREAD_MULTIPLE
    }

    fun Initialized() = initialized
    fun Finalized() = finalized

    /** Libera conexões e portas. Depois disso nenhuma outra chamada MPI é permitida. */
    @Synchronized
    fun Finalize() {
        checkInit()
        liveComms.toList().forEach { Comm_disconnect(it) }
        openPorts.keys.toList().forEach { Close_port(it) }
        finalized = true
        initialized = false
    }

    // ---- Comunicadores ---------------------------------------------------------------

    fun Comm_rank(comm: Comm): Int = comm.rank
    fun Comm_size(comm: Comm): Int = comm.size
    fun Comm_remote_size(comm: Comm): Int {
        if (!comm.isInter) throw MpiException(ERR_COMM, "${comm.name} não é inter-comunicador")
        return comm.remoteSize
    }
    fun Comm_test_inter(comm: Comm): Boolean = comm.isInter

    // ---- Estabelecendo comunicação (Seção 11.9) ------------------------------------

    fun Info_create() = Info()

    /**
     * MPI_Open_port: abre um endereço de rede e devolve o port_name, no formato legível
     * "ip:porta" (sem espaços, como recomenda o padrão, pois o usuário pode digitá-lo).
     */
    fun Open_port(info: Info = INFO_NULL): String {
        checkInit()
        val host = info.get("host") ?: "0.0.0.0"
        val port = info.get("port")?.toIntOrNull() ?: DEFAULT_PORT
        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            server.bind(InetSocketAddress(0)) // porta ocupada: o sistema escolhe outra
        }
        val portName = "$host:${server.localPort}"
        openPorts[portName] = server
        return portName
    }

    /** MPI_Close_port: libera o endereço. Um MPI_Comm_accept pendente falha com MPI_ERR_PORT. */
    fun Close_port(portName: String) {
        openPorts.remove(portName)?.close()
    }

    /**
     * MPI_Comm_accept: bloqueia até um cliente se conectar e devolve um
     * inter-comunicador cujo grupo remoto é o cliente.
     */
    fun Comm_accept(portName: String, info: Info = INFO_NULL, root: Int = 0, comm: Comm = COMM_SELF): Comm {
        checkInit()
        checkRoot(root, comm)
        val server = openPorts[portName]
            ?: throw MpiException(ERR_PORT, "porta $portName não foi aberta com MPI_Open_port")
        val socket = try {
            server.accept()
        } catch (e: IOException) {
            throw MpiException(ERR_PORT, "porta fechada durante MPI_Comm_accept")
        }
        try {
            socket.tcpNoDelay = true
            val inp = DataInputStream(socket.getInputStream())
            val out = DataOutputStream(socket.getOutputStream())
            if (inp.readInt() != Wire.MAGIC || inp.readInt() != Wire.VERSION || inp.readInt() != Wire.FRAME_HELLO) {
                throw IOException("o cliente não fala este protocolo MPI")
            }
            val remoteSize = inp.readInt()
            // O servidor escolhe o contexto do novo inter-comunicador (2+ ficam fora do WORLD/SELF).
            val contextId = Random.nextInt(2, Int.MAX_VALUE)
            out.writeInt(Wire.MAGIC)
            out.writeInt(Wire.VERSION)
            out.writeInt(Wire.FRAME_WELCOME)
            out.writeInt(contextId)
            out.writeInt(comm.size)
            out.flush()
            return newInterComm(socket, contextId, comm, remoteSize)
        } catch (e: IOException) {
            socket.close()
            throw MpiException(ERR_PORT, "falha no aperto de mão: ${e.message}")
        }
    }

    /**
     * MPI_Comm_connect: conecta ao servidor identificado por port_name. Se a porta não
     * existe, ou não há MPI_Comm_accept dentro do tempo limite, gera MPI_ERR_PORT.
     */
    fun Comm_connect(portName: String, info: Info = INFO_NULL, root: Int = 0, comm: Comm = COMM_SELF): Comm {
        checkInit()
        checkRoot(root, comm)
        val (host, port) = parsePortName(portName)
        val timeout = info.get("timeout")?.toIntOrNull() ?: 10_000
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeout)
            socket.tcpNoDelay = true
            socket.soTimeout = timeout
            val out = DataOutputStream(socket.getOutputStream())
            val inp = DataInputStream(socket.getInputStream())
            out.writeInt(Wire.MAGIC)
            out.writeInt(Wire.VERSION)
            out.writeInt(Wire.FRAME_HELLO)
            out.writeInt(comm.size)
            out.flush()
            if (inp.readInt() != Wire.MAGIC || inp.readInt() != Wire.VERSION || inp.readInt() != Wire.FRAME_WELCOME) {
                throw IOException("o servidor não fala este protocolo MPI")
            }
            val contextId = inp.readInt()
            val remoteSize = inp.readInt()
            socket.soTimeout = 0
            return newInterComm(socket, contextId, comm, remoteSize)
        } catch (e: SocketTimeoutException) {
            socket.close()
            throw MpiException(ERR_PORT, "tempo esgotado conectando a $portName")
        } catch (e: IOException) {
            socket.close()
            throw MpiException(ERR_PORT, "não foi possível conectar a $portName: ${e.message}")
        }
    }

    /** MPI_Comm_disconnect: espera as operações pendentes e desfaz a conexão. */
    fun Comm_disconnect(comm: Comm) {
        if (!comm.isInter) throw MpiException(ERR_COMM, "só inter-comunicadores podem ser desconectados aqui")
        comm.disconnect()
        liveComms.remove(comm)
    }

    // ---- Comunicação ponto-a-ponto (Capítulo 3) --------------------------------------

    /** MPI_Send bloqueante (Seção 3.2.1): retorna quando o buffer pode ser reutilizado. */
    fun Send(buf: Any, count: Int, datatype: Datatype, dest: Int, tag: Int, comm: Comm) {
        Wait(Isend(buf, count, datatype, dest, tag, comm))
    }

    /** MPI_Recv bloqueante (Seção 3.2.4). */
    fun Recv(buf: Any, count: Int, datatype: Datatype, source: Int, tag: Int, comm: Comm): Status =
        Wait(Irecv(buf, count, datatype, source, tag, comm))

    /** MPI_Isend (Seção 3.7.2): inicia o envio e devolve uma requisição. */
    fun Isend(buf: Any, count: Int, datatype: Datatype, dest: Int, tag: Int, comm: Comm): Request {
        checkInit()
        if (tag < 0 || tag > TAG_UB) throw MpiException(ERR_TAG, "tag inválida: $tag")
        if (dest == PROC_NULL) return Request(Request.Kind.SEND, comm).apply {
            status.MPI_SOURCE = PROC_NULL
            status.MPI_TAG = ANY_TAG
            complete()
        }
        if (dest < 0 || dest >= comm.targetSize) throw MpiException(ERR_RANK, "rank de destino inválido: $dest")
        // O source do envelope é implícito: o rank de quem envia (Seção 3.2.3).
        val msg = Message(comm.contextId, comm.rank, dest, tag, datatype.id, datatype.pack(buf, count))
        return comm.isend(msg)
    }

    /** MPI_Irecv (Seção 3.7.2): posta um receive que casa pelo envelope (source, tag, comm). */
    fun Irecv(buf: Any, count: Int, datatype: Datatype, source: Int, tag: Int, comm: Comm): Request {
        checkInit()
        if (tag != ANY_TAG && (tag < 0 || tag > TAG_UB)) throw MpiException(ERR_TAG, "tag inválida: $tag")
        val req = Request(Request.Kind.RECV, comm)
        if (source == PROC_NULL) {
            // Seção 3.10: receive de MPI_PROC_NULL completa imediatamente, sem dados.
            req.status.MPI_SOURCE = PROC_NULL
            req.status.MPI_TAG = ANY_TAG
            req.complete()
            return req
        }
        if (source != ANY_SOURCE && (source < 0 || source >= comm.targetSize)) {
            throw MpiException(ERR_RANK, "rank de origem inválido: $source")
        }
        req.buf = buf
        req.count = count
        req.datatype = datatype
        req.source = source
        req.tag = tag
        comm.engine.post(req)
        return req
    }

    /** MPI_Wait (Seção 3.7.3): bloqueia até a operação terminar. */
    fun Wait(request: Request): Status {
        request.await()
        request.error?.let { throw it }
        return request.status
    }

    /** MPI_Test (Seção 3.7.3): devolve o Status se terminou (flag = true) ou null. */
    fun Test(request: Request): Status? {
        if (!request.isComplete) return null
        request.error?.let { throw it }
        return request.status
    }

    /** MPI_Cancel (Seção 3.8.4): cancela um receive ainda não casado. */
    fun Cancel(request: Request) {
        if (request.kind == Request.Kind.RECV) request.comm.engine.cancel(request)
    }

    fun Test_cancelled(status: Status): Boolean = status.cancelled

    /** MPI_Probe (Seção 3.8.1): bloqueia até existir uma mensagem que case, sem recebê-la. */
    fun Probe(source: Int, tag: Int, comm: Comm): Status =
        comm.engine.probe(comm.contextId, source, tag, blocking = true)!!

    /** MPI_Iprobe: versão não-bloqueante; null se não há mensagem (flag = false). */
    fun Iprobe(source: Int, tag: Int, comm: Comm): Status? =
        comm.engine.probe(comm.contextId, source, tag, blocking = false)

    /** MPI_Get_count (Seção 3.2.5): número de elementos recebidos. */
    fun Get_count(status: Status, datatype: Datatype): Int =
        if (status.nbytes % datatype.extent != 0) UNDEFINED else status.nbytes / datatype.extent

    fun Error_string(errorClass: Int): String = when (errorClass) {
        SUCCESS -> "MPI_SUCCESS"
        ERR_BUFFER -> "MPI_ERR_BUFFER"
        ERR_COUNT -> "MPI_ERR_COUNT"
        ERR_TYPE -> "MPI_ERR_TYPE"
        ERR_TAG -> "MPI_ERR_TAG"
        ERR_COMM -> "MPI_ERR_COMM"
        ERR_RANK -> "MPI_ERR_RANK"
        ERR_REQUEST -> "MPI_ERR_REQUEST"
        ERR_ROOT -> "MPI_ERR_ROOT"
        ERR_ARG -> "MPI_ERR_ARG"
        ERR_TRUNCATE -> "MPI_ERR_TRUNCATE"
        ERR_INTERN -> "MPI_ERR_INTERN"
        ERR_INFO -> "MPI_ERR_INFO"
        ERR_PORT -> "MPI_ERR_PORT"
        else -> "MPI_ERR_OTHER"
    }

    // ---- Auxiliares ----------------------------------------------------------------

    private fun newInterComm(socket: Socket, contextId: Int, local: Comm, remoteSize: Int): Comm {
        val c = Comm(
            name = "intercomm#$contextId",
            rank = local.rank,
            size = local.size,
            remoteSize = remoteSize,
            contextId = contextId,
            channel = TcpChannel(socket),
        )
        liveComms.add(c)
        return c
    }

    /** Aceita "ip:porta", "host:porta" ou "tcp://ip:porta" (formas equivalentes, Seção 11.9.3). */
    private fun parsePortName(portName: String): Pair<String, Int> {
        val s = portName.trim().removePrefix("tcp://")
        val i = s.lastIndexOf(':')
        if (i <= 0) throw MpiException(ERR_PORT, "port_name inválido: \"$portName\" (use ip:porta)")
        val port = s.substring(i + 1).toIntOrNull()
            ?: throw MpiException(ERR_PORT, "porta inválida em \"$portName\"")
        return s.substring(0, i) to port
    }

    private fun checkRoot(root: Int, comm: Comm) {
        if (root < 0 || root >= comm.size) throw MpiException(ERR_ROOT, "root inválido: $root")
    }

    private fun checkInit() {
        if (!initialized) throw MpiException(ERR_OTHER, "MPI não foi inicializado (chame MPI.Init)")
    }
}
