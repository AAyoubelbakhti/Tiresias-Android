package com.xebolax.tiresias.traduccion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El idioma de un texto para elegir el traductor. */
class TraduccionDetectorIdiomaTest {
	@Test
	fun reconoceLosSeisIdiomas() {
		assertEquals("es", DetectorIdioma.detectar("El perro come en la casa de su abuela cuando hace frío"))
		assertEquals("en", DetectorIdioma.detectar("The quick brown fox jumps over the lazy dog and the cat is sleeping"))
		assertEquals("fr", DetectorIdioma.detectar("Le chat est sur la table avec les enfants et nous sommes très contents"))
		assertEquals("de", DetectorIdioma.detectar("Der Hund ist in dem Haus und die Katze schläft auf dem Sofa"))
		assertEquals("it", DetectorIdioma.detectar("Il gatto è sulla tavola con i bambini e non vuole scendere"))
		assertEquals("pt", DetectorIdioma.detectar("O gato está na mesa com as crianças e não quer descer"))
	}

	@Test
	fun lasLetrasPropiasAyudanConTextosCortos() {
		assertEquals("es", DetectorIdioma.detectar("¿Mañana?"))
		assertEquals("de", DetectorIdioma.detectar("Straße"))
		assertEquals("en", DetectorIdioma.detectar("Don’t forget to check your messages"))
		assertEquals("fr", DetectorIdioma.detectar("C’est la vie, n’est-ce pas"))
	}

	@Test
	fun sinPistasNoDecide() {
		assertNull(DetectorIdioma.detectar(""))
		assertNull(DetectorIdioma.detectar("12345 678"))
		assertNull(DetectorIdioma.detectar("Netflix"))
	}

	@Test
	fun elMasProbableEntreLosQueHay() {
		// «la» es de español, francés e italiano: entre español e inglés, español
		assertEquals("es", DetectorIdioma.masProbable("la Tierra", listOf("en", "es")))
		assertNull(DetectorIdioma.masProbable("Netflix", listOf("en", "es")))
		assertEquals("en", DetectorIdioma.masProbable("the", listOf("en", "es")))
	}
}
