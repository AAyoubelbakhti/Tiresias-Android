package com.xebolax.tiresias

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xebolax.tiresias.nucleo.Accion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que contaron en el foro tras la 1.0.16 (mensajes 83 y 84): los deslizadores dentro de una fila que no se
 * podían cambiar, la barra de un audio que sonaba sin parar y «Transcribir» dicho dos veces.
 */
@RunWith(AndroidJUnit4::class)
class Peticiones117Test : PruebaTiresias() {

	@Test
	fun elDeslizadorDentroDeUnaFilaEsSuParadaYSeAjusta() {
		abrirMuestrario(".ActividadAlarma")
		// Es su propia parada (antes se lo comía la fila)
		assertNotNull("El deslizador no es parada", irA("Volumen de la alarma"))
		val d = hacer(Accion.VALOR_MAS, 900)
		assertTrue("No se subió el valor: «$d»", d.contains("55") || d.contains("51"))
	}

	@Test
	fun conElFocoEnLaFilaArribaYAbajoAjustanSuDeslizador() {
		abrirMuestrario(".ActividadAlarma")
		assertNotNull(irA("Alarma de las siete"))
		SystemClock.sleep(400)
		val m = marca()
		s.ejecutarPrueba(Accion.VALOR_MENOS)
		assertNotNull("No se dijo el valor nuevo del deslizador de la fila", dichoQue(3000, m) { it.contains("45") || it.contains("49") })
	}

	@Test
	fun laBarraDeUnAudioQueSuenaNoSeDiceSola() {
		abrirMuestrario(".ActividadAlarma")
		assertNotNull(irA("Mensaje de voz"))
		SystemClock.sleep(1500)
		val m = marca()
		SystemClock.sleep(4000)
		val dicho = s.vozPruebas!!.dichoDesde(m)
		assertEquals("La barra que avanza sola habló: $dicho", 0, dicho.size)
	}

	@Test
	fun transcribirSeDiceUnaSolaVez() {
		abrirMuestrario(".ActividadAlarma")
		val dichos = recorrer("Muestrario alarma")
		assertEquals("Transcribir sale más de una vez: $dichos", 1, dichos.count { it.startsWith("Transcribir", ignoreCase = true) })
	}
}
