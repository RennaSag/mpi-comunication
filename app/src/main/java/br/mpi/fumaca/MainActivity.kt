package br.mpi.fumaca

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.mpi.fumaca.mpi.Comm
import br.mpi.fumaca.mpi.MPI
import br.mpi.fumaca.mpi.MpiException
import br.mpi.fumaca.mpi.Request
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Programa MPI de dois processos (um por celular):
 *
 *   MPI_Init_thread
 *   servidor: MPI_Open_port -> MPI_Comm_accept     cliente: MPI_Comm_connect
 *   laço:  MPI_Irecv(ANY_SOURCE, ANY_TAG) + MPI_Wait  -> mostra o sinal de fumaça
 *   toque: MPI_Send(dest = 0 do grupo remoto, tag = TAG_FUMACA)
 *   saída: MPI_Send(TAG_ADEUS), MPI_Cancel, MPI_Comm_disconnect, MPI_Finalize
 */
class MainActivity : Activity() {

    companion object {
        /** Tags distinguem os tipos de mensagem (Seção 3.2.3). */
        const val TAG_FUMACA = 1
        const val TAG_ADEUS = 2
        /** Quantas nuvens de fumaça cada sinal tem (primeiro inteiro da mensagem). */
        const val NUVENS_POR_SINAL = 1
        /** Rank do outro celular no grupo remoto do inter-comunicador. */
        const val OUTRO_CELULAR = 0
    }

    @Volatile private var comm: Comm? = null
    @Volatile private var openPort: String? = null
    @Volatile private var recvRequest: Request? = null
    private var scene: SceneView? = null
    private var sequence = 0

    /** Chamadas MPI bloqueantes nunca rodam na thread de interface. */
    private val mpiThread = Executors.newSingleThreadExecutor()

    private lateinit var statusView: TextView
    private lateinit var serverButton: Button
    private lateinit var clientButton: Button
    private lateinit var cancelButton: Button
    private lateinit var portInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!MPI.Initialized()) MPI.Init_thread(MPI.THREAD_MULTIPLE)
        showSetup()
    }

    // ---------------------------------------------------------------- Tela de conexão

    private fun showSetup() {
        scene = null
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (40 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }

        root.addView(label("Sinal de Fumaça", 30f, bold = true, color = 0xFFFFE0B2.toInt()))
        root.addView(label("Duas fogueiras, duas montanhas, uma conexão MPI.", 15f, color = 0xFFB0BEC5.toInt()), spaced(4, 24))

        root.addView(label("1. No celular com o hotspot ligado", 17f, bold = true))
        root.addView(label("Abre uma porta (MPI_Open_port) e espera o outro celular (MPI_Comm_accept).", 14f, color = 0xFFB0BEC5.toInt()), spaced(4, 8))
        serverButton = button("Acender fogueira (servidor)", 0xFFE8641C.toInt()) { startServer() }
        root.addView(serverButton, spaced(0, 28))

        root.addView(label("2. No celular conectado ao hotspot", 17f, bold = true))
        root.addView(label("Digite o port_name mostrado no outro celular (já preenchido com o IP do hotspot).", 14f, color = 0xFFB0BEC5.toInt()), spaced(4, 8))
        portInput = EditText(this).apply {
            setText(NetUtil.hotspotGateway(this@MainActivity)?.let { "$it:${MPI.DEFAULT_PORT}" } ?: "")
            hint = "ip:porta, ex.: 192.168.43.1:${MPI.DEFAULT_PORT}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF78909C.toInt())
            textSize = 17f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
        }
        root.addView(portInput, spaced(0, 8))
        clientButton = button("Conectar (cliente)", 0xFF37659A.toInt()) { startClient() }
        root.addView(clientButton, spaced(0, 24))

        statusView = label("", 15f, color = 0xFFFFE0B2.toInt()).apply { typeface = Typeface.MONOSPACE }
        root.addView(statusView, spaced(0, 12))
        cancelButton = button("Cancelar", 0xFF455A64.toInt()) { cancelWaiting() }.apply { visibility = View.GONE }
        root.addView(cancelButton)

        val scroll = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0B1D3A.toInt(), 0xFF2E3E7A.toInt(), 0xFF5B3A5C.toInt()),
            )
            addView(root)
        }
        setContentView(scroll)
    }

    private fun startServer() {
        setBusy(true)
        val ip = NetUtil.localIp()
        if (ip == null) {
            setBusy(false)
            statusView.text = "Nenhuma rede encontrada. Ligue o hotspot deste celular."
            return
        }
        statusView.text = "Abrindo porta..."
        thread(name = "mpi-accept") {
            try {
                val info = MPI.Info_create()
                info.set("host", ip)
                val port = MPI.Open_port(info)
                openPort = port
                runOnUiThread {
                    statusView.text = "port_name (MPI_Open_port):\n\n    $port\n\nAguardando o outro celular...\n(MPI_Comm_accept)"
                    cancelButton.visibility = View.VISIBLE
                }
                val c = MPI.Comm_accept(port, MPI.INFO_NULL, 0, MPI.COMM_SELF)
                MPI.Close_port(port)
                openPort = null
                runOnUiThread { onConnected(c, "servidor") }
            } catch (e: MpiException) {
                openPort?.let { MPI.Close_port(it) }
                openPort = null
                runOnUiThread {
                    setBusy(false)
                    statusView.text = if (e.errorClass == MPI.ERR_PORT) "Espera cancelada." else "Erro: ${e.message}"
                }
            }
        }
    }

    private fun cancelWaiting() {
        openPort?.let { MPI.Close_port(it) } // o MPI_Comm_accept pendente falha com MPI_ERR_PORT
    }

    private fun startClient() {
        val portName = portInput.text.toString().trim()
        if (portName.isEmpty()) {
            statusView.text = "Informe o port_name (ip:porta) do outro celular."
            return
        }
        setBusy(true)
        statusView.text = "Conectando a $portName...\n(MPI_Comm_connect)"
        thread(name = "mpi-connect") {
            try {
                val c = MPI.Comm_connect(portName, MPI.INFO_NULL, 0, MPI.COMM_SELF)
                runOnUiThread { onConnected(c, "cliente") }
            } catch (e: MpiException) {
                runOnUiThread {
                    setBusy(false)
                    statusView.text = "Erro: ${e.message}\n\nVerifique se o outro celular já acendeu a fogueira e se este está no hotspot dele."
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        serverButton.isEnabled = !busy
        clientButton.isEnabled = !busy
        portInput.isEnabled = !busy
        for (v in listOf(serverButton, clientButton, portInput)) v.alpha = if (busy) 0.45f else 1f
        if (!busy) cancelButton.visibility = View.GONE
    }

    // ---------------------------------------------------------------- Cena conectada

    private fun onConnected(c: Comm, role: String) {
        comm = c
        val view = SceneView(this)
        view.header = "Você: $role · rank ${MPI.Comm_rank(c)} · grupo remoto: ${MPI.Comm_remote_size(c)} processo"
        view.onFireTapped = { sendSmokeSignal() }
        view.showMessage("Conectado! Toque na fogueira.", 4f)
        scene = view
        setContentView(view)
        startReceiver(c)
    }

    /** Toque na fogueira: MPI_Send para o rank 0 do grupo remoto. */
    private fun sendSmokeSignal() {
        val c = comm ?: return
        val view = scene ?: return
        view.flare()
        val msg = intArrayOf(NUVENS_POR_SINAL, ++sequence)
        mpiThread.execute {
            try {
                MPI.Send(msg, msg.size, MPI.INT, OUTRO_CELULAR, TAG_FUMACA, c)
                runOnUiThread { view.showMessage("Sinal enviado! (MPI_Send, tag $TAG_FUMACA)") }
            } catch (e: MpiException) {
                runOnUiThread { view.showMessage("Falha ao enviar o sinal") }
            }
        }
    }

    /** Laço de recepção: um MPI_Irecv com curingas, completado por MPI_Wait. */
    private fun startReceiver(c: Comm) {
        thread(name = "mpi-recv", isDaemon = true) {
            val buf = IntArray(2)
            while (true) {
                val req = try {
                    MPI.Irecv(buf, buf.size, MPI.INT, MPI.ANY_SOURCE, MPI.ANY_TAG, c)
                } catch (e: MpiException) {
                    break
                }
                recvRequest = req
                val status = try {
                    MPI.Wait(req)
                } catch (e: MpiException) {
                    runOnUiThread { peerGone(c, "A conexão com o outro celular caiu.") }
                    break
                }
                if (MPI.Test_cancelled(status)) break
                when (status.MPI_TAG) {
                    TAG_FUMACA -> {
                        val nuvens = if (MPI.Get_count(status, MPI.INT) >= 1) buf[0] else NUVENS_POR_SINAL
                        runOnUiThread {
                            scene?.showSmokeSignal(nuvens)
                            vibrate()
                        }
                    }
                    TAG_ADEUS -> {
                        runOnUiThread { peerGone(c, "O outro celular apagou a fogueira.") }
                        break
                    }
                }
            }
        }
    }

    private fun peerGone(c: Comm, reason: String) {
        if (comm !== c) return
        comm = null
        mpiThread.execute { MPI.Comm_disconnect(c) }
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Fogueira apagada")
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton("Voltar") { _, _ -> showSetup() }
            .show()
    }

    /** Sai da conversa avisando o outro lado e desfazendo o inter-comunicador. */
    private fun leave(c: Comm) {
        try {
            MPI.Send(IntArray(0), 0, MPI.INT, OUTRO_CELULAR, TAG_ADEUS, c)
        } catch (_: MpiException) {
        }
        recvRequest?.let { MPI.Cancel(it) }
        MPI.Comm_disconnect(c)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val c = comm
        if (c == null) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
        comm = null
        mpiThread.execute { leave(c) }
        showSetup()
    }

    override fun onDestroy() {
        super.onDestroy()
        val c = comm
        comm = null
        // MPI_Finalize encerra o processo MPI; depois dele não se pode chamar MPI_Init de novo,
        // então o processo Android também termina.
        val cleanup = thread {
            try {
                if (c != null) leave(c)
                openPort?.let { MPI.Close_port(it) }
                MPI.Finalize()
            } catch (_: Exception) {
            }
        }
        cleanup.join(2500)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---------------------------------------------------------------- Auxiliares

    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(180)
        }
    }

    private fun label(text: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 14 * resources.displayMetrics.density
        }
        minHeight = (52 * resources.displayMetrics.density).toInt()
        setOnClickListener { onClick() }
    }

    private fun spaced(topDp: Int, bottomDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        val d = resources.displayMetrics.density
        topMargin = (topDp * d).toInt()
        bottomMargin = (bottomDp * d).toInt()
        gravity = Gravity.CENTER_HORIZONTAL
    }
}
