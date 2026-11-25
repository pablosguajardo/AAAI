package com.aaai.app.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Sesión de Android Auto para AAAI.
 *
 * Cada vez que el host de Android Auto conecta con la app se crea una nueva sesión.
 * La sesión crea la pantalla inicial: [UpdateAlertScreen].
 */
class AutoCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen =
        UpdateAlertScreen(carContext)
}
