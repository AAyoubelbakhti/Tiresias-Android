package com.xebolax.tiresias.nucleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompositorTest {
	private val c = Compositor(TextosPrueba)

	@Test
	fun losEncabezadosDeTablaAntesDespuesONunca() {
		val celda = Elemento(nombre = "12", posicion = Posicion(fila = 2, columna = 3, totalFilas = 5, totalColumnas = 4, encabezadoFila = "Lunes", encabezadoColumna = "Ventas"))
		assertEquals("Lunes, Ventas, fila 2, columna 3", c.posicion(celda))
		assertEquals("fila 2, columna 3, Lunes, Ventas", c.posicion(celda, Verbosidad(encabezadosDespues = true)))
		assertEquals("fila 2, columna 3", c.posicion(celda, Verbosidad(decirEncabezadosTabla = false)))
		assertEquals("Lunes, Ventas", c.posicion(celda, Verbosidad(decirFilaColumna = false)))
	}

	@Test
	fun lasPistasNombranLosGestosDelUsuario() {
		val conAcciones = Elemento(nombre = "Mensaje", tieneAcciones = true)
		assertEquals("Toca con tres dedos para ver sus acciones.", c.pista(conAcciones))
		val otro = Compositor(TextosPrueba, gestos = Compositor.GestosDePistas(menuAcciones = "Deslizar arriba y luego a la izquierda"))
		assertEquals("Deslizar arriba y luego a la izquierda para ver sus acciones.", otro.pista(conAcciones))
		// Con «activar al levantar el dedo»: nada de doble toque
		val levantar = Compositor(TextosPrueba, gestos = Compositor.GestosDePistas(alLevantar = true))
		assertEquals("Levanta el dedo para activar.", levantar.pista(Elemento(nombre = "Guardar", rol = Rol.BOTON, pulsable = true)))
	}

	@Test
	fun conAccionesArribaYAbajoLaPistaLoDice() {
		val arribaAbajo = Compositor(TextosPrueba, gestos = Compositor.GestosDePistas(accionesArribaAbajo = true))
		val elegir = "Desliza arriba o abajo para elegir una acción y toca dos veces para hacerla."
		// Solo con acciones: en vez del menú de acciones
		assertEquals(elegir, arribaAbajo.pista(Elemento(nombre = "Mensaje", tieneAcciones = true)))
		// Una casilla con acciones: cómo se cambia y cómo se eligen
		val casilla = Elemento(nombre = "Al activar", rol = Rol.CASILLA, marcado = Marcado.MARCADO, pulsable = true, tieneAcciones = true)
		assertEquals("Toca dos veces para cambiar. $elegir", arribaAbajo.pista(casilla))
		// Un deslizador: arriba y abajo cambian el valor
		val barra = Elemento(nombre = "Volumen", rol = Rol.DESLIZADOR, tieneAcciones = true)
		assertEquals(c.pista(barra), arribaAbajo.pista(barra))
	}

	@Test
	fun interruptorDiceActivadoYNoMarcado() {
		val e = Elemento(nombre = "Wifi", rol = Rol.INTERRUPTOR, marcado = Marcado.MARCADO, pulsable = true)
		assertEquals("Wifi, interruptor, activado", c.describir(e))
	}

	@Test
	fun casillaParcial() {
		val e = Elemento(nombre = "Todo", rol = Rol.CASILLA, marcado = Marcado.PARCIAL)
		assertEquals("Todo, casilla, parcialmente marcado", c.describir(e))
	}

	@Test
	fun ordenEstadoPrimero() {
		val e = Elemento(nombre = "Leído", rol = Rol.CASILLA, marcado = Marcado.NO_MARCADO)
		assertEquals("no marcado, Leído, casilla", c.describir(e, Verbosidad(orden = Verbosidad.ORDEN_ESTADO_PRIMERO)))
	}

	@Test
	fun estadoDeLaAppMandaSobreElCalculado() {
		val e = Elemento(nombre = "Modo", rol = Rol.INTERRUPTOR, marcado = Marcado.MARCADO, estado = "automático")
		assertEquals("Modo, interruptor, automático", c.describir(e))
	}

	@Test
	fun sinNombreDiceElIdentificador() {
		val e = Elemento(rol = Rol.BOTON_IMAGEN, pulsable = true, identificador = "com.app:id/boton_enviar")
		assertEquals("boton enviar, sin nombre, botón", c.describir(e))
		val sinId = Elemento(rol = Rol.BOTON_IMAGEN, pulsable = true, identificador = "com.app:id/view12")
		assertEquals("sin nombre, botón", c.describir(sinId))
	}

	@Test
	fun etiquetaPersonalMandaSobreElNombre() {
		val e = Elemento(nombre = "ic_menu", rol = Rol.BOTON, etiquetaPersonal = "Menú principal")
		assertEquals("Menú principal, botón", c.describir(e))
	}

	@Test
	fun deslizadorConPorCiento() {
		val e = Elemento(nombre = "Volumen", rol = Rol.DESLIZADOR, rango = Rango(0f, 100f, 40f))
		assertEquals("Volumen, 40 por ciento, deslizador", c.describir(e))
		val propio = Elemento(nombre = "Velocidad", rol = Rol.DESLIZADOR, rango = Rango(0f, 10f, 2f), estado = "1,5x")
		assertEquals("Velocidad, 1,5x, deslizador", c.describir(propio))
	}

	@Test
	fun posicionEnListaYTabla() {
		val fila = Elemento(nombre = "Correo", posicion = Posicion(fila = 3, totalFilas = 10))
		assertEquals("Correo, 3 de 10", c.describir(fila))
		val celda = Elemento(nombre = "12", posicion = Posicion(fila = 2, columna = 3, totalFilas = 5, totalColumnas = 4, encabezadoColumna = "Precio"))
		assertEquals("12, Precio, fila 2, columna 3", c.describir(celda))
	}

	@Test
	fun encabezadoConNivel() {
		val e = Elemento(nombre = "Noticias", encabezado = true, nivelEncabezado = 2)
		assertEquals("Noticias, encabezado de nivel 2", c.describir(e))
		// Chrome manda «heading 2» como descripción de rol: no se repite
		assertEquals("Noticias, encabezado de nivel 2", c.describir(e.copy(descripcionRol = "heading 2")))
	}

	@Test
	fun errorYObligatorio() {
		val e = Elemento(nombre = "Correo", rol = Rol.CAMPO_TEXTO, editable = true, obligatorio = true, error = "Escribe un correo válido")
		assertEquals("Correo, campo de texto, obligatorio, error: Escribe un correo válido", c.describir(e))
	}

	@Test
	fun noRepiteLoMismo() {
		val e = Elemento(nombre = "Seleccionado", seleccionado = true)
		assertEquals("Seleccionado", c.describir(e))
	}

	@Test
	fun pistas() {
		assertEquals("Toca dos veces para activar.", c.pista(Elemento(nombre = "Enviar", rol = Rol.BOTON, pulsable = true)))
		assertNull(c.pista(Elemento(nombre = "Enviar", rol = Rol.BOTON, pulsable = true), Verbosidad(decirPistas = false)))
		assertNull(c.pista(Elemento(nombre = "Enviar", rol = Rol.BOTON, pulsable = true, activo = false)))
	}

	@Test
	fun identificadoresLegibles() {
		assertEquals("btn send message", Compositor.legible("com.x:id/btnSendMessage"))
		assertNull(Compositor.legible("com.x:id/view3"))
		assertNull(Compositor.legible("12345"))
	}

	@Test
	fun posicionEnListasHorizontalesYSinTotal() {
		// Pestañas o barra inferior: una fila y tres columnas → «2 de 3», no «fila 1, columna 2»
		val pestana = Elemento(nombre = "Chats", rol = Rol.PESTANA, posicion = Posicion.enColeccion(1, 3, 0, 1))
		assertEquals("Chats, pestaña, 2 de 3", c.describir(pestana))
		// Sin filas (0) y cuatro columnas: también horizontal
		assertEquals("3 de 4", c.posicion(Elemento(posicion = Posicion.enColeccion(0, 4, 0, 2))))
		// Un ChipGroup de varias líneas (columnas -1): sin «1 de 2»
		assertNull(c.posicion(Elemento(nombre = "Rojo", posicion = Posicion.enColeccion(2, -1, 1, 0))))
		// Una lista de Compose con el total sin saber (filas -1): sin «5 de 1»
		assertNull(c.posicion(Elemento(nombre = "Correo", posicion = Posicion.enColeccion(-1, 1, 4, 0))))
		// Lista vertical normal y tabla de verdad
		assertEquals("5 de 10", c.posicion(Elemento(posicion = Posicion.enColeccion(10, 1, 4, 0))))
		assertEquals("fila 2, columna 3", c.posicion(Elemento(nombre = "12", posicion = Posicion.enColeccion(5, 4, 1, 2))))
		// Una lista de un solo elemento
		assertEquals("1 de 1", c.posicion(Elemento(posicion = Posicion.enColeccion(1, 1, 0, 0))))
	}

	@Test
	fun celdaDeEncabezadoDeTabla() {
		val columna = Elemento(nombre = "Precio", posicion = Posicion.enColeccion(5, 4, 0, 2, esEncabezado = true))
		assertEquals("Precio, encabezado de columna, fila 1, columna 3", c.describir(columna))
		val fila = Elemento(nombre = "Lunes", posicion = Posicion.enColeccion(5, 4, 1, 0, esEncabezado = true, esEncabezadoFila = true))
		assertEquals("Lunes, encabezado de fila, fila 2, columna 1", c.describir(fila))
	}

	@Test
	fun loQueSoloSePulsaNoEsBoton() {
		// Una fila de Compose con clickable sin Role: android.view.View sin hijos, con nombre → sin «botón»
		val fila = Roles.paraDecir(Rol.BOTON, "android.view.View", conNombre = true)
		assertEquals(Rol.NINGUNO, fila)
		assertEquals("Wifi, Conectado", c.describir(Elemento(nombre = "Wifi, Conectado", rol = fila, pulsable = true)))
		// Sin nombre sí es botón («sin nombre, botón» ayuda); un Button de verdad, siempre
		assertEquals(Rol.BOTON, Roles.paraDecir(Rol.BOTON, "android.view.View", conNombre = false))
		assertEquals(Rol.BOTON, Roles.paraDecir(Rol.BOTON, "android.widget.Button", conNombre = true))
		// Un CompoundButton propio es una casilla; un CheckedTextView de una sola opción, una opción
		assertEquals(Rol.CASILLA, Roles.paraDecir(Rol.BOTON, "android.widget.CompoundButton", conNombre = true))
		assertEquals(Rol.OPCION, Roles.paraDecir(Rol.CASILLA, "android.widget.CheckedTextView", conNombre = true) { true })
		assertEquals(Rol.CASILLA, Roles.paraDecir(Rol.CASILLA, "android.widget.CheckedTextView", conNombre = true) { false })
		// Un rango sin clase conocida: barra de progreso, o deslizador si se le pone el valor
		assertEquals(Rol.BARRA_PROGRESO, Roles.paraDecir(Rol.NINGUNO, "android.view.View", conNombre = false, conRango = true))
		assertEquals(Rol.DESLIZADOR, Roles.paraDecir(Rol.NINGUNO, "android.view.View", conNombre = false, conRango = true, poneValor = true))
		// Lo que dice la web no se toca
		assertEquals(Rol.BOTON, Roles.paraDecir(Rol.BOTON, "android.view.View", conNombre = true, web = true))
	}

	@Test
	fun noDisponibleAlFinalYNuncaEnEncabezados() {
		// Al final de todo, también con el estado primero
		val casilla = Elemento(nombre = "Leído", rol = Rol.CASILLA, marcado = Marcado.NO_MARCADO, activo = false, posicion = Posicion(fila = 2, totalFilas = 5))
		assertEquals("Leído, casilla, no marcado, 2 de 5, no disponible", c.describir(casilla))
		assertEquals("no marcado, Leído, casilla, 2 de 5, no disponible", c.describir(casilla, Verbosidad(orden = Verbosidad.ORDEN_ESTADO_PRIMERO)))
		// Una categoría de ajustes desactivada por el tema: sin «no disponible»
		val categoria = Elemento(nombre = "Pantalla", encabezado = true, activo = false)
		assertEquals("Pantalla, encabezado", c.describir(categoria))
		// El cambio de estado (decirCambioEstado) sigue diciéndolo
		assertEquals(listOf("no marcado", "no disponible"), c.estados(casilla))
	}

	@Test
	fun campoDeSoloLectura() {
		val fecha = Elemento(nombre = "Fecha", rol = Rol.CAMPO_TEXTO, texto = "24/11/2026", soloLectura = true, pulsable = true)
		assertEquals("Fecha, 24/11/2026, campo de texto, solo lectura", c.describir(fecha))
		// Sin la pista de escribir: se pulsa (abre el calendario)
		assertEquals("Toca dos veces para activar.", c.pista(fecha))
		// Sin nombre, se reconoce por lo que tiene escrito
		assertEquals(false, c.sinNombre(fecha.copy(nombre = "")))
	}

	@Test
	fun tooltipYPistaDelNodoConCualquierVerbosidad() {
		val boton = Elemento(nombre = "Compartir", rol = Rol.BOTON, tooltip = "Compartir con tus contactos", pulsable = true)
		assertEquals("Compartir, botón, Compartir con tus contactos", c.describir(boton, Verbosidad.de(NivelVerbosidad.MEDIA)))
		// El tooltip igual que el nombre (lo normal en los botones de icono de Material) no se repite
		assertEquals("Compartir, botón", c.describir(boton.copy(tooltip = "Compartir")))
		// La pista de lo que no es un campo va en la frase, y la de uso no se pierde por ella
		val enlace = Elemento(nombre = "Inicio", rol = Rol.ENLACE, pistaNodo = "Vuelve a la portada", pulsable = true)
		assertEquals("Inicio, enlace, Vuelve a la portada", c.describir(enlace, Verbosidad.de(NivelVerbosidad.BAJA).copy(decirRol = true)))
		assertEquals("Toca dos veces para activar.", c.pista(enlace))
		// La complementaria sigue dependiendo de la verbosidad
		val articulo = Elemento(nombre = "Noticia", complementaria = "Por Ana")
		assertEquals("Noticia, Por Ana", c.describir(articulo))
		assertEquals("Noticia", c.describir(articulo, Verbosidad.de(NivelVerbosidad.MEDIA)))
	}

	@Test
	fun loExpandibleQueSePulsaDiceQueSePulsa() {
		// Lo que se pulsa: el doble toque lo pulsa; expandir está en las acciones
		val fila = Elemento(nombre = "Descargas", expansion = Expansion.CONTRAIDO, pulsable = true)
		assertEquals("Toca dos veces para activar. Toca con tres dedos para ver sus acciones.", c.pista(fila))
		// Lo que no se pulsa sigue diciendo que se expande
		assertEquals("Toca dos veces para expandir.", c.pista(fila.copy(pulsable = false)))
	}

	@Test
	fun identificadoresNumeradosDeAndroidNoSeDicen() {
		assertNull(Compositor.legible("android:id/button1"))
		assertNull(Compositor.legible("android:id/text2"))
		assertEquals("summary", Compositor.legible("android:id/summary"))
		assertEquals("button1", Compositor.legible("com.app:id/button1"))
	}

	@Test
	fun coleccion() {
		assertEquals("lista con 12 elementos", c.entrarEnColeccion(Rol.LISTA, 12, 1))
		assertEquals("cuadrícula con 3 filas, 4 columnas", c.entrarEnColeccion(Rol.CUADRICULA, 3, 4))
	}
}
