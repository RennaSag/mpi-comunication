@file:Suppress("FunctionName", "unused")

package br.mpi.fumaca.mpi

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object MPI {
    const val SUCCESS = 0
    const val ANY_SOURCE = -1
    const val ANY_TAG = -1
    const val PROC_NULL = -2
    const val UNDEFINED = -32766

    const val TAG_UB = 32767
    const val MAX_PORT_NAME = 256

    const val THREAD_SINGLE = 0
    const val THREAD_FUNNELED = 1
    const val THREAD_SERIALIZED = 2
    const val THREAD_MULTIPLE = 3

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
    const val ERR_NAME = 33
    const val ERR_PORT = 38
    const val ERR_SERVICE = 41

    val INT = Datatype(1, "MPI_INT", 4)
    val CHAR = Datatype(2, "MPI_CHAR", 1)
    val BYTE = Datatype(3, "MPI_BYTE", 1)

    val INFO_NULL = Info(readOnly = true)

    const val DEFAULT_PORT = 50999

    @Volatile private var initialized = false
    @Volatile private var finalized = false
    private val openPorts = ConcurrentHashMap<String, ServerSocket>()
    private val relayPorts = ConcurrentHashMap<String, RelayPort>()
    private val publishedNames = ConcurrentHashMap<String, String>()
    private val liveComms = ConcurrentHashMap.newKeySet<Comm>()

    lateinit var COMM_WORLD: Comm
        private set
    lateinit var COMM_SELF: Comm
        private set

    fun Init() {
        Init_thread(THREAD_SINGLE)
    }

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

    @Synchronized
    fun Finalize() {
        checkInit()
        liveComms.toList().forEach { Comm_disconnect(it) }
        publishedNames.keys.toList().forEach { runCatching { Unpublish_name(it) } }
        openPorts.keys.toList().forEach { Close_port(it) }
        relayPorts.keys.toList().forEach { Close_port(it) }
        finalized = true
        initialized = false
    }

    fun Comm_rank(comm: Comm): Int = comm.rank
    fun Comm_size(comm: Comm): Int = comm.size
    fun Comm_remote_size(comm: Comm): Int {
        if (!comm.isInter) throw MpiException(ERR_COMM, "${comm.name} não é inter-comunicador")
        return comm.remoteSize
    }
    fun Comm_test_inter(comm: Comm): Boolean = comm.isInter

    fun Info_create() = Info()

    fun Open_port(info: Info = INFO_NULL): String {
        checkInit()
        if (info.get("transport") == "relay") {
            val p = Relay.openPort()
            relayPorts[p.portName] = p
            return p.portName
        }
        val host = info.get("host") ?: "0.0.0.0"
        val port = info.get("port")?.toIntOrNull() ?: DEFAULT_PORT

        fun bind(p: Int) = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(p))
        }
        val server = try {
            bind(port)
        } catch (e: IOException) {
            try {
                bind(0)
            } catch (e2: IOException) {
                throw MpiException(ERR_PORT, "não foi possível abrir uma porta: ${e2.message}")
            }
        }
        val portName = "$host:${server.localPort}"
        openPorts[portName] = server
        return portName
    }

    fun Close_port(portName: String) {
        openPorts.remove(portName)?.close()
        relayPorts.remove(portName)?.close()
    }

    fun Comm_accept(portName: String, info: Info = INFO_NULL, root: Int = 0, comm: Comm = COMM_SELF): Comm {
        checkInit()
        checkRoot(root, comm)
        relayPorts[portName]?.let { relay ->
            val stream = relay.accept()
            return try {
                stream.readTimeoutMs = 10_000
                acceptHandshake(stream.input, stream.output, comm) { ctx, remote ->
                    stream.readTimeoutMs = 0
                    newInterComm(StreamChannel(stream.input, stream.output, stream::close), ctx, comm, remote)
                }
            } catch (e: IOException) {
                stream.close()
                throw MpiException(ERR_PORT, "falha no aperto de mão: ${e.message}")
            }
        }
        val server = openPorts[portName]
            ?: throw MpiException(ERR_PORT, "porta $portName não foi aberta com MPI_Open_port")
        val socket = try {
            server.accept()
        } catch (e: IOException) {
            throw MpiException(ERR_PORT, "porta fechada durante MPI_Comm_accept")
        }
        try {
            socket.tcpNoDelay = true
            val inp = socket.getInputStream()
            val out = socket.getOutputStream()
            return acceptHandshake(inp, out, comm) { ctx, remote ->
                newInterComm(StreamChannel(inp, out, socket::close), ctx, comm, remote)
            }
        } catch (e: IOException) {
            socket.close()
            throw MpiException(ERR_PORT, "falha no aperto de mão: ${e.message}")
        }
    }

    private fun acceptHandshake(input: InputStream, output: OutputStream, comm: Comm, make: (Int, Int) -> Comm): Comm {
        val inp = DataInputStream(input)
        val out = DataOutputStream(output)
        if (inp.readInt() != Wire.MAGIC || inp.readInt() != Wire.VERSION || inp.readInt() != Wire.FRAME_HELLO) {
            throw IOException("o cliente não fala este protocolo MPI")
        }
        val remoteSize = inp.readInt()

        val contextId = Random.nextInt(2, Int.MAX_VALUE)
        out.writeInt(Wire.MAGIC)
        out.writeInt(Wire.VERSION)
        out.writeInt(Wire.FRAME_WELCOME)
        out.writeInt(contextId)
        out.writeInt(comm.size)
        out.flush()
        return make(contextId, remoteSize)
    }

    private fun connectHandshake(input: InputStream, output: OutputStream, comm: Comm): Pair<Int, Int> {
        val out = DataOutputStream(output)
        val inp = DataInputStream(input)
        out.writeInt(Wire.MAGIC)
        out.writeInt(Wire.VERSION)
        out.writeInt(Wire.FRAME_HELLO)
        out.writeInt(comm.size)
        out.flush()
        if (inp.readInt() != Wire.MAGIC || inp.readInt() != Wire.VERSION || inp.readInt() != Wire.FRAME_WELCOME) {
            throw IOException("o servidor não fala este protocolo MPI")
        }
        return inp.readInt() to inp.readInt()
    }

    fun Comm_connect(portName: String, info: Info = INFO_NULL, root: Int = 0, comm: Comm = COMM_SELF): Comm {
        checkInit()
        checkRoot(root, comm)
        val timeout = info.get("timeout")?.toIntOrNull() ?: 10_000
        if (Relay.isRelayPortName(portName.trim())) {
            val stream = Relay.connect(portName.trim())
            try {
                stream.readTimeoutMs = timeout
                val (contextId, remoteSize) = connectHandshake(stream.input, stream.output, comm)
                stream.readTimeoutMs = 0
                return newInterComm(StreamChannel(stream.input, stream.output, stream::close), contextId, comm, remoteSize)
            } catch (e: SocketTimeoutException) {
                stream.close()
                throw MpiException(ERR_PORT, "ninguém respondeu em $portName (o outro celular está esperando?)")
            } catch (e: IOException) {
                stream.close()
                throw MpiException(ERR_PORT, "não foi possível conectar a $portName: ${e.message}")
            }
        }
        val (host, port) = parsePortName(portName)
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeout)
            socket.tcpNoDelay = true
            socket.soTimeout = timeout
            val inp = socket.getInputStream()
            val out = socket.getOutputStream()
            val (contextId, remoteSize) = connectHandshake(inp, out, comm)
            socket.soTimeout = 0
            return newInterComm(StreamChannel(inp, out, socket::close), contextId, comm, remoteSize)
        } catch (e: SocketTimeoutException) {
            socket.close()
            throw MpiException(ERR_PORT, "tempo esgotado conectando a $portName")
        } catch (e: IOException) {
            socket.close()
            throw MpiException(ERR_PORT, "não foi possível conectar a $portName: ${e.message}")
        }
    }

    fun Publish_name(serviceName: String, info: Info = INFO_NULL, portName: String) {
        checkInit()
        val preferred = relayPorts[portName]?.broker
        Relay.publishName(serviceName, portName, preferred)
        publishedNames[serviceName] = portName
    }

    fun Unpublish_name(serviceName: String, info: Info = INFO_NULL, portName: String? = null) {
        checkInit()
        val p = publishedNames.remove(serviceName)
            ?: throw MpiException(ERR_SERVICE, "nome \"$serviceName\" não foi publicado")
        Relay.unpublishName(serviceName, portName ?: p)
    }

    fun Lookup_name(serviceName: String, info: Info = INFO_NULL): String {
        checkInit()
        val timeout = info.get("timeout")?.toLongOrNull() ?: 8_000
        return Relay.lookupName(serviceName, timeout)
            ?: throw MpiException(ERR_NAME, "nenhuma porta publicada com o nome \"$serviceName\"")
    }

    fun Comm_disconnect(comm: Comm) {
        if (!comm.isInter) throw MpiException(ERR_COMM, "só inter-comunicadores podem ser desconectados aqui")
        comm.disconnect()
        liveComms.remove(comm)
    }

    fun Send(buf: Any, count: Int, datatype: Datatype, dest: Int, tag: Int, comm: Comm) {
        Wait(Isend(buf, count, datatype, dest, tag, comm))
    }

    fun Recv(buf: Any, count: Int, datatype: Datatype, source: Int, tag: Int, comm: Comm): Status =
        Wait(Irecv(buf, count, datatype, source, tag, comm))

    fun Isend(buf: Any, count: Int, datatype: Datatype, dest: Int, tag: Int, comm: Comm): Request {
        checkInit()
        if (tag < 0 || tag > TAG_UB) throw MpiException(ERR_TAG, "tag inválida: $tag")
        if (dest == PROC_NULL) return Request(Request.Kind.SEND, comm).apply {
            status.MPI_SOURCE = PROC_NULL
            status.MPI_TAG = ANY_TAG
            complete()
        }
        if (dest < 0 || dest >= comm.targetSize) throw MpiException(ERR_RANK, "rank de destino inválido: $dest")

        val msg = Message(comm.contextId, comm.rank, dest, tag, datatype.id, datatype.pack(buf, count))
        return comm.isend(msg)
    }

    fun Irecv(buf: Any, count: Int, datatype: Datatype, source: Int, tag: Int, comm: Comm): Request {
        checkInit()
        if (tag != ANY_TAG && (tag < 0 || tag > TAG_UB)) throw MpiException(ERR_TAG, "tag inválida: $tag")
        val req = Request(Request.Kind.RECV, comm)
        if (source == PROC_NULL) {
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

    fun Wait(request: Request): Status {
        request.await()
        request.error?.let { throw it }
        return request.status
    }

    fun Test(request: Request): Status? {
        if (!request.isComplete) return null
        request.error?.let { throw it }
        return request.status
    }

    fun Cancel(request: Request) {
        if (request.kind == Request.Kind.RECV) request.comm.engine.cancel(request)
    }

    fun Test_cancelled(status: Status): Boolean = status.cancelled

    fun Probe(source: Int, tag: Int, comm: Comm): Status =
        comm.engine.probe(comm.contextId, source, tag, blocking = true)!!

    fun Iprobe(source: Int, tag: Int, comm: Comm): Status? =
        comm.engine.probe(comm.contextId, source, tag, blocking = false)

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
        ERR_NAME -> "MPI_ERR_NAME"
        ERR_PORT -> "MPI_ERR_PORT"
        ERR_SERVICE -> "MPI_ERR_SERVICE"
        else -> "MPI_ERR_OTHER"
    }

    private fun newInterComm(channel: StreamChannel, contextId: Int, local: Comm, remoteSize: Int): Comm {
        val c = Comm(
            name = "intercomm#$contextId",
            rank = local.rank,
            size = local.size,
            remoteSize = remoteSize,
            contextId = contextId,
            channel = channel,
        )
        liveComms.add(c)
        return c
    }

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
