package com.xebolax.tiresias.nucleo.braille

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Las posiciones calibradas de los puntos se mueven poco a poco hacia donde caen los dedos. */
class AjustePuntosTest {
	private val ancho = 2000f
	private val alto = 1000f

	/** Mano izquierda (1, 2, 3) a la izquierda, de arriba abajo; la derecha (4, 5, 6) a la derecha. */
	private val centros = listOf(
		AjustePuntos.Punto(0.2f, 0.2f), AjustePuntos.Punto(0.2f, 0.5f), AjustePuntos.Punto(0.2f, 0.8f),
		AjustePuntos.Punto(0.8f, 0.2f), AjustePuntos.Punto(0.8f, 0.5f), AjustePuntos.Punto(0.8f, 0.8f),
	)

	@Test
	fun laManoSeMueveHaciaLosDedos() {
		// Punto 1 tocado un poco a la derecha: toda la mano izquierda se mueve un 0,2 y el 1 otro poco
		val n = AjustePuntos.ajustar(centros, listOf(AjustePuntos.Toque(0, 0.25f, 0.2f)), ancho, alto)
		assertNotNull(n)
		n!!
		assertEquals(0.21f, n[1].x, 0.0001f)
		assertEquals(0.21f, n[2].x, 0.0001f)
		assertEquals(0.218f, n[0].x, 0.0001f)
		// La otra mano no se toca
		assertEquals(0.8f, n[3].x, 0.0001f)
	}

	@Test
	fun unToqueLejosNoCuenta() {
		// Entre el punto 1 y el 2: no se sabe de cuál es
		assertNull(AjustePuntos.ajustar(centros, listOf(AjustePuntos.Toque(0, 0.2f, 0.4f)), ancho, alto))
	}

	@Test
	fun sinCalibracionCompletaNada() {
		assertNull(AjustePuntos.ajustar(centros.take(5), listOf(AjustePuntos.Toque(0, 0.2f, 0.2f)), ancho, alto))
		assertNull(AjustePuntos.ajustar(centros, emptyList(), ancho, alto))
	}
}
