package com.xebolax.tiresias.servicio

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xebolax.tiresias.R
import com.xebolax.tiresias.base.Claves
import com.xebolax.tiresias.base.Registro
import com.xebolax.tiresias.lectura.Lector
import com.xebolax.tiresias.voz.Modo

/**
 * El nivel de la batería (SandFly c0905777a y c1127ed00), con la pantalla encendida:
 *
 * - cada vez que llega a un múltiplo del 5 o del 10 por ciento, o cada 15, 30 o
 *   60 minutos, si se quiere (de fábrica, no);
 * - al encender la pantalla, si está por debajo del nivel elegido y no carga.
 *
 * El intervalo va con un `Handler` que se para mientras la pantalla está
 * apagada (sigue donde iba al encenderla): sin alarmas y sin despertar el móvil.
 * Todo en el hilo principal (lo llaman los receptores del servicio); lo que se
 * dice va al hilo del lector, callado como los avisos del cargador.
 */
class AvisosBateria(private val servicio: AccessibilityService, private val lector: Lector) {
	private val principal = Handler(Looper.getMainLooper())
	/** El nivel (0 a 100) y si carga, de la última difusión de la batería (-1: aún no se sabe). */
	private var nivel = -1
	private var cargando = false
	private var pantallaEncendida = runCatching { servicio.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false }.getOrDefault(true)
	/** Lo que se ha elegido ahora y, con un intervalo, cuándo toca decirlo (o lo que faltaba al apagar la pantalla). */
	private var eleccion = ""
	private var proximo = 0L
	private var faltaba = -1L

	private val alIntervalo = Runnable {
		decirNivel()
		programar(intervaloMs())
	}

	/** Una difusión de la batería; [inicial] es la que llega al registrarse (el estado de ahora, no un cambio). */
	fun alCambiar(i: Intent, inicial: Boolean) {
		val n = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
		val m = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
		if (n < 0 || m <= 0) return
		val nuevo = n * 100 / m
		val estado = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
		cargando = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 || estado == BatteryManager.BATTERY_STATUS_CHARGING
		val anterior = nivel
		nivel = nuevo
		if (inicial || anterior < 0 || nuevo == anterior || !pantallaEncendida) return
		val paso = lector.ajustes.avisoNivelBateria.toIntOrNull() ?: return
		if (paso > 0 && nuevo % paso == 0) decirNivel()
	}

	/** La pantalla se enciende o se apaga. */
	fun alPantalla(encendida: Boolean) {
		pantallaEncendida = encendida
		if (!encendida) {
			// Se para y se apunta lo que faltaba: al volver a encenderla, sigue donde iba
			if (proximo > 0) faltaba = (proximo - SystemClock.elapsedRealtime()).coerceAtLeast(0)
			principal.removeCallbacks(alIntervalo)
			proximo = 0
			return
		}
		avisarSiBaja()
		val intervalo = intervaloMs()
		if (intervalo > 0) programar(if (faltaba >= 0) faltaba.coerceAtMost(intervalo) else intervalo)
		faltaba = -1
	}

	/** Han cambiado los ajustes: con otro intervalo, se empieza a contar desde ahora. */
	fun recargar() {
		val ahora = lector.ajustes.avisoNivelBateria
		if (ahora == eleccion) return
		eleccion = ahora
		principal.removeCallbacks(alIntervalo)
		proximo = 0
		faltaba = -1
		val intervalo = intervaloMs()
		if (intervalo > 0 && pantallaEncendida) programar(intervalo)
	}

	fun cerrar() {
		principal.removeCallbacks(alIntervalo)
	}

	private fun programar(ms: Long) {
		principal.removeCallbacks(alIntervalo)
		if (intervaloMs() <= 0 || !pantallaEncendida) return
		proximo = SystemClock.elapsedRealtime() + ms
		principal.postDelayed(alIntervalo, ms)
	}

	/** El intervalo elegido en milisegundos (0: no es por tiempo). */
	private fun intervaloMs(): Long {
		val e = lector.ajustes.avisoNivelBateria
		if (!e.endsWith("min")) return 0
		val minutos = e.removeSuffix("min").toLongOrNull() ?: return 0
		return minutos * 60_000L
	}

	private fun decirNivel() {
		val n = nivel
		if (n < 0) return
		val texto = lector.textos.getString(if (cargando) R.string.aviso_bateria_nivel_cargando else R.string.aviso_bateria_nivel, n)
		lector.manejador.post {
			if (lector.motivoCallarSinPedir() != null) return@post
			lector.decir(texto, Modo.ENCOLAR)
		}
	}

	/** Al encender la pantalla: por debajo del nivel elegido y sin cargar (salvo si ya lo dice lo que se oye al encenderla). */
	private fun avisarSiBaja() {
		val limite = lector.ajustes.bateriaBaja
		val n = nivel
		if (limite <= 0 || n < 0 || n >= limite || cargando) return
		if (lector.ajustes.alEncender == "estado") return
		val texto = lector.textos.getString(R.string.aviso_bateria_baja, n)
		lector.manejador.post {
			if (lector.motivoCallarSinPedir() != null) return@post
			lector.decir(texto, Modo.ENCOLAR)
		}
	}
}

/**
 * Lo que ha durado una llamada, al colgar (SandFly 72132b955): «Llamada
 * terminada: 3 minutos y 12 segundos».
 *
 * SandFly mira el estado de la llamada del teléfono, que pide el permiso de leer
 * el estado del teléfono; Tiresias no lo tiene y mira el modo del audio, que no
 * pide nada (Android 12 o posterior): la llamada empieza al pasar a «en llamada»
 * y acaba al volver al normal. Como en SandFly, una llamada que se hace cuenta
 * desde que se marca (con los tonos) y una que suena y no se coge no se dice.
 * Solo las llamadas de teléfono: las de las apps (WhatsApp) van en otro modo que
 * también usan otras cosas.
 */
class DuracionLlamada(private val servicio: AccessibilityService, private val lector: Lector) {
	private val principal = Handler(Looper.getMainLooper())
	private val audio = servicio.getSystemService(AudioManager::class.java)
	/** Cuándo empezó la llamada de ahora (null: no hay ninguna que se haya visto empezar). */
	private var inicio: Long? = null
	private var oyente: Any? = null

	fun iniciar() {
		if (Build.VERSION.SDK_INT < 31 || oyente != null) return
		val a = audio ?: return
		val o = AudioManager.OnModeChangedListener { modo -> principal.post { alCambiarModo(modo) } }
		try {
			a.addOnModeChangedListener(servicio.mainExecutor, o)
			oyente = o
		} catch (e: Exception) {
			Registro.w(ETIQUETA, "No se pudo vigilar el modo del audio", e)
		}
	}

	fun cerrar() {
		principal.removeCallbacksAndMessages(null)
		if (Build.VERSION.SDK_INT < 31) return
		val o = oyente as? AudioManager.OnModeChangedListener ?: return
		runCatching { audio?.removeOnModeChangedListener(o) }.onFailure { Registro.w(ETIQUETA, "No se pudo dejar de vigilar el modo del audio", it) }
		oyente = null
	}

	private fun alCambiarModo(modo: Int) {
		when (modo) {
			AudioManager.MODE_IN_CALL -> if (inicio == null) inicio = SystemClock.elapsedRealtime()
			// Una segunda llamada que suena con la primera en marcha no la termina
			AudioManager.MODE_RINGTONE -> Unit
			AudioManager.MODE_NORMAL -> {
				val desde = inicio ?: return
				inicio = null
				if (!lector.ajustes.duracionLlamada) return
				val segundos = ((SystemClock.elapsedRealtime() - desde) / 1000).coerceAtLeast(0)
				val texto = lector.textos.getString(R.string.llamada_terminada, duracion(segundos))
				// Un momento después: al colgar se cierra la pantalla de la llamada, y lo que dice al cambiar de ventana lo cortaría
				principal.postDelayed({ lector.manejador.post { lector.decir(texto, Modo.ENCOLAR) } }, ESPERA_MS)
			}
		}
	}

	/** «3 minutos y 12 segundos», en el idioma de Tiresias. */
	private fun duracion(segundos: Long): String {
		val horas = segundos / 3600
		val minutos = segundos % 3600 / 60
		val resto = segundos % 60
		val partes = buildList {
			if (horas > 0) add(android.icu.util.Measure(horas, android.icu.util.MeasureUnit.HOUR))
			if (minutos > 0) add(android.icu.util.Measure(minutos, android.icu.util.MeasureUnit.MINUTE))
			if (resto > 0 || isEmpty()) add(android.icu.util.Measure(resto, android.icu.util.MeasureUnit.SECOND))
		}
		val idioma = lector.textos.resources.configuration.locales[0]
		return android.icu.text.MeasureFormat.getInstance(idioma, android.icu.text.MeasureFormat.FormatWidth.WIDE).formatMeasures(*partes.toTypedArray())
	}

	companion object {
		private const val ETIQUETA = "DuracionLlamada"
		private const val ESPERA_MS = 1200L
	}
}

/**
 * El recuadro del foco (grosor y color, Android 12 o posterior) y las
 * animaciones del móvil (Android 13 o posterior) mientras Tiresias está activo.
 * Solo se tocan si se piden: de fábrica, el recuadro y las animaciones del sistema.
 */
class AparienciaSistema(private val servicio: AccessibilityService, private val lector: Lector) {
	/** Si Tiresias ha cambiado el recuadro (para devolverlo al del sistema). */
	private var recuadroPuesto = false

	/** Pone lo que digan los ajustes (en el hilo principal: al arrancar y al cambiar un ajuste). */
	fun aplicar() {
		aplicarRecuadro()
		aplicarAnimaciones(lector.ajustes.sinAnimaciones)
	}

	/** Devuelve lo del sistema (al apagar Tiresias). */
	fun devolver() {
		if (Build.VERSION.SDK_INT >= 31 && recuadroPuesto) {
			try {
				val (ancho, color) = recuadroDelSistema()
				servicio.setAccessibilityFocusAppearance(ancho, color)
				recuadroPuesto = false
			} catch (e: Exception) {
				Registro.w(ETIQUETA, "No se pudo devolver el recuadro del foco", e)
			}
		}
		aplicarAnimaciones(false)
	}

	private fun aplicarRecuadro() {
		if (Build.VERSION.SDK_INT < 31) return
		val grosor = lector.ajustes.recuadroGrosor
		val colorElegido = COLORES[lector.ajustes.recuadroColor]
		val dp = GROSORES[grosor]
		if (dp == null && colorElegido == null && !recuadroPuesto) return
		try {
			val (anchoSistema, colorSistema) = recuadroDelSistema()
			val ancho = dp?.let { (it * servicio.resources.displayMetrics.density).toInt() } ?: anchoSistema
			servicio.setAccessibilityFocusAppearance(ancho, colorElegido ?: colorSistema)
			recuadroPuesto = dp != null || colorElegido != null
		} catch (e: Exception) {
			Registro.w(ETIQUETA, "No se pudo cambiar el recuadro del foco", e)
		}
	}

	/** El grosor (en píxeles) y el color del recuadro de fábrica, de los recursos de Android (los mismos que usa él). */
	private fun recuadroDelSistema(): Pair<Int, Int> {
		val r = android.content.res.Resources.getSystem()
		val idAncho = r.getIdentifier("accessibility_focus_highlight_stroke_width", "dimen", "android")
		val idColor = r.getIdentifier("accessibility_focus_highlight_color", "color", "android")
		val ancho = if (idAncho != 0) runCatching { r.getDimensionPixelSize(idAncho) }.getOrNull() else null
		val color = if (idColor != 0) runCatching { r.getColor(idColor, null) }.getOrNull() else null
		return (ancho ?: (ANCHO_SISTEMA_DP * servicio.resources.displayMetrics.density).toInt()) to (color ?: COLOR_SISTEMA)
	}

	/**
	 * Quita las animaciones ([quitar]) o devuelve las que había. La escala de antes se apunta en los ajustes de
	 * este móvil, así se devuelve aunque Tiresias se haya cerrado de golpe con ellas quitadas.
	 */
	private fun aplicarAnimaciones(quitar: Boolean) {
		if (Build.VERSION.SDK_INT < 33) return
		val p = lector.ajustes.p
		val antes = p.getFloat(Claves.ANIMACIONES_ANTES, -1f)
		try {
			if (quitar) {
				if (antes < 0) {
					val ahora = android.provider.Settings.Global.getFloat(servicio.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
					p.edit().putFloat(Claves.ANIMACIONES_ANTES, ahora).apply()
					Registro.i(ETIQUETA, "Animaciones quitadas (escala de antes: $ahora)")
				}
				servicio.setAnimationScale(0f)
			} else if (antes >= 0) {
				servicio.setAnimationScale(antes)
				p.edit().remove(Claves.ANIMACIONES_ANTES).apply()
				Registro.i(ETIQUETA, "Animaciones devueltas (escala $antes)")
			}
		} catch (e: Exception) {
			Registro.w(ETIQUETA, "No se pudo cambiar la escala de las animaciones", e)
		}
	}

	companion object {
		private const val ETIQUETA = "AparienciaSistema"
		/** Los del recuadro de fábrica de Android, por si no se pueden leer de sus recursos. */
		private const val ANCHO_SISTEMA_DP = 4f
		private const val COLOR_SISTEMA = 0xBF39B500.toInt()
		private val GROSORES = mapOf("grueso" to 8f, "muy_grueso" to 12f)
		private val COLORES = mapOf(
			"rojo" to 0xFFE53935.toInt(),
			"naranja" to 0xFFFB8C00.toInt(),
			"amarillo" to 0xFFFDD835.toInt(),
			"verde" to 0xFF43A047.toInt(),
			"azul" to 0xFF1E88E5.toInt(),
			"magenta" to 0xFFD81B60.toInt(),
			"blanco" to 0xFFFFFFFF.toInt(),
		)
	}
}
