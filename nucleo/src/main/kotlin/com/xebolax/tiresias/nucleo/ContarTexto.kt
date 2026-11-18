package com.xebolax.tiresias.nucleo

import java.text.BreakIterator
import java.util.Locale

/**
 * Cuenta los caracteres y las palabras de un texto, como se ven (SandFly c80fa068b): un emoji o una letra con
 * tilde compuesta son un carácter, aunque ocupen varias posiciones; una palabra es un trozo con alguna letra o
 * cifra (los signos y los espacios no cuentan).
 */
object ContarTexto {
	data class Cuenta(val caracteres: Int, val palabras: Int)

	fun contar(texto: String, idioma: Locale = Locale.getDefault()): Cuenta = Cuenta(caracteres(texto, idioma), palabras(texto, idioma))

	fun caracteres(texto: String, idioma: Locale = Locale.getDefault()): Int {
		if (texto.isEmpty()) return 0
		val it = BreakIterator.getCharacterInstance(idioma)
		it.setText(texto)
		var n = 0
		while (it.next() != BreakIterator.DONE) n++
		return n
	}

	fun palabras(texto: String, idioma: Locale = Locale.getDefault()): Int {
		if (texto.isBlank()) return 0
		val it = BreakIterator.getWordInstance(idioma)
		it.setText(texto)
		var n = 0
		var inicio = it.first()
		var fin = it.next()
		while (fin != BreakIterator.DONE) {
			if ((inicio until fin).any { texto[it].isLetterOrDigit() }) n++
			inicio = fin
			fin = it.next()
		}
		return n
	}
}
