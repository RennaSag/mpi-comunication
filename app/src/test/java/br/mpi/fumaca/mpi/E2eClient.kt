package br.mpi.fumaca.mpi

import org.junit.Assume.assumeTrue
import org.junit.Test

class E2eClient {
    @Test
    fun conversaComOApp() {
        val port = System.getenv("MPI_E2E")
        assumeTrue(port != null)
        if (!MPI.Initialized()) MPI.Init()

        val portName = if (':' in port!!) port else MPI.Lookup_name(port)
        println("E2E: port_name = $portName")
        val c = MPI.Comm_connect(portName)
        MPI.Send(intArrayOf(3, 1), 2, MPI.INT, 0, 1, c)
        println("E2E: sinal enviado")
        val buf = IntArray(2)
        val st = MPI.Recv(buf, 2, MPI.INT, MPI.ANY_SOURCE, MPI.ANY_TAG, c)
        println("E2E: recebido tag=${st.MPI_TAG} dados=${buf.toList()}")
        MPI.Send(IntArray(0), 0, MPI.INT, 0, 2, c)
        MPI.Comm_disconnect(c)
    }
}
