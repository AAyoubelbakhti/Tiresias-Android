package com.xebolax.tiresias.servicio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xebolax.tiresias.R
import com.xebolax.tiresias.base.Ajustes
import com.xebolax.tiresias.base.Registro

/**
 * Dice el volumen nuevo al cambiarlo con las teclas («Volumen multimedia, 40
 * por ciento»), como TalkBack y SandFly (VolumeMonitor), pero sin sus dos
 * segundos de espera: se dice un momento después de la última pulsación.
 *
 * Android avisa con la difusión `VOLUME_CHANGED_ACTION` (oculta, pero se
 * entrega a quien la registra). Lo que cambia Tiresias (el volumen de la voz,
 * que ya dice él) no se repite: ver [propio].
 */
class AvisoVolumen(private val c: Context, private val ajustes: Ajustes, private val decir: (nombre: Int, porCiento: Int) -> Unit) {
	private val principal = Handler(Looper.getMainLooper())
	private val audio = c.getSystemService(AudioManager::class.java)
	private var pendiente: Runnable? = null
	private var registrado = false
	/** El volumen de accesibilidad que había al apagarse la pantalla (-1: aún no se ha tocado desde entonces). */
	private var baseApagada = -1

	private val receptor = object : BroadcastReceiver() {
		override fun onReceive(contexto: Context?, intent: Intent?) {
			intent ?: return
			val flujo = intent.getIntExtra(EXTRA_FLUJO, -1)
			val valor = intent.getIntExtra(EXTRA_VALOR, -1)
			val antes = intent.getIntExtra(EXTRA_ANTES, -1)
			if (flujo < 0 || valor < 0 || valor == antes) return
			// Al empezar a sonar una llamada, Android mueve el volumen de los tonos del teclado de marcar (un alias de otro):
			// eso no lo ha hecho el usuario y se decía «volumen otro» (SandFly c7ab0985b)
			if (flujo == STREAM_DTMF && intent.getIntExtra(EXTRA_ALIAS, STREAM_DTMF) != STREAM_DTMF) return
			// Con la pantalla encendida, cualquier cambio (también los de Tiresias) deja sin valor lo apuntado al apagarla
			if (!apagada()) baseApagada = -1
			if (SystemClock.uptimeMillis() < propioHasta) return
			if (alMultimediaSiApagada(flujo, valor, antes)) return
			if (!ajustes.decirVolumen) return
			pendiente?.let { principal.removeCallbacks(it) }
			val r = Runnable {
				pendiente = null
				val maximo = runCatching { audio?.getStreamMaxVolume(flujo) }.getOrNull()?.coerceAtLeast(1) ?: return@Runnable
				val actual = runCatching { audio?.getStreamVolume(flujo) }.getOrNull() ?: valor
				decir(nombre(flujo), actual * 100 / maximo)
			}
			pendiente = r
			principal.postDelayed(r, ESPERA_MS)
		}
	}

	/**
	 * Con la pantalla apagada, las teclas de volumen son para la música: si Android ha cambiado el volumen de
	 * accesibilidad (lo hace cuando la voz va por él y ha hablado hace poco: «pantalla apagada»), se deja como estaba
	 * y el cambio se pasa al multimedia (Woss y Dharius: apagada, se movía el de la voz en vez del de la música).
	 * No en una llamada (ahí Android cambia el de la llamada) ni si no hay volumen de accesibilidad aparte.
	 */
	private fun alMultimediaSiApagada(flujo: Int, valor: Int, antes: Int): Boolean {
		if (Build.VERSION.SDK_INT < 26 || flujo != AudioManager.STREAM_ACCESSIBILITY || antes < 0) return false
		if (!ajustes.volumenApagadaMultimedia || com.xebolax.tiresias.voz.PistaAudio.multimedia) return false
		val a = audio ?: return false
		if (!apagada() || a.mode != AudioManager.MODE_NORMAL) return false
		return try {
			// (como estaba al apagar: con pulsaciones seguidas, el aviso de la segunda puede llegar antes de devolverlo)
			if (baseApagada < 0) baseApagada = antes
			// El aviso de haberlo devuelto Tiresias no es un cambio del usuario
			if (valor == baseApagada) return true
			a.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, baseApagada, 0)
			a.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (valor > antes) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
			// Con la tecla mantenida, Android junta sus avisos y alguno no llega: al acabar, el de la voz vuelve a su sitio
			principal.removeCallbacks(asegurarBase)
			principal.postDelayed(asegurarBase, ASEGURAR_MS)
			true
		} catch (e: Exception) {
			Registro.w(ETIQUETA, "No se pudo pasar el cambio de volumen al multimedia", e)
			false
		}
	}

	private val asegurarBase = Runnable {
		val base = baseApagada
		val a = audio
		if (base >= 0 && a != null && Build.VERSION.SDK_INT >= 26 && apagada()) {
			runCatching { if (a.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY) != base) a.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, base, 0) }
				.onFailure { Registro.w(ETIQUETA, "No se pudo devolver el volumen de la voz", it) }
		}
	}

	private fun apagada(): Boolean = runCatching { c.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false }.getOrDefault(false)

	fun iniciar() {
		if (registrado) return
		try {
			val filtro = IntentFilter(ACCION)
			if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(receptor, filtro, Context.RECEIVER_NOT_EXPORTED)
			else @Suppress("UnspecifiedRegisterReceiverFlag") c.registerReceiver(receptor, filtro)
			registrado = true
		} catch (e: Exception) {
			Registro.w(ETIQUETA, "No se pudo escuchar los cambios de volumen", e)
		}
	}

	fun cerrar() {
		pendiente?.let { principal.removeCallbacks(it) }
		principal.removeCallbacks(asegurarBase)
		if (!registrado) return
		runCatching { c.unregisterReceiver(receptor) }.onFailure { Registro.w(ETIQUETA, "No se pudo dejar de escuchar el volumen", it) }
		registrado = false
	}

	/** La cadena del nombre del volumen [flujo] (la dice el servicio en el idioma de Tiresias). */
	private fun nombre(flujo: Int): Int =
		when (flujo) {
			AudioManager.STREAM_MUSIC -> R.string.volumen_multimedia
			AudioManager.STREAM_RING -> R.string.volumen_tono
			AudioManager.STREAM_NOTIFICATION -> R.string.volumen_notificaciones
			AudioManager.STREAM_ALARM -> R.string.volumen_alarma
			AudioManager.STREAM_VOICE_CALL -> R.string.volumen_llamada
			AudioManager.STREAM_SYSTEM -> R.string.volumen_sistema
			else -> if (Build.VERSION.SDK_INT >= 26 && flujo == AudioManager.STREAM_ACCESSIBILITY) R.string.volumen_accesibilidad else R.string.volumen_otro
		}

	companion object {
		private const val ETIQUETA = "AvisoVolumen"
		private const val ACCION = "android.media.VOLUME_CHANGED_ACTION"
		private const val EXTRA_FLUJO = "android.media.EXTRA_VOLUME_STREAM_TYPE"
		private const val EXTRA_VALOR = "android.media.EXTRA_VOLUME_STREAM_VALUE"
		private const val EXTRA_ANTES = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
		private const val EXTRA_ALIAS = "android.media.EXTRA_VOLUME_STREAM_TYPE_ALIAS"
		/** AudioManager.STREAM_DTMF (los tonos del teclado de marcar). */
		private const val STREAM_DTMF = 8
		/** Lo que se espera tras la última pulsación para decir el volumen (TalkBack espera 2 s: demasiado). */
		private const val ESPERA_MS = 350L
		/** Lo que se espera tras el último cambio con la pantalla apagada para comprobar que el volumen de la voz sigue en su sitio. */
		private const val ASEGURAR_MS = 400L

		/** Hasta cuándo un cambio de volumen es de Tiresias (que ya lo dice): no se repite. */
		@Volatile var propioHasta = 0L

		/** Tiresias va a cambiar un volumen y lo dirá él. */
		fun propio() {
			propioHasta = SystemClock.uptimeMillis() + 1000
		}
	}
}
