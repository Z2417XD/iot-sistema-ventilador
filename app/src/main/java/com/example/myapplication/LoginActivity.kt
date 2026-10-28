package com.example.myapplication

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import java.security.MessageDigest

// ---------------------------------------------------------------------------
// Pantalla de inicio de sesión.
// Usuario genérico: admin / 1234 (contraseña almacenada como hash SHA-256).
// ---------------------------------------------------------------------------

private const val USUARIO = "admin"
private const val HASH_CLAVE = "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4" // SHA-256 de "1234"
private const val MAX_INTENTOS = 3                // intentos antes del bloqueo
private const val BLOQUEO_MS = 30_000L            // duración del bloqueo
private const val ESPERA_FIREBASE_MS = 5_000L     // espera máxima de la sesión de Firebase

/** Datos de la sesión activa. */
object Sesion {
    var activa = false
    var usuario = ""
}

class LoginActivity : AppCompatActivity() {

    private lateinit var etUsuario: EditText
    private lateinit var etClave: EditText
    private lateinit var btnIngresar: Button
    private lateinit var tvError: TextView

    private val principal = Handler(Looper.getMainLooper())
    private var intentosFallidos = 0
    private var appAbierta = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_login)
        FondoAnimado(findViewById(R.id.raizLogin))

        etUsuario = findViewById(R.id.etUsuario)
        etClave = findViewById(R.id.etClave)
        btnIngresar = findViewById(R.id.btnIngresar)
        tvError = findViewById(R.id.tvErrorLogin)

        btnIngresar.setOnClickListener { intentarIngreso() }
        // Botón "Listo" del teclado.
        etClave.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_DONE) {
                intentarIngreso()
                true
            } else {
                false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        principal.removeCallbacksAndMessages(null)
    }

    /** Valida usuario y contraseña. */
    private fun intentarIngreso() {
        if (!btnIngresar.isEnabled) return
        val usuario = etUsuario.text.toString().trim()
        val clave = etClave.text.toString()

        if (usuario == USUARIO && sha256(clave) == HASH_CLAVE) {
            entrar(usuario)
            return
        }

        intentosFallidos++
        etClave.text.clear()
        if (intentosFallidos >= MAX_INTENTOS) {
            bloquear()
        } else {
            mostrarError(getString(R.string.login_error, MAX_INTENTOS - intentosFallidos))
        }
    }

    /** Bloquea el ingreso temporalmente tras varios intentos fallidos. */
    private fun bloquear() {
        intentosFallidos = 0
        btnIngresar.isEnabled = false
        mostrarError(getString(R.string.login_bloqueado, BLOQUEO_MS / 1000))
        principal.postDelayed({
            btnIngresar.isEnabled = true
            tvError.visibility = View.GONE
        }, BLOQUEO_MS)
    }

    /** Inicia la sesión y abre la pantalla principal. */
    private fun entrar(usuario: String) {
        Sesion.activa = true
        Sesion.usuario = usuario
        btnIngresar.isEnabled = false
        tvError.visibility = View.GONE

        // Sesión anónima de Firebase, requerida por las reglas de Firestore para escribir.
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            abrirApp()
            return
        }
        auth.signInAnonymously().addOnCompleteListener(this) { abrirApp() }
        principal.postDelayed({ abrirApp() }, ESPERA_FIREBASE_MS)
    }

    private fun abrirApp() {
        if (appAbierta) return
        appAbierta = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun mostrarError(mensaje: String) {
        tvError.text = mensaje
        tvError.visibility = View.VISIBLE
    }
}

/** Hash SHA-256 de un texto, en hexadecimal. */
private fun sha256(texto: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(texto.toByteArray())
        .joinToString("") { "%02x".format(it) }
