package com.xebolax.tiresias.nucleo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La salida de emergencia tiene que ser un gesto que Android reconozca: el triple toque mantenido con cuatro dedos no
 * existe en Android (no hay constante), así que desde la 1.0.19 es el doble toque mantenido con cuatro dedos.
 */
class EmergenciaTest {
	@Test
	fun elDobleToqueMantenidoConCuatroDedosEsElModoSeguroEnTodosLosMapas() {
		for (m in listOf(MapaGestos.POR_DEFECTO, MapaGestos.SENCILLO, MapaGestos.SENIOR)) {
			assertEquals(Accion.MODO_SEGURO, m[Gesto.DOBLE_TOQUE_MANTENIDO_4])
		}
	}

	@Test
	fun nadaLaQuita() {
		val m = MapaGestos.leer("DOBLE_TOQUE_MANTENIDO_4=NINGUNA;ARRIBA=SIGUIENTE")
		assertEquals(Accion.MODO_SEGURO, m[Gesto.DOBLE_TOQUE_MANTENIDO_4])
	}

	@Test
	fun dejarPasarElGestoSigueTeniendoGesto() {
		assertEquals(Accion.PASAR_GESTO, MapaGestos.POR_DEFECTO[Gesto.TRIPLE_TOQUE_MANTENIDO_2])
	}
}
