package com.xebolax.tiresias.traduccion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** El tokenizador Unigram con un vocabulario pequeño inventado (sin el modelo real). */
class TraduccionTokenizadorTest {
	// Como los de Marian: </s> 0, <unk> 1 y el unk_id del modelo apuntando a la coma (2), que no se usa
	private val json = """
		{
		  "version": "1.0",
		  "truncation": null,
		  "added_tokens": [
		    {"id": 0, "special": true, "content": "</s>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": false},
		    {"id": 1, "special": true, "content": "<unk>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": false},
		    {"id": 14, "special": true, "content": "<pad>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": false}
		  ],
		  "normalizer": {"type": "Precompiled", "precompiled_charsmap": null},
		  "pre_tokenizer": {"type": "Sequence", "pretokenizers": [{"type": "WhitespaceSplit"}, {"type": "Metaspace", "replacement": "▁", "add_prefix_space": true}]},
		  "model": {
		    "type": "Unigram",
		    "unk_id": 2,
		    "vocab": [
		      ["</s>", 0.0], ["<unk>", 0.0], [",", -3.0],
		      ["▁hola", -2.0], ["▁ho", -3.0], ["la", -3.0], ["▁", -4.0],
		      ["h", -5.0], ["o", -5.0], ["l", -5.0], ["a", -5.0],
		      ["▁mundo", -2.5], ["▁hol", -1.0], ["▁café", -1.5e0],
		      ["<pad>", 0.0], ["\"", -6.0]
		    ]
		  }
		}
	""".trimIndent()

	private val tok = TokenizadorUnigram.desdeJson(json)

	@Test
	fun leeElVocabularioYLosEspeciales() {
		assertEquals(16, tok.tamanoVocabulario)
		// El desconocido es <unk>, no el unk_id del modelo
		assertEquals(1, tok.idDesconocido)
		assertEquals(0, tok.idFin)
	}

	@Test
	fun eligeLasPiezasQueMasPuntuanJuntas() {
		// ▁hola (-2) gana a ▁ho + la (-6) y a ▁hol + a (-6)
		assertArrayEquals(intArrayOf(3, 11, 0), tok.codificar("hola mundo"))
		assertArrayEquals(intArrayOf(3, 2, 11, 0), tok.codificar("hola, mundo"))
		assertArrayEquals(intArrayOf(12, 9, 0), tok.codificar("holl"))
	}

	@Test
	fun losEspaciosDeCualquierTipoSeparanPalabras() {
		assertArrayEquals(intArrayOf(3, 11, 0), tok.codificar("  hola mundo\n\t"))
		assertArrayEquals(intArrayOf(0), tok.codificar("   "))
	}

	@Test
	fun losDesconocidosSeguidosSonUnoSolo() {
		assertArrayEquals(intArrayOf(3, 6, 1, 0), tok.codificar("hola ñññ"))
		// Un emoji (dos unidades UTF-16) es un carácter desconocido, no dos
		assertArrayEquals(intArrayOf(6, 1, 0), tok.codificar("😀"))
		// Los símbolos especiales no salen del texto
		assertArrayEquals(intArrayOf(6, 1, 0), tok.codificar("</s>"))
	}

	@Test
	fun leeLosEscapesDelJson() {
		assertArrayEquals(intArrayOf(13, 0), tok.codificar("café"))
		// NFKC: la e con tilde combinada es la misma pieza
		assertArrayEquals(intArrayOf(13, 0), tok.codificar("café"))
		assertArrayEquals(intArrayOf(3, 15, 0), tok.codificar("hola\""))
	}

	@Test
	fun decodificaSinLosEspeciales() {
		assertEquals("hola, mundo", tok.decodificar(intArrayOf(3, 2, 11, 0, 14)))
		assertEquals("café hola", tok.decodificar(intArrayOf(14, 13, 1, 3, 0)))
	}

	@Test(expected = IllegalArgumentException::class)
	fun rechazaOtrosModelos() {
		TokenizadorUnigram.desdeJson("""{"model": {"type": "BPE", "vocab": {}, "merges": []}}""")
	}

	@Test
	fun partePorFrases() {
		assertEquals(listOf("Hola.", "¿Qué tal?", "Bien…", "(sí).", "Adiós"), TraductorOpus.frases("Hola. ¿Qué tal? Bien… (sí). Adiós"))
		assertEquals(listOf("Cuesta 3.5 euros."), TraductorOpus.frases("Cuesta 3.5 euros."))
		assertEquals(listOf("Dijo «hola.»", "Luego se fue"), TraductorOpus.frases("  Dijo «hola.» Luego se fue  "))
		assertEquals(emptyList<String>(), TraductorOpus.frases("   "))
	}
}
