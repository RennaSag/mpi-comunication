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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : Activity() {
    companion object {
        const val TAG_FUMACA = 1
        const val TAG_ADEUS = 2

        const val NUVENS_POR_SINAL = 1

        const val OUTRO_CELULAR = 0

        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }

    @Volatile private var comm: Comm? = null

    @Volatile private var openPorts: List<String> = emptyList()

    @Volatile private var publishedName: String? = null

    @Volatile private var waitCancelled = false
    @Volatile private var recvRequest: Request? = null
    private var scene: SceneView? = null
    private var fireSound: FireSound? = null
    private var sequence = 0

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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    private fun showSetup() {
        scene = null
        stopFireSound()
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (40 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }

        root.addView(label("Sinal de Fumaça", 30f, bold = true, color = 0xFFFFE0B2.toInt()))
        root.addView(label("Duas fogueiras, duas montanhas, uma conexão MPI.", 15f, color = 0xFFB0BEC5.toInt()), spaced(4, 24))

        root.addView(label("1. Em um dos celulares", 17f, bold = true))
        root.addView(label("Abre as portas (MPI_Open_port), publica um código (MPI_Publish_name) e espera o outro celular (MPI_Comm_accept).", 14f, color = 0xFFB0BEC5.toInt()), spaced(4, 8))
        serverButton = button("Acender fogueira (servidor)", 0xFFE8641C.toInt()) { startServer() }
        root.addView(serverButton, spaced(0, 28))

        root.addView(label("2. No outro celular", 17f, bold = true))
        root.addView(label("Digite o código que aparece no primeiro celular (funciona em qualquer rede, pela internet) ou o ip:porta dele (mesma rede, sem internet).", 14f, color = 0xFFB0BEC5.toInt()), spaced(4, 8))
        portInput = EditText(this).apply {
            hint = "código (ex.: K7Q2PX) ou ip:porta"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF78909C.toInt())
            textSize = 17f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) startClient()
                action == EditorInfo.IME_ACTION_GO
            }
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
        waitCancelled = false
        statusView.text = "Abrindo portas...\n(MPI_Open_port)"
        thread(name = "mpi-open") {
            val ports = mutableListOf<String>()
            val lines = StringBuilder()

            NetUtil.localIp()?.let { ip ->
                try {
                    val info = MPI.Info_create()
                    info.set("host", ip)
                    val port = MPI.Open_port(info)
                    ports += port
                    lines.append("Mesma rede (Wi-Fi / hotspot):\n    $port\n\n")
                } catch (_: MpiException) {
                }
            }

            try {
                val info = MPI.Info_create()
                info.set("transport", "relay")
                val port = MPI.Open_port(info)
                ports += port
                val code = String(CharArray(6) { CODE_ALPHABET[Random.nextInt(CODE_ALPHABET.length)] })
                try {
                    MPI.Publish_name(code, MPI.INFO_NULL, port)
                    publishedName = code
                    lines.append("Qualquer rede (internet), código:\n    $code\n\n")
                } catch (e: MpiException) {
                    lines.append("Qualquer rede (internet), port_name:\n    $port\n\n")
                }
            } catch (e: MpiException) {
                lines.append("Pela internet: indisponível (este celular está sem internet?)\n\n")
            }
            openPorts = ports
            if (ports.isEmpty() || waitCancelled) {
                closeServerPorts()
                runOnUiThread {
                    setBusy(false)
                    statusView.text = if (waitCancelled) "Espera cancelada."
                    else "Nenhuma rede encontrada. Ligue o Wi-Fi, o hotspot ou os dados móveis."
                }
                return@thread
            }
            runOnUiThread {
                statusView.text = "${lines}Aguardando o outro celular...\n(MPI_Comm_accept)"
                cancelButton.visibility = View.VISIBLE
            }

            val won = AtomicBoolean(false)
            val failures = AtomicInteger()
            for (port in ports) {
                thread(name = "mpi-accept") {
                    try {
                        val c = MPI.Comm_accept(port, MPI.INFO_NULL, 0, MPI.COMM_SELF)
                        if (won.compareAndSet(false, true)) {
                            runOnUiThread { onConnected(c, "servidor") }
                            closeServerPorts()
                        } else {
                            MPI.Comm_disconnect(c)
                        }
                    } catch (e: MpiException) {
                        if (failures.incrementAndGet() == ports.size && !won.get()) {
                            closeServerPorts()
                            runOnUiThread {
                                setBusy(false)
                                statusView.text = if (waitCancelled) "Espera cancelada." else "Erro: ${e.message}"
                            }
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    private fun closeServerPorts() {
        openPorts.forEach { MPI.Close_port(it) }
        openPorts = emptyList()
        publishedName?.let { runCatching { MPI.Unpublish_name(it) } }
        publishedName = null
    }

    private fun cancelWaiting() {
        waitCancelled = true
        thread { closeServerPorts() }
    }

    private fun startClient() {
        val typed = portInput.text.toString().trim()
        if (typed.isEmpty()) {
            statusView.text = "Digite o código ou o ip:porta do outro celular."
            return
        }
        if (!clientButton.isEnabled) return
        hideKeyboard()
        setBusy(true)

        val isCode = ':' !in typed
        thread(name = "mpi-connect") {
            try {
                val portName = if (isCode) {
                    val code = typed.uppercase().replace(" ", "").replace("-", "")
                    runOnUiThread { statusView.text = "Procurando a fogueira $code...\n(MPI_Lookup_name)" }
                    MPI.Lookup_name(code)
                } else typed
                runOnUiThread { statusView.text = "Conectando a $portName...\n(MPI_Comm_connect)" }
                val c = MPI.Comm_connect(portName, MPI.INFO_NULL, 0, MPI.COMM_SELF)
                runOnUiThread { onConnected(c, "cliente") }
            } catch (e: MpiException) {
                val hint = if (isCode) "Confira o código e se os dois celulares têm internet."
                else "Verifique se o outro celular já acendeu a fogueira e se os dois estão na mesma rede."
                runOnUiThread {
                    setBusy(false)
                    statusView.text = "Erro: ${e.message}\n\n$hint"
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

    private fun onConnected(c: Comm, role: String) {
        hideKeyboard()
        comm = c
        val view = SceneView(this)
        view.header = "Você: $role · rank ${MPI.Comm_rank(c)} · grupo remoto: ${MPI.Comm_remote_size(c)} processo"
        view.onFireTapped = { sendSmokeSignal() }
        view.showMessage("Conectado! Toque na fogueira.", 4f)
        scene = view
        setContentView(view)
        fireSound = FireSound(this).also { it.start() }
        startReceiver(c)
    }

    private fun stopFireSound() {
        fireSound?.stop()
        fireSound = null
    }

    private fun sendSmokeSignal() {
        val c = comm ?: return
        val view = scene ?: return
        view.flare()
        val msg = intArrayOf(NUVENS_POR_SINAL, ++sequence)
        mpiThread.execute {
            try {
                MPI.Send(msg, msg.size, MPI.INT, OUTRO_CELULAR, TAG_FUMACA, c)
                runOnUiThread { view.showMessage("Sinal enviado (MPI_Send, tag $TAG_FUMACA)") }
            } catch (e: MpiException) {
                runOnUiThread { view.showMessage("Falha ao enviar o sinal") }
            }
        }
    }

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
        stopFireSound()
        mpiThread.execute { MPI.Comm_disconnect(c) }
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Fogueira apagada")
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton("Voltar") { _, _ -> showSetup() }
            .show()
    }

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

    override fun onPause() {
        super.onPause()
        fireSound?.pause()
    }

    override fun onResume() {
        super.onResume()
        fireSound?.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFireSound()
        val c = comm
        comm = null

        val cleanup = thread {
            try {
                if (c != null) leave(c)
                closeServerPorts()
                MPI.Finalize()
            } catch (_: Exception) {
            }
        }
        cleanup.join(2500)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

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
