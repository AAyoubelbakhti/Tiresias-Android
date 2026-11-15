package com.xebolax.tiresias.nucleo.braille

import com.xebolax.tiresias.nucleo.Accion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El protocolo de las líneas Seika, con los paquetes que describe el controlador de BRLTTY. */
class SeikaTest {
	private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

	@Test
	fun pedirIdentidad() {
		assertArrayEquals(b(0xFF, 0xFF, 0xA1), ProtocoloSeika.ANOTADOR.pedirIdentidad())
		assertArrayEquals(b(0xFF, 0xFF, 0x1C), ProtocoloSeika.LINEA.pedirIdentidad())
		assertArrayEquals(b(0xFF, 0xFF, 0x0A), ProtocoloSeika.POWERBRAILLE.pedirIdentidad())
	}

	@Test
	fun lineaDe40() {
		val l = LectorSeika(ProtocoloSeika.LINEA)
		// «seika3 v2.10», llegando a trozos
		val id = "seika3 v2.10".toByteArray(Charsets.US_ASCII)
		assertTrue(l.recibir(id.copyOfRange(0, 5)).isEmpty())
		val p = l.recibir(id.copyOfRange(5, id.size))
		assertEquals(listOf(PaqueteSeika.Identidad(40, 16, 40)), p)
		assertEquals(40, l.celdas)
		// Las celdas: cabecera «seika» y cada celda detrás de un cero
		val celdas = ProtocoloSeika.LINEA.celdas(40, b(0x01, 0x3F))
		assertEquals(8 + 80, celdas.size)
		assertArrayEquals(b(0xFF, 0xFF, 0x73, 0x65, 0x69, 0x6B, 0x61, 0x00, 0x00, 0x01, 0x00, 0x3F, 0x00, 0x00), celdas.copyOfRange(0, 14))
	}

	@Test
	fun lineaDe80() {
		val l = LectorSeika(ProtocoloSeika.LINEA)
		assertEquals(listOf(PaqueteSeika.Identidad(80, 16, 80)), l.recibir("seika80v1.02".toByteArray(Charsets.US_ASCII)))
		val celdas = ProtocoloSeika.LINEA.celdas(80, b(0xFF))
		assertEquals(8 + 80, celdas.size)
		assertArrayEquals(b(0xFF, 0xFF, 0x73, 0x38, 0x30, 0x00, 0x00, 0x00, 0xFF, 0x00), celdas.copyOfRange(0, 10))
		// Encaminamiento: la celda 9 (segundo byte, bit 1)
		val enc = b(0x00, 0x08, 0x0F, 0, 0, 0, 0, 0x00, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
		val p = l.recibir(enc).single() as PaqueteSeika.Teclas
		assertEquals(setOf(9), p.encaminamiento)
		assertEquals(setOf(TeclaLinea(UsosBraille.ENCAMINAMIENTO, 9)), TeclasSeika.teclas(ProtocoloSeika.LINEA, p))
	}

	@Test
	fun teclasDeLaLinea() {
		val l = LectorSeika(ProtocoloSeika.LINEA)
		l.recibir("seika5 v1.00".toByteArray(Charsets.US_ASCII))
		// Basura delante: se tira hasta encontrar el paquete. K1 (bit 0) y K4 (bit 12 = segundo byte, bit 4)
		val p = l.recibir(b(0x12, 0x61, 0xF0))
		val t = p.single() as PaqueteSeika.Teclas
		assertEquals(1L or (1L shl 12), t.teclas)
		val teclas = TeclasSeika.teclas(ProtocoloSeika.LINEA, t)
		assertEquals(setOf(TeclaLinea(UsosSeika.K1), TeclaLinea(UsosSeika.K4)), teclas)
		// Una «s» (0x73: bits 0, 1 y 4) que empieza como la identidad pero son teclas
		val k = l.recibir(b(0x73, 0xE0)).single() as PaqueteSeika.Teclas
		assertEquals(setOf(TeclaLinea(UsosSeika.K1), TeclaLinea(UsosSeika.K7), TeclaLinea(UsosSeika.K5)), TeclasSeika.teclas(ProtocoloSeika.LINEA, k))
	}

	@Test
	fun powerBraille() {
		val l = LectorSeika(ProtocoloSeika.POWERBRAILLE)
		assertEquals(listOf(PaqueteSeika.Identidad(40, 16, 40)), l.recibir(b(0x00, 0x05, 40, 0x08, 1, 2, 3, 4, 5, 6, 7, 8)))
		val celdas = ProtocoloSeika.POWERBRAILLE.celdas(40, b(0x07))
		assertArrayEquals(b(0xFF, 0xFF, 0x04, 0x00, 0x63, 0x00, 80, 0x00, 0x00, 0x07), celdas.copyOfRange(0, 10))
		assertEquals(8 + 80, celdas.size)
		// Encaminamiento de 40: celda 0
		val enc = b(0x00, 0x08, 0x09, 0, 0, 0, 0, 0x01, 0, 0, 0, 0, 0x00, 0x08, 0x09, 0, 0, 0, 0, 0, 0, 0, 0, 0)
		assertEquals(setOf(0), (l.recibir(enc).single() as PaqueteSeika.Teclas).encaminamiento)
	}

	@Test
	fun anotador() {
		val l = LectorSeika(ProtocoloSeika.ANOTADOR)
		// Identidad: 22 teclas, 40 celdas, 40 de encaminamiento
		assertEquals(listOf(PaqueteSeika.Identidad(40, 22, 40)), l.recibir(b(0xFF, 0xFF, 0xA2, 0x03, 22, 40, 40)))
		assertArrayEquals(b(0xFF, 0xFF, 0xA3, 2, 0x01, 0x03), ProtocoloSeika.ANOTADOR.celdas(2, b(0x01, 0x03)))
		// Puntos 1 y 2 con el espacio (bit 9): la b con espacio, es decir «Atrás»
		val t = l.recibir(b(0xFF, 0xFF, 0xA6, 0x03, 0x03, 0x02, 0x00)).single() as PaqueteSeika.Teclas
		val teclas = TeclasSeika.teclas(ProtocoloSeika.ANOTADOR, t)
		assertEquals(OrdenLinea.Hacer(Accion.ATRAS), TecladoLinea.orden(teclas))
		// Encaminamiento en la celda 10
		val r = l.recibir(b(0xFF, 0xFF, 0xA4, 0x05, 0x00, 0x04, 0x00, 0x00, 0x00)).single() as PaqueteSeika.Teclas
		assertEquals(OrdenLinea.Encaminar(10), TecladoLinea.orden(TeclasSeika.teclas(ProtocoloSeika.ANOTADOR, r)))
		// Combinado: teclas (3 bytes con 22 teclas) y encaminamiento
		val c = l.recibir(b(0xFF, 0xFF, 0xA8, 0x08, 0x00, 0x00, 0x08, 0x01, 0, 0, 0, 0)).single() as PaqueteSeika.Teclas
		assertEquals(1L shl 19, c.teclas)
		assertEquals(setOf(0), c.encaminamiento)
	}

	@Test
	fun ordenesDelAnotador() {
		fun o(vararg bits: Int): OrdenLinea {
			var v = 0L
			for (bit in bits) v = v or (1L shl bit)
			return TecladoLinea.orden(TeclasSeika.teclas(ProtocoloSeika.ANOTADOR, PaqueteSeika.Teclas(v, emptySet())))
		}
		// Escribir una a: el punto 1 solo
		assertEquals(OrdenLinea.Escribir(Puntos.de("1")), o(0))
		// Espacio, borrar sola, borrar con puntos hace de espacio, puntos 7 y 8
		assertEquals(OrdenLinea.Espacio, o(9))
		assertEquals(OrdenLinea.Borrar, o(8))
		assertEquals(OrdenLinea.Hacer(Accion.ANTERIOR), o(8, 0))
		assertEquals(OrdenLinea.Borrar, o(6))
		assertEquals(OrdenLinea.Intro, o(7))
		// Botones, palancas
		assertEquals(OrdenLinea.Desplazar(false), o(10))
		assertEquals(OrdenLinea.Desplazar(true), o(11))
		assertEquals(OrdenLinea.Hacer(Accion.INICIO), o(10, 11))
		assertEquals(OrdenLinea.Hacer(Accion.ACTIVAR), o(12))
		assertEquals(OrdenLinea.Hacer(Accion.SIGUIENTE), o(16))
		assertEquals(OrdenLinea.Hacer(Accion.CONTROL_SIGUIENTE), o(21))
		assertEquals(OrdenLinea.Hacer(Accion.ATRAS), o(17))
	}

	@Test
	fun ordenesDeLaLinea() {
		fun o(vararg teclas: Int) = TecladoLinea.orden(teclas.map { TeclaLinea(it) }.toSet())
		with(UsosSeika) {
			assertEquals(OrdenLinea.Desplazar(false), o(K1))
			assertEquals(OrdenLinea.Desplazar(true), o(K8))
			assertEquals(OrdenLinea.Hacer(Accion.ANTERIOR), o(K2))
			assertEquals(OrdenLinea.Hacer(Accion.SIGUIENTE), o(K3))
			assertEquals(OrdenLinea.Hacer(Accion.ACTIVAR), o(K5))
			assertEquals(OrdenLinea.Hacer(Accion.ATRAS), o(K4))
			assertEquals(OrdenLinea.Hacer(Accion.INICIO), o(K4, K5))
			assertEquals(OrdenLinea.Hacer(Accion.MENU_TIRESIAS), o(K1, K8))
		}
		// Las combinaciones de las líneas HID no cambian
		assertEquals(OrdenLinea.Hacer(Accion.ATRAS), o(UsosBraille.ESPACIO, UsosBraille.PUNTO_1, UsosBraille.PUNTO_1 + 1))
	}

	@Test
	fun bytesSinSentido() {
		val l = LectorSeika(ProtocoloSeika.ANOTADOR)
		assertTrue(l.recibir(ByteArray(600) { 0x11 }).isEmpty())
		assertEquals(listOf(PaqueteSeika.Identidad(40, 22, 40)), l.recibir(b(0xFF, 0xFF, 0xA2, 0x03, 22, 40, 40)))
	}
}
