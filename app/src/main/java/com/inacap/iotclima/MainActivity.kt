package com.inacap.iotclima

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.inacap.iotclima.databinding.ActivityMainBinding

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
        dbRef.child("telemetria").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val temp = snapshot.child("temperatura").getValue(Double::class.java) ?: 0.0
                val hum = snapshot.child("humedad").getValue(Double::class.java) ?: 0.0

                binding.tvTemperatura.text = String.format("%.1f °C", temp)
                binding.tvHumedad.text = String.format("%.1f %%", hum)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(applicationContext, "Error al sincronizar datos", Toast.LENGTH_SHORT).show()
            }
        })

        dbRef.child("actuadores").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val ventilacion = snapshot.child("ventilacion").getValue(Boolean::class.java) ?: false
                val bomba = snapshot.child("bomba").getValue(Boolean::class.java) ?: false

                binding.swVentilacion.isChecked = ventilacion
                binding.swBomba.isChecked = bomba
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun setupControls() {
        binding.swModoManual.setOnCheckedChangeListener { _, isChecked ->
            binding.swVentilacion.isEnabled = isChecked
            binding.swBomba.isEnabled = isChecked
            dbRef.child("configuracion").child("modo_manual").setValue(isChecked)
        }

        binding.swVentilacion.setOnCheckedChangeListener { _, isChecked ->
            if (binding.swModoManual.isChecked) {
                dbRef.child("actuadores").child("ventilacion").setValue(isChecked)
            }
        }

        binding.swBomba.setOnCheckedChangeListener { _, isChecked ->
            if (binding.swModoManual.isChecked) {
                dbRef.child("actuadores").child("bomba").setValue(isChecked)
            }
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}