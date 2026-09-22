package br.mpi.fumaca.mpi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.concurrent.thread

/** Simula os dois celulares no mesmo computador, conectados via localhost. */
class MpiTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun init() {
            MPI.Init_thread(MPI.THREAD_MULTIPLE)
        }
    }

    private fun connectPair(): Pair<Comm, Comm> {
        val info = MPI.Info_create()
        info.set("host", "127.0.0.1")
        info.set("port", "0")
        val port = MPI.Open_port(info)
        var server: Comm? = null
        val t = thread { server = MPI.Comm_accept(port) }
        val client = MPI.Comm_connect(port)
        t.join()
        MPI.Close_port(port)
        return server!! to client
    }

    @Test
    fun sinalDeFumacaNosDoisSentidos() {
        val (a, b) = connectPair()
        assertTrue(MPI.Comm_test_inter(a))
        assertEquals(1, MPI.Comm_remote_size(b))

        MPI.Send(intArrayOf(3, 1), 2, MPI.INT, 0, 1, a)
        val buf = IntArray(2)
        val st = MPI.Recv(buf, 2, MPI.INT, MPI.ANY_SOURCE, MPI.ANY_TAG, b)
        assertArrayEquals(intArrayOf(3, 1), buf)
        assertEquals(1, st.MPI_TAG)
        assertEquals(0, st.MPI_SOURCE)
        assertEquals(2, MPI.Get_count(st, MPI.INT))

        MPI.Send(intArrayOf(3, 7), 2, MPI.INT, 0, 1, b)
        MPI.Recv(buf, 2, MPI.INT, 0, 1, a)
        assertArrayEquals(intArrayOf(3, 7), buf)

        MPI.Comm_disconnect(a)
        MPI.Comm_disconnect(b)
    }

    @Test
    fun ordemETagsSelecionamMensagens() {
        val (a, b) = connectPair()
        for (i in 1..50) MPI.Send(intArrayOf(i), 1, MPI.INT, 0, if (i % 2 == 0) 2 else 1, a)
        MPI.Probe(0, 2, b)
        val buf = IntArray(1)
        // Mensagens de mesma tag chegam na ordem enviada (não-ultrapassagem)
        for (i in 2..50 step 2) { MPI.Recv(buf, 1, MPI.INT, 0, 2, b); assertEquals(i, buf[0]) }
        for (i in 1..49 step 2) { MPI.Recv(buf, 1, MPI.INT, 0, 1, b); assertEquals(i, buf[0]) }
        assertNull(MPI.Iprobe(MPI.ANY_SOURCE, MPI.ANY_TAG, b))
        MPI.Comm_disconnect(a)
        MPI.Comm_disconnect(b)
    }

    @Test
    fun cancelEDesconexao() {
        val (a, b) = connectPair()
        val req = MPI.Irecv(IntArray(2), 2, MPI.INT, MPI.ANY_SOURCE, MPI.ANY_TAG, a)
        assertNull(MPI.Test(req))
        MPI.Cancel(req)
        assertTrue(MPI.Test_cancelled(MPI.Wait(req)))

        // Um receive pendente falha quando o outro lado desconecta
        val pending = MPI.Irecv(IntArray(2), 2, MPI.INT, MPI.ANY_SOURCE, MPI.ANY_TAG, a)
        MPI.Comm_disconnect(b)
        val err = runCatching { MPI.Wait(pending) }.exceptionOrNull()
        assertTrue(err is MpiException)
        MPI.Comm_disconnect(a)
    }

    @Test
    fun erros() {
        val (a, b) = connectPair()
        assertEquals(MPI.ERR_TAG, runCatching { MPI.Send(IntArray(1), 1, MPI.INT, 0, -5, a) }
            .exceptionOrNull().let { (it as MpiException).errorClass })
        assertEquals(MPI.ERR_RANK, runCatching { MPI.Send(IntArray(1), 1, MPI.INT, 1, 1, a) }
            .exceptionOrNull().let { (it as MpiException).errorClass })
        MPI.Send(IntArray(4), 4, MPI.INT, 0, 1, a)
        assertEquals(MPI.ERR_TRUNCATE, runCatching { MPI.Recv(IntArray(2), 2, MPI.INT, 0, 1, b) }
            .exceptionOrNull().let { (it as MpiException).errorClass })
        assertEquals(MPI.ERR_PORT, runCatching { MPI.Comm_connect("127.0.0.1:1") }
            .exceptionOrNull().let { (it as MpiException).errorClass })
        MPI.Comm_disconnect(a)
        MPI.Comm_disconnect(b)
    }
}
