package com.aaai.app.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point del CarAppService para Android Auto.
 *
 * Android Auto lo inicia cuando el usuario abre AAAI desde el launcher del auto.
 * Crea una [AutoCarSession] que a su vez muestra la pantalla de updates disponibles.
 *
 * Declarado en AndroidManifest con la action "androidx.car.app.CarAppService" y
 * categoría "androidx.car.app.category.OTHER".
 */
class AutoCarAppService : CarAppService() {

    /**
     * Durante el desarrollo / testing con DHU se acepta cualquier host.
     * En producción se podría restringir a hosts conocidos de Google.
     */
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = AutoCarSession()
}
