// Basado en ShakeDetector de SandFly (Copyright 2026 SandFly contributors, Apache License 2.0), con cambios.
package com.xebolax.tiresias.gestos

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.xebolax.tiresias.base.Registro
import kotlin.math.sqrt

/**
 * El gesto de agitar el móvil (SandFly 073bb90b4): cuatro sacudidas de ida y vuelta en 650 ms. Solo escucha el
 * acelerómetro mientras hay una acción puesta en el gesto y la pantalla está encendida (gasta batería).
 *
 * El detector separa la gravedad (filtro lento) del movimiento (filtro rápido, que quita los golpecitos), cuenta cada
 * sacudida por su punto más fuerte y su dirección, y da el gesto cuando hay cuatro, con cambios de sentido, seguidas.
 */
class Agitar(private val contexto: Context, private val alAgitar: () -> Unit) : SensorEventListener {
	private val sensores = contexto.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
	private val manejador = Handler(Looper.getMainLooper())
	@Volatile private var escuchando = false

	/** Lo fuerte que tiene que ser cada sacudida (m/s² además de la gravedad): baja 20, media 14, alta 10. */
	var umbral = SENSIBILIDAD_MEDIA

	/** Empieza o deja de escuchar el acelerómetro. */
	fun escuchar(si: Boolean) {
		if (si == escuchando) return
		val s = sensores ?: return
		try {
			if (si) {
				val acel = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
				escuchando = s.registerListener(this, acel, SensorManager.SENSOR_DELAY_GAME)
				empezado = false
			} else {
				s.unregisterListener(this)
				escuchando = false
			}
		} catch (e: RuntimeException) {
			Registro.w(ETIQUETA, "No se pudo cambiar la escucha del acelerómetro", e)
		}
	}

	override fun onAccuracyChanged(sensor: Sensor?, precision: Int) {}

	override fun onSensorChanged(e: SensorEvent) {
		if (e.values.size < 3) return
		muestra(e.timestamp, e.values[0], e.values[1], e.values[2])
	}

	private var empezado = false
	private var ultimoNs = 0L
	private var ignorarHastaNs = 0L
	private var gx = 0f
	private var gy = 0f
	private var gz = 0f
	private var mx = 0f
	private var my = 0f
	private var mz = 0f
	private var enSacudida = false
	private var pico = 0f
	private var picoNs = 0L
	private var dx = 0f
	private var dy = 0f
	private var dz = 0f
	private var ultimaNs = 0L
	private var ux = 0f
	private var uy = 0f
	private var uz = 0f
	private val sacudidas = ArrayDeque<Long>()

	/** Una lectura del acelerómetro: [ns] y la aceleración en m/s². */
	fun muestra(ns: Long, x: Float, y: Float, z: Float) {
		if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
		if (!empezado || ns <= ultimoNs || ns - ultimoNs > HUECO_MAXIMO_NS) {
			empezar(ns, x, y, z)
			return
		}
		val dt = (ns - ultimoNs) / 1e9f
		ultimoNs = ns
		val ag = dt / (TAU_GRAVEDAD_S + dt)
		gx += (x - gx) * ag
		gy += (y - gy) * ag
		gz += (z - gz) * ag
		val am = dt / (TAU_MOVIMIENTO_S + dt)
		mx += (x - gx - mx) * am
		my += (y - gy - my) * am
		mz += (z - gz - mz) * am
		val fuerza = sqrt(mx * mx + my * my + mz * mz)
		if (ns < ignorarHastaNs) {
			// Asentándose, o justo después de un gesto: mientras se siga moviendo, no cuenta
			if (fuerza >= umbral) ignorarHastaNs = maxOf(ignorarHastaNs, ns + QUIETO_NS)
			enSacudida = false
			sacudidas.clear()
			return
		}
		if (!enSacudida) {
			if (fuerza >= umbral) {
				enSacudida = true
				ponerPico(ns, fuerza)
			}
			return
		}
		val alLado = mx * dx + my * dy + mz * dz
		if (fuerza > pico && alLado > 0f) ponerPico(ns, fuerza)
		else if (fuerza < umbral * FIN_SACUDIDA || alLado <= 0f) {
			enSacudida = false
			alAcabarSacudida()
			if (fuerza >= umbral && alLado <= 0f) {
				// Ha dado la vuelta sin aflojar: la siguiente sacudida empieza ya
				enSacudida = true
				ponerPico(ns, fuerza)
			}
		}
	}

	private fun ponerPico(ns: Long, fuerza: Float) {
		pico = fuerza
		picoNs = ns
		dx = mx / fuerza
		dy = my / fuerza
		dz = mz / fuerza
	}

	private fun alAcabarSacudida() {
		val p = picoNs
		if (sacudidas.isEmpty() || p - ultimaNs > ENTRE_SACUDIDAS_MAX_NS) {
			empezarCadena(p)
			return
		}
		val hueco = p - ultimaNs
		val coseno = dx * ux + dy * uy + dz * uz
		when {
			// Demasiado pronto para ser la vuelta: un rebote o un golpe
			hueco < ENTRE_SACUDIDAS_MIN_NS -> return
			coseno <= -COSENO_VUELTA -> {
				sacudidas.addLast(p)
				ponerUltima(p)
			}
			// Otra hacia el mismo lado (una sacudida a tirones): ni cuenta ni corta
			coseno >= COSENO_VUELTA -> ponerUltima(p)
			else -> {
				empezarCadena(p)
				return
			}
		}
		while (sacudidas.size > 1 && p - sacudidas.first() > GESTO_NS) sacudidas.removeFirst()
		if (sacudidas.size >= SACUDIDAS) {
			sacudidas.clear()
			ignorarHastaNs = p + ESPERA_TRAS_GESTO_NS
			manejador.post(alAgitar)
		}
	}

	private fun empezarCadena(ns: Long) {
		sacudidas.clear()
		sacudidas.addLast(ns)
		ponerUltima(ns)
	}

	private fun ponerUltima(ns: Long) {
		ultimaNs = ns
		ux = dx
		uy = dy
		uz = dz
	}

	private fun empezar(ns: Long, x: Float, y: Float, z: Float) {
		empezado = true
		ultimoNs = ns
		gx = x
		gy = y
		gz = z
		mx = 0f
		my = 0f
		mz = 0f
		enSacudida = false
		sacudidas.clear()
		ignorarHastaNs = maxOf(ignorarHastaNs, ns + ASENTARSE_NS)
	}

	companion object {
		private const val ETIQUETA = "Agitar"
		private const val MS = 1_000_000L
		const val SENSIBILIDAD_BAJA = 20f
		const val SENSIBILIDAD_MEDIA = 14f
		const val SENSIBILIDAD_ALTA = 10f
		/** Sacudidas de un gesto: tres cambios de sentido. */
		private const val SACUDIDAS = 4
		private const val TAU_GRAVEDAD_S = 0.4f
		private const val TAU_MOVIMIENTO_S = 0.02f
		private const val HUECO_MAXIMO_NS = 200 * MS
		private const val ASENTARSE_NS = 300 * MS
		private const val FIN_SACUDIDA = 0.5f
		private const val COSENO_VUELTA = 0.5f
		private const val ENTRE_SACUDIDAS_MIN_NS = 60 * MS
		private const val ENTRE_SACUDIDAS_MAX_NS = 300 * MS
		private const val GESTO_NS = 650 * MS
		private const val ESPERA_TRAS_GESTO_NS = 1_000 * MS
		private const val QUIETO_NS = 400 * MS
	}
}
