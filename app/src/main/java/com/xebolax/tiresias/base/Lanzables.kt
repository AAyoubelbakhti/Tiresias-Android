package com.xebolax.tiresias.base

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.xebolax.tiresias.nucleo.escritorio.Buscar

/** Una app que se puede abrir. */
data class Lanzable(val nombre: String, val paquete: String)

/**
 * Las apps del lanzador, por su nombre. Pedir sus nombres a Android es lento, y esta lista
 * hacía falta en seis sitios, así que se guarda unos segundos. Bloquea la primera vez.
 */
object Lanzables {
	private const val ETIQUETA = "Lanzables"
	private const val VALE_MS = 10_000L
	private var lista: List<Lanzable> = emptyList()
	private var cuando = 0L

	@Synchronized
	fun todas(c: Context): List<Lanzable> {
		val ahora = SystemClock.elapsedRealtime()
		if (lista.isNotEmpty() && ahora - cuando < VALE_MS) return lista
		val pm = c.packageManager
		lista = try {
			pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
				.map { Lanzable(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
				.distinctBy { it.paquete }
				.sortedBy { Buscar.normalizar(it.nombre) }
		} catch (e: Exception) {
			Registro.w(ETIQUETA, "No se pudo leer la lista de apps", e)
			emptyList()
		}
		cuando = ahora
		return lista
	}

	/**
	 * La más parecida a [nombre]: la que lo contiene; si ninguna, la que suena casi igual (lo que entiende el dictado de un
	 * nombre extranjero: «guasap» es WhatsApp; Backtalk 2869f39). Null si no hay ninguna clara (dos casi iguales, tampoco).
	 */
	fun buscar(c: Context, nombre: String): Lanzable? {
		val q = Buscar.normalizar(nombre)
		if (q.isEmpty()) return null
		val apps = todas(c)
		apps.maxByOrNull { Buscar.parecido(Buscar.normalizar(it.nombre), q) }?.takeIf { Buscar.normalizar(it.nombre).contains(q) }?.let { return it }
		val sq = sonido(q)
		val puntos = apps.map { it to similitud(sonido(Buscar.normalizar(it.nombre)), sq) }.sortedByDescending { it.second }
		val mejor = puntos.firstOrNull() ?: return null
		if (mejor.second < PARECIDO_MINIMO) return null
		val segundo = puntos.getOrNull(1)?.second ?: 0.0
		return if (mejor.second - segundo >= 0.05) mejor.first else null
	}

	private const val PARECIDO_MINIMO = 0.8

	/** Cómo suena un nombre, más o menos, en español: «WhatsApp» y «guasap» se acercan. */
	fun sonido(t: String): String {
		var s = t.replace(Regex("[^a-z0-9ñ]"), "")
		s = s.replace("wh", "gu").replace("w", "gu").replace("ph", "f").replace("sh", "ch").replace("qu", "k").replace("ll", "y")
			.replace(Regex("c([ei])"), "s$1").replace("c", "k").replace("z", "s").replace("v", "b").replace("h", "").replace("x", "ks")
		return s.replace(Regex("(.)\\1+"), "$1")
	}

	/** De 0 a 1: lo que se parecen dos textos (1 menos la distancia de edición entre el largo del mayor). */
	fun similitud(a: String, b: String): Double {
		if (a.isEmpty() || b.isEmpty()) return 0.0
		val d = IntArray(b.length + 1) { it }
		for (i in 1..a.length) {
			var anterior = d[0]
			d[0] = i
			for (j in 1..b.length) {
				val guardado = d[j]
				d[j] = minOf(d[j] + 1, d[j - 1] + 1, anterior + if (a[i - 1] == b[j - 1]) 0 else 1)
				anterior = guardado
			}
		}
		return 1.0 - d[b.length].toDouble() / maxOf(a.length, b.length)
	}
}
