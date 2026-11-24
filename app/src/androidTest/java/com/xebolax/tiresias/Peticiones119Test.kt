package com.xebolax.tiresias

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La regla única de paradas (1.0.19): lo que antes se saltaba, se juntaba de más o se partía, en la pantalla
 * `ActividadTarjetas` del muestrario.
 */
@RunWith(AndroidJUnit4::class)
class Peticiones119Test : PruebaTiresias() {

	@Test
	fun losControlesDeDentroDeUnaTarjetaSonParadas() {
		abrirMuestrario(".ActividadTarjetas")
		val dichos = recorrer("Muestrario tarjetas")
		val tarjeta = dichos.firstOrNull { it.startsWith("Publicación de Ana") }
		assertTrue("No se dice la tarjeta: $dichos", tarjeta != null && tarjeta.contains("Hola a todos"))
		// «Me gusta» y «Comentar» son paradas propias, y la tarjeta ya no los dice
		assertTrue("«Me gusta» no es parada: $dichos", dichos.any { it.startsWith("Me gusta 12") })
		assertTrue("«Comentar» no es parada: $dichos", dichos.any { it.startsWith("Comentar") })
		assertTrue("La tarjeta dice también sus controles: «$tarjeta»", !tarjeta!!.contains("Me gusta"))
	}

	@Test
	fun unGrupoEnfocableEsUnaSolaParada() {
		abrirMuestrario(".ActividadTarjetas")
		val dichos = recorrer("Muestrario tarjetas")
		assertTrue("«Correo» y su valor no van juntos: $dichos", dichos.any { it.startsWith("Correo") && it.contains("ejemplo.es") })
		assertEquals("El valor sale además suelto: $dichos", 0, dichos.count { it.startsWith("ana") })
	}

	@Test
	fun loMuyHondoNoSePierde() {
		abrirMuestrario(".ActividadTarjetas")
		val dichos = recorrer("Muestrario tarjetas")
		assertTrue("El texto hondo no se dice: $dichos", dichos.any { it.contains("Texto muy hondo") })
		assertTrue("El botón hondo no es parada: $dichos", dichos.any { it.startsWith("Botón muy hondo", ignoreCase = true) })
	}

	@Test
	fun cadaMensajeDeUnChatEsUnaParada() {
		abrirMuestrario(".ActividadTarjetas")
		val dichos = recorrer("Muestrario tarjetas")
		assertTrue("El mensaje no es una parada entera: $dichos", dichos.any { it.startsWith("Remitente 1") && it.contains("Mensaje número 1") && it.contains("10:01") })
		assertEquals("El texto del mensaje sale suelto: $dichos", 0, dichos.count { it.startsWith("Mensaje número 1") })
	}
}
