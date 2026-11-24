package com.xebolax.tiresias.nucleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La puntuación «ninguna»: los signos que no se dicen van como un espacio, salvo los que hacen falta (SandFly 98c9e0532 y cc1bbf045). */
class PuntuacionNingunaTest {
	private val tabla = TablaSimbolos.leer(
		"""
		# prueba
		.	punto	mayoria
		,	coma	mayoria
		?	cierra interrogación	mayoria	mantener
		¿	abre interrogación	mayoria
		@	arroba	algunas
		#	almohadilla	algunas
		*	asterisco	algunas
		%	por ciento	algunas
		€	euro	algunas
		=	igual	todas
		-	guion	mayoria
		+	más	algunas
		...	puntos suspensivos	algunas
		⇒	flecha doble a la derecha	ninguna
		""".trimIndent()
	)
	private val n = Normalizador(tabla, TextosPrueba)
	private val ninguna = AjustesTexto(puntuacion = NivelPuntuacion.NINGUNA)
	private val conSentido = ninguna.copy(simbolosSentido = true)

	@Test
	fun losSignosQueNoSeDicenNoLleganAlMotor() {
		assertEquals("correo sitio es", n.normalizar("correo@sitio*es", ninguna))
		assertEquals("Etiqueta etiqueta", n.normalizar("#Etiqueta #etiqueta", ninguna))
		assertEquals("Qué tal?", n.normalizar("¿Qué tal?", ninguna))
	}

	@Test
	fun lasUnionesDePalabraYNumeroSeQuedan() {
		assertEquals("e-mail", n.normalizar("e-mail", ninguna))
		assertEquals("COVID-19", n.normalizar("COVID-19", ninguna))
		assertEquals("l'aigua", n.normalizar("l'aigua", ninguna))
		// Un guion suelto entre palabras, no
		assertEquals("uno dos", n.normalizar("uno - dos", ninguna))
	}

	@Test
	fun losSignosEntreCifrasSeQuedan() {
		assertEquals("3,50 a las 10:30 el 1/2", n.normalizar("3,50 a las 10:30 el 1/2", ninguna))
		assertEquals("www google com", n.normalizar("www.google.com", ninguna))
	}

	@Test
	fun laPuntuacionFinalSeQuedaPorLaEntonacion() {
		assertEquals("Hola, vienes? Sí.", n.normalizar("Hola, ¿vienes? Sí.", ninguna))
		assertEquals("Espera...", n.normalizar("Espera...", ninguna))
		assertEquals("dijo sí.", n.normalizar("dijo «sí».", ninguna))
	}

	@Test
	fun losEmojisSeQuedan() {
		assertEquals("Hola 😀", n.normalizar("Hola 😀", ninguna.copy(emojis = ModoEmojis.MOTOR)))
		assertFalse(TablaSimbolos.callado("#️⃣", 0))
	}

	@Test
	fun losDeNivelNingunaSeDicenSiempre() {
		assertEquals("a flecha doble a la derecha b", n.normalizar("a ⇒ b", ninguna))
	}

	@Test
	fun unCaracterSueltoNoSeCalla() {
		assertEquals("@", n.normalizar("@", ninguna))
		// Un texto que solo son signos no se queda en nada
		assertTrue(n.normalizar("***", ninguna).isNotBlank())
	}

	@Test
	fun losSimbolosQueCambianElSentido() {
		// Sin el ajuste, se callan
		assertEquals("Hace 5 grados, sube un 20", n.normalizar("Hace -5 grados, sube un 20 %", ninguna))
		// Con el ajuste, se quedan para el motor
		assertEquals("Hace -5 grados, sube un 20 %", n.normalizar("Hace -5 grados, sube un 20 %", conSentido))
		assertEquals("Cuesta 3 €", n.normalizar("Cuesta 3 €", conSentido))
		assertEquals("2 + 2 = 4", n.normalizar("2 + 2 = 4", conSentido))
		assertEquals("+34 600", n.normalizar("+34 600", conSentido))
		// Un igual que no va entre cifras se sigue callando
		assertEquals("a b", n.normalizar("a = b", conSentido))
	}

	@Test
	fun losOtrosNivelesNoCambian() {
		assertEquals("correo arroba sitio.es", n.normalizar("correo@sitio.es", AjustesTexto(puntuacion = NivelPuntuacion.ALGUNAS)))
		assertEquals("correo asterisco sitio", n.normalizar("correo*sitio", AjustesTexto(puntuacion = NivelPuntuacion.ALGUNAS)))
	}
}
