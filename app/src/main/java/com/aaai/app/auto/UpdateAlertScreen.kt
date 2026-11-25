package com.aaai.app.auto

import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla de Android Auto que muestra los updates disponibles de las apps instaladas.
 *
 * Estados:
 *  - LOADING  : mientras se consulta la DB y GitHub
 *  - UPDATES  : hay updates — muestra lista con nombres y versiones
 *  - UP_TO_DATE: todo al día — muestra mensaje corto y botón para cerrar
 *  - ERROR    : no se pudo leer la DB o error inesperado
 */
class UpdateAlertScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "com.aaai.app-UpdateAlertScreen"
    }

    private enum class State { LOADING, UPDATES, UP_TO_DATE, ERROR }

    private var state: State = State.LOADING
    private var updates: List<AutoUpdateNotifier.AppUpdate> = emptyList()
    private var errorMsg: String = ""

    // Scope de coroutines cancelado cuando la Screen es destruida
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })

        // IMPORTANTE: Action.BACK (usado en setHeaderAction) por defecto solo hace
        // ScreenManager.pop(). Como esta es la única/root Screen de la sesión, el pop
        // no tiene a dónde volver y el botón atrás queda sin efecto visible para el
        // usuario. Interceptamos el evento para cerrar la app realmente en Android Auto.
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                carContext.finishCarApp()
            }
        })
        scope.launch {
            Log.d(TAG, "▶ Checking for updates (car screen)")
            try {
                // getAvailableUpdates() hace IO (DB + red): ejecutar fuera del hilo principal
                val result = withContext(Dispatchers.IO) {
                    AutoUpdateNotifier(carContext).getAvailableUpdates()
                }
                updates = result
                state = if (result.isEmpty()) State.UP_TO_DATE else State.UPDATES
                Log.d(TAG, "Updates found: ${result.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking updates: ${e.message}", e)
                errorMsg = e.message ?: "Unknown error"
                state = State.ERROR
            }
            invalidate()   // pide a Android Auto que llame onGetTemplate() de nuevo
        }
    }

    override fun onGetTemplate(): Template {
        return when (state) {

            State.LOADING -> MessageTemplate.Builder("Checking for updates…")
                .setTitle("AAAI Updates")
                .setLoading(true)
                .build()

            State.UP_TO_DATE -> MessageTemplate.Builder("✓  All installed apps are up to date.")
                .setTitle("AAAI Updates")
                .addAction(
                    Action.Builder()
                        .setTitle("OK")
                        .setOnClickListener { finish() }
                        .build()
                )
                .build()

            State.UPDATES -> {
                val count = updates.size
                val header = if (count == 1) "1 update available" else "$count updates available"

                // ListTemplate (en vez de MessageTemplate) porque Row alinea título/texto
                // a la izquierda de forma nativa; MessageTemplate siempre centra el texto
                // y no permite elegir alineación.
                val itemList = ItemList.Builder().apply {
                    updates.forEach { u ->
                        addItem(
                            Row.Builder()
                                .setTitle(u.displayName)
                                .addText("${u.installed}  →  ${u.latest}")
                                .build()
                        )
                    }
                }.build()

                val openOnPhoneAction = Action.Builder()
                    .setTitle("Open on phone")
                    .setOnClickListener {
                        // IMPORTANTE: carContext.startActivity() fuerza el lanzamiento en el
                        // display virtual del auto (launchDisplayId), lo cual el sistema
                        // deniega con "Permission Denial" para actividades normales (no
                        // distraction-optimized). Usamos applicationContext para que el
                        // intent se resuelva en el display por defecto (la pantalla del
                        // teléfono), que es lo que realmente queremos aquí.
                        try {
                            carContext.applicationContext.startActivity(
                                android.content.Intent(
                                    carContext.applicationContext,
                                    com.aaai.app.LauncherActivity::class.java
                                ).apply {
                                    addCategory(android.content.Intent.CATEGORY_DEFAULT)
                                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "startActivity failed: ${e.message}")
                        }
                        finish()
                    }
                    .build()

                // IMPORTANTE: ActionStrip de ListTemplate solo permite 1 acción con título
                // personalizado (ActionsConstraints.ACTION_STRIP). Por eso "Dismiss" se mapea
                // al header action (botón atrás estándar) en vez de ir en el ActionStrip.
                ListTemplate.Builder()
                    .setTitle(header)
                    .setHeaderAction(Action.BACK)
                    .setSingleList(itemList)
                    .setActionStrip(
                        ActionStrip.Builder()
                            .addAction(openOnPhoneAction)
                            .build()
                    )
                    .build()
            }

            State.ERROR -> MessageTemplate.Builder("Could not check updates:\n$errorMsg")
                .setTitle("AAAI Updates")
                .addAction(
                    Action.Builder()
                        .setTitle("Close")
                        .setOnClickListener { finish() }
                        .build()
                )
                .build()
        }
    }

}
