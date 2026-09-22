package br.mpi.fumaca.mpi

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Faz o papel do celular "servidor" para testar o app como cliente (só roda com MPI_E2E_SERVER=1). */
class E2eServer {
    @Test
    fun aceitaOApp() {
        assumeTrue(System.getenv("MPI_E2E_SERVER") != null)
        if (!MPI.Initialized()) MPI.Init()
        val info = MPI.Info_create()
        info.set("host", "127.0.0.1")
        val port = MPI.Open_port(info)
        println("E2E: aguardando em $port")
        val c = MPI.Comm_accept(port)
        MPI.Close_port(port)
        println("E2E: app conectou")
        Thread.sleep(1500)
        MPI.Send(intArrayOf(3, 1), 2, MPI.INT, 0, 1, c)
        println("E2E: sinal enviado")
        val buf = IntArray(2)
        val st = MPI.Recv(buf, 2, MPI.INT, MPI.ANY_SOURCE, MPI.ANY_TAG, c)
        println("E2E: recebido tag=${st.MPI_TAG} dados=${buf.toList()}")
        Thread.sleep(8000)
        MPI.Comm_disconnect(c)
    }
}
