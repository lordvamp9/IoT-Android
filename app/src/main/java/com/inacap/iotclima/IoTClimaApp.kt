package com.inacap.iotclima

import android.app.Application

/**
 * Clase Application principal de la solución vamp9 IoT.
 * Punto de entrada de la app. Firestore Android conserva una caché local por defecto.
 * La entrega de operaciones pendientes debe validarse con pruebas de reconexión.
 */
class IoTClimaApp : Application() {

    // Firestore Android mantiene caché local persistente por defecto.
}
