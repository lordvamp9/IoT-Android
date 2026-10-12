package com.inacap.iotclima

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.inacap.iotclima.databinding.ActivityMainBinding

/**
 * Panel de Control OT y Telemetría Agrícola de vanguardia.
 * Desarrollado para vamp9 IoT.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var dbRef: DatabaseReference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        dbRef = FirebaseDatabase.getInstance().reference

        setupFirebaseListeners()
        setupControls()
    }

    private fun setupFirebaseListeners() {
        // Telemetría con 100% de exactitud (respetando los datos reales del sensor sin truncamiento artificial)
        dbRef.child("telemetria").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val rawTemp = snapshot.child("temperatura").value
                val rawHum = snapshot.child("humedad").value

                val formattedTemp = formatExactValue(rawTemp, "°C")
                val formattedHum = formatExactValue(rawHum, "%")

                binding.tvTemperatura.text = formattedTemp
                binding.tvHumedad.text = formattedHum

                // Pasar valores exactos a la simulación 3D
                val tempDouble = parseDoubleExact(rawTemp)
                val humDouble = parseDoubleExact(rawHum)
                binding.agroSimulationView.updateTelemetry(tempDouble, humDouble)

                // Indicador contextual de telemetría en tiempo real
                binding.tvTempBadge.text = if (tempDouble in 18.0..28.0) "Rango Térmico Óptimo" else "Atención Climática"
                binding.tvHumBadge.text = if (humDouble in 40.0..75.0) "Humedad Adecuada" else "Monitoreo Requerido"
                binding.tvStatusLabel.text = "Sincronizado"
            }

            override fun onCancelled(error: DatabaseError) {
                binding.tvStatusLabel.text = "Sin Conexión"
                Toast.makeText(applicationContext, "Error al sincronizar datos: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })

        // Escucha en tiempo real de los actuadores de campo
        dbRef.child("actuadores").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val ventilacion = snapshot.child("ventilacion").getValue(Boolean::class.java) ?: false
                val bomba = snapshot.child("bomba").getValue(Boolean::class.java) ?: false

                binding.swVentilacion.isChecked = ventilacion
                binding.swBomba.isChecked = bomba

                // Sincronizar simulación 3D
                binding.agroSimulationView.setVentilation(ventilacion)
                binding.agroSimulationView.setPump(bomba)

                actualizarEtiquetasActuadores(ventilacion, bomba)
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        // Escuchar el estado de modo manual si ya estuviese configurado
        dbRef.child("configuracion").child("modo_manual").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val modoManual = snapshot.getValue(Boolean::class.java) ?: false
                if (binding.swModoManual.isChecked != modoManual) {
                    binding.swModoManual.isChecked = modoManual
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun setupControls() {
        // Toggle Modo Manual: Despliega la animación 3D de inspección y habilita control directo
        binding.swModoManual.setOnCheckedChangeListener { view, isChecked ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

            binding.swVentilacion.isEnabled = isChecked
            binding.swBomba.isEnabled = isChecked

            // Activar simulación e inspección 3D
            binding.agroSimulationView.setManualMode(isChecked)

            if (isChecked) {
                binding.tvModoManualDesc.text = "Modo Manual ACTIVO • Control físico directo habilitado"
                binding.tvSimulationStatus.text = "Modo Manual: Mueve los actuadores para simular la compuerta y riego"
                binding.tvBadge3D.text = "Control Manual 3D"
            } else {
                binding.tvModoManualDesc.text = "Modo Automático • Control inteligente por sensores"
                binding.tvSimulationStatus.text = "Simulación en vivo de Compuerta y Riego"
                binding.tvBadge3D.text = "Automático"
            }

            dbRef.child("configuracion").child("modo_manual").setValue(isChecked)
        }

        // Control Compuerta Ventilación (Servo SG90)
        binding.swVentilacion.setOnCheckedChangeListener { view, isChecked ->
            if (binding.swModoManual.isChecked) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                binding.agroSimulationView.setVentilation(isChecked)
                actualizarEtiquetaVentilacion(isChecked)
                dbRef.child("actuadores").child("ventilacion").setValue(isChecked)
            }
        }

        // Control Bomba de Riego (Relé)
        binding.swBomba.setOnCheckedChangeListener { view, isChecked ->
            if (binding.swModoManual.isChecked) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                binding.agroSimulationView.setPump(isChecked)
                actualizarEtiquetaBomba(isChecked)
                dbRef.child("actuadores").child("bomba").setValue(isChecked)
            }
        }

        // Cerrar Sesión
        binding.btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun actualizarEtiquetasActuadores(ventilacion: Boolean, bomba: Boolean) {
        actualizarEtiquetaVentilacion(ventilacion)
        actualizarEtiquetaBomba(bomba)
    }

    private fun actualizarEtiquetaVentilacion(abierta: Boolean) {
        binding.tvVentilacionEstado.text = if (abierta) {
            "Servo SG90 • Abierta (90° - Ventilación ON)"
        } else {
            "Servo SG90 • Cerrada (0°)"
        }
    }

    private fun actualizarEtiquetaBomba(activa: Boolean) {
        binding.tvBombaEstado.text = if (activa) {
            "Relé / Aspersores • Regando (Flujo OK)"
        } else {
            "Relé / Aspersores • Inactiva"
        }
    }

    /**
     * Formatea el valor de telemetría preservando el 100% de exactitud numérica recibida.
     */
    private fun formatExactValue(raw: Any?, unit: String): String {
        if (raw == null) return "-- $unit"
        return when (raw) {
            is Double -> {
                if (raw == raw.toLong().toDouble()) {
                    "${raw.toLong()} $unit"
                } else {
                    "$raw $unit"
                }
            }
            is Long -> "$raw $unit"
            is Int -> "$raw $unit"
            is Float -> {
                val d = raw.toDouble()
                if (d == d.toLong().toDouble()) "${d.toLong()} $unit" else "$raw $unit"
            }
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.endsWith(unit, ignoreCase = true)) trimmed else "$trimmed $unit"
            }
            else -> "$raw $unit"
        }
    }

    /**
     * Convierte de manera segura cualquier tipo numérico o string a Double sin distorsionar el valor.
     */
    private fun parseDoubleExact(raw: Any?): Double {
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}