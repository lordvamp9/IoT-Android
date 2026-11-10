package com.inacap.iotclima

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.inacap.iotclima.databinding.ActivityLoginBinding

/**
 * Pantalla de inicio de sesión de vamp9 IoT.
 * Autenticación nativa y segura mediante Firebase Authentication.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        // Si el operador ya inició sesión previamente en Firebase, avanzar directo al Dashboard
        if (auth.currentUser != null) {
            goToDashboard()
        }
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.progressBar.visibility = View.VISIBLE
            binding.btnLogin.isEnabled = false

            iniciarSesionConFirebase(email, password)
        }
    }

    private fun iniciarSesionConFirebase(email: String, pass: String) {
        auth.signInWithEmailAndPassword(email, pass)
            .addOnCompleteListener(this) { task ->
                binding.progressBar.visibility = View.GONE
                binding.btnLogin.isEnabled = true

                if (task.isSuccessful) {
                    Toast.makeText(this, "Sesión iniciada con éxito", Toast.LENGTH_SHORT).show()
                    goToDashboard()
                } else {
                    val mensaje = when (val ex = task.exception) {
                        is FirebaseAuthInvalidUserException -> "No existe una cuenta registrada con este correo"
                        is FirebaseAuthInvalidCredentialsException -> "Credenciales incorrectas (correo o contraseña no válidos)"
                        is FirebaseNetworkException -> "Error de conexión: verifica tu acceso a Internet"
                        else -> ex?.localizedMessage ?: "Error al autenticar con Firebase"
                    }
                    Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun goToDashboard() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}