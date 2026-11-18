package com.xebolax.tiresias.nucleo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Contar los caracteres y las palabras de un campo como se ven. */
class ContarTextoTest {
	private val es = Locale("es", "ES")

	@Test
	fun palabrasSinSignosNiEspacios() {
		assertEquals(4, ContarTexto.palabras("Hola, ¿qué tal estás?", es))
		assertEquals(0, ContarTexto.palabras("  ... ¡! ", es))
		assertEquals(2, ContarTexto.palabras("año 2026", es))
		assertEquals(0, ContarTexto.palabras("", es))
	}

	@Test
	fun unEmojiEsUnCaracter() {
		assertEquals(4, ContarTexto.caracteres("Hola", es))
		// Un corazón con selector de variante (dos posiciones): un carácter; un pulgar (par sustituto) entre dos letras: tres
		assertEquals(1, ContarTexto.caracteres("❤️", es))
		assertEquals(3, ContarTexto.caracteres("a👍b", es))
		assertEquals(0, ContarTexto.caracteres("", es))
	}

	@Test
	fun laCuentaEntera() {
		assertEquals(ContarTexto.Cuenta(9, 2), ContarTexto.contar("Hola mund", es))
	}
}
