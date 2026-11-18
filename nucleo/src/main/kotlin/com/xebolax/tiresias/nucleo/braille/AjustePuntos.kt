package com.xebolax.tiresias.nucleo.braille

import kotlin.math.hypot

/**
 * Mueve poco a poco las posiciones calibradas de los seis puntos del teclado braille hacia donde caen de verdad los
 * dedos al escribir (SandFly 1769e1880): la mano se desplaza al escribir y, con la calibración fija, acaban saliendo
 * puntos de más o de menos.
 *
 * Con cada celda escrita, los tres puntos de cada mano (1, 2 y 3; 4, 5 y 6) se mueven [PESO_MANO] de lo que se han
 * desviado de media los dedos de esa mano, y cada punto tocado otro [PESO_PUNTO] hacia su dedo. Un toque solo cuenta
 * si cae a menos de [CERCA] de la distancia a su punto vecino más cercano (si no, no se sabe de qué punto era).
 * El cambio se descarta si saca un punto de la superficie, si deja dos puntos más cerca que [DISTANCIA_MINIMA] del
 * lado corto o si un punto deja de ser el más cercano a su propio sitio (se cambiaría el número de algún punto).
 *
 * Las posiciones van en proporción del ancho y del alto (de 0 a 1); las distancias se miden en píxeles.
 */
object AjustePuntos {
	data class Punto(val x: Float, val y: Float)

	/** Un dedo de la celda escrita: el punto (0 a 5) al que se asignó y dónde se apoyó (en proporción). */
	data class Toque(val punto: Int, val x: Float, val y: Float)

	const val PESO_MANO = 0.2f
	const val PESO_PUNTO = 0.2f
	const val CERCA = 0.6f
	const val DISTANCIA_MINIMA = 0.08f

	/** Las posiciones nuevas tras escribir una celda con [toques], o null si no hay que cambiar nada. */
	fun ajustar(centros: List<Punto>, toques: List<Toque>, ancho: Float, alto: Float): List<Punto>? {
		if (centros.size != 6 || toques.isEmpty() || ancho <= 0f || alto <= 0f) return null
		fun distancia(a: Punto, bx: Float, by: Float) = hypot((a.x - bx) * ancho, (a.y - by) * alto)
		// Solo los toques que de verdad son de su punto
		val validos = toques.filter { t ->
			if (t.punto !in 0..5) return@filter false
			val c = centros[t.punto]
			val vecino = centros.indices.filter { it != t.punto }.minOf { distancia(centros[it], c.x, c.y) }
			distancia(c, t.x, t.y) < vecino * CERCA
		}
		if (validos.isEmpty()) return null
		val nuevos = centros.toMutableList()
		for (mano in listOf(0..2, 3..5)) {
			val deMano = validos.filter { it.punto in mano }
			if (deMano.isEmpty()) continue
			val mx = deMano.map { it.x - centros[it.punto].x }.average().toFloat() * PESO_MANO
			val my = deMano.map { it.y - centros[it.punto].y }.average().toFloat() * PESO_MANO
			for (i in mano) nuevos[i] = Punto(nuevos[i].x + mx, nuevos[i].y + my)
			for (t in deMano) {
				val c = nuevos[t.punto]
				nuevos[t.punto] = Punto(c.x + (t.x - c.x) * PESO_PUNTO, c.y + (t.y - c.y) * PESO_PUNTO)
			}
		}
		// Fuera de la superficie
		if (nuevos.any { it.x !in 0f..1f || it.y !in 0f..1f }) return null
		// Dos puntos demasiado juntos
		val minimo = minOf(ancho, alto) * DISTANCIA_MINIMA
		for (i in nuevos.indices) for (j in i + 1 until nuevos.size) if (distancia(nuevos[i], nuevos[j].x, nuevos[j].y) < minimo) return null
		// Cada punto sigue siendo el más cercano a donde estaba (no se cambia el número de ninguno)
		for (i in centros.indices) {
			val masCercano = nuevos.indices.minByOrNull { distancia(nuevos[it], centros[i].x, centros[i].y) }
			if (masCercano != i) return null
		}
		return nuevos
	}
}
