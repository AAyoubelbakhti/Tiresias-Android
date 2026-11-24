package com.xebolax.tiresias.nucleo

/**
 * El papel de un elemento de la pantalla, como lo dice Tiresias.
 *
 * Se deduce del nombre de clase del nodo (como hace TalkBack en `Role.java`),
 * de su descripción de rol y de sus propiedades. [NINGUNO] es un elemento sin
 * papel que decir (texto suelto, contenedor).
 */
enum class Rol {
	NINGUNO,
	BOTON,
	BOTON_IMAGEN,
	BOTON_ALTERNAR,
	CASILLA,
	INTERRUPTOR,
	OPCION,
	CAMPO_TEXTO,
	DESLIZADOR,
	BARRA_PROGRESO,
	DESPLEGABLE,
	LISTA,
	CUADRICULA,
	TABLA,
	ELEMENTO_LISTA,
	CELDA,
	PESTANA,
	LISTA_PESTANAS,
	PAGINADOR,
	ENLACE,
	IMAGEN,
	MENU,
	ELEMENTO_MENU,
	BARRA_HERRAMIENTAS,
	DIALOGO,
	ALERTA,
	DESPLAZABLE,
	WEB,
	TEXTO,
	SELECTOR_FECHA,
	SELECTOR_HORA,
	SELECTOR_NUMERO,
	CHIP,
	VIDEO,
	MAPA,
	TECLA,
	CALENDARIO,
	EDITOR_VALOR,
	REGION,
	NAVEGACION,
	ESTADO,
}

/** Lo que la regla por clase no distingue al decir el rol de un elemento. */
object Roles {
	/**
	 * El rol que se dice de un elemento cuyo rol por clase es [rol] (TalkBack, `Role.java`):
	 * - Lo que solo se pulsa (una fila de Compose con `clickable` sin `Role`, que es
	 *   un `android.view.View` sin hijos) no es «botón» si tiene nombre: la pista
	 *   ya dice que se pulsa. Sin nombre sí, que «sin nombre, botón» ayuda.
	 * - Un `CompoundButton` propio es una casilla.
	 * - Un `CheckedTextView` en una lista de una sola opción ([unaSolaOpcion]) es una opción.
	 * - Algo con un rango válido ([conRango]) y sin clase conocida es una barra de
	 *   progreso, o un deslizador si se le puede poner el valor ([poneValor]).
	 *
	 * [web] es si el rol lo ha dado la página (entonces no se toca).
	 */
	fun paraDecir(
		rol: Rol, clase: String, conNombre: Boolean, web: Boolean = false, conRango: Boolean = false, poneValor: Boolean = false,
		unaSolaOpcion: () -> Boolean = { false },
	): Rol {
		if (web) return rol
		val pulsableSinClase = rol == Rol.BOTON && !clase.endsWith("Button")
		if ((rol == Rol.NINGUNO || pulsableSinClase) && conRango) return if (poneValor) Rol.DESLIZADOR else Rol.BARRA_PROGRESO
		return when {
			pulsableSinClase && conNombre -> Rol.NINGUNO
			rol == Rol.BOTON && clase.endsWith("CompoundButton") -> Rol.CASILLA
			rol == Rol.CASILLA && clase.endsWith("CheckedTextView") && unaSolaOpcion() -> Rol.OPCION
			else -> rol
		}
	}
}

/** Estado de una casilla, un interruptor o una opción (triestado en Android 16 o posterior). */
enum class Marcado { NO_MARCABLE, MARCADO, NO_MARCADO, PARCIAL }

/** Expandido o contraído (Android 16 añade el estado explícito; antes se deduce de las acciones). */
enum class Expansion { NINGUNA, EXPANDIDO, CONTRAIDO, EXPANDIDO_PARCIAL }

/** Un valor dentro de un rango: deslizadores y barras de progreso. */
data class Rango(
	val minimo: Float,
	val maximo: Float,
	val actual: Float,
	/** Tipo de rango de Android: 0 entero, 1 decimal, 2 por ciento. */
	val tipo: Int = 0,
	/** Texto del valor si la app lo da (stateDescription o el del nodo): manda sobre el número. */
	val texto: String? = null,
	val indeterminado: Boolean = false,
) {
	/** El valor en por ciento entre el mínimo y el máximo (0 si el rango no es válido). */
	val porCiento: Int
		get() = if (maximo > minimo) (((actual - minimo) * 100f) / (maximo - minimo)).let { Math.round(it) }.coerceIn(0, 100) else 0
}

/** Dónde está el elemento dentro de su colección (lista, cuadrícula, tabla). */
data class Posicion(
	/** Fila (o índice en una lista de una columna), empezando en 1; 0 si no se sabe. */
	val fila: Int = 0,
	val columna: Int = 0,
	val totalFilas: Int = 0,
	val totalColumnas: Int = 0,
	/** Encabezado de la fila y de la columna en una tabla, si lo hay. */
	val encabezadoFila: String? = null,
	val encabezadoColumna: String? = null,
	/** Si es una celda de encabezado de tabla. */
	val esEncabezado: Boolean = false,
	/** Si esa celda de encabezado lo es de una fila (si no, de una columna). */
	val esEncabezadoFila: Boolean = false,
) {
	companion object {
		/**
		 * La posición de un elemento en una colección de [filas] × [columnas] (las
		 * que dice la app; -1 es «no se sabe»), con sus índices desde 0. Como la
		 * posición en las colecciones de TalkBack (`CollectionStateFeedbackUtils`):
		 * - Solo es tabla o cuadrícula con más de una fila y más de una columna.
		 * - Una fila con varias columnas (pestañas, barra inferior, opciones en
		 *   fila) es una lista horizontal: «2 de 4» con el índice de columna.
		 * - Sin el total (-1, o el otro lado sin saber), no se da posición.
		 *
		 * Devuelve null si no hay nada que decir de la posición.
		 */
		fun enColeccion(
			filas: Int, columnas: Int, indiceFila: Int, indiceColumna: Int,
			encabezadoFila: String? = null, encabezadoColumna: String? = null,
			esEncabezado: Boolean = false, esEncabezadoFila: Boolean = false,
		): Posicion? {
			val tabla = filas > 1 && columnas > 1
			val (fila, columna, total) = when {
				tabla -> Triple(indiceFila + 1, indiceColumna + 1, filas)
				// Lista horizontal: una fila (o sin filas) y varias columnas
				columnas > 1 && filas != -1 -> Triple(indiceColumna + 1, 0, columnas)
				// Lista vertical: varias filas y una columna (o sin columnas)
				filas > 1 && columnas != -1 -> Triple(indiceFila + 1, 0, filas)
				// Un solo elemento: «1 de 1»
				filas == 1 && columnas in 0..1 -> Triple(indiceFila + 1, 0, 1)
				else -> Triple(0, 0, 0)
			}
			if (fila <= 0 && encabezadoFila.isNullOrBlank() && encabezadoColumna.isNullOrBlank() && !esEncabezado) return null
			return Posicion(
				fila = fila.coerceAtLeast(0),
				columna = columna.coerceAtLeast(0),
				totalFilas = total,
				totalColumnas = if (tabla) columnas else 0,
				encabezadoFila = encabezadoFila,
				encabezadoColumna = encabezadoColumna,
				esEncabezado = esEncabezado,
				esEncabezadoFila = esEncabezadoFila,
			)
		}
	}
}

/**
 * Lo que Tiresias sabe de un elemento de la pantalla para decirlo.
 *
 * Es una foto inmutable, sin referencias a Android: el servicio la construye a
 * partir del nodo de accesibilidad y el [Compositor] la convierte en frase.
 */
data class Elemento(
	/** El nombre: descripción del contenido o, si no hay, el texto visible (o el de sus hijos). */
	val nombre: String = "",
	val rol: Rol = Rol.NINGUNO,
	/** Lo escrito en un campo de texto (el nombre es su etiqueta). Vacío si no hay nada. */
	val texto: String = "",
	/** Descripción de rol que da la app (roleDescription): sustituye al nombre del [rol]. */
	val descripcionRol: String? = null,
	/** Descripción de estado que da la app (stateDescription): sustituye al estado calculado. */
	val estado: String? = null,
	val marcado: Marcado = Marcado.NO_MARCABLE,
	val seleccionado: Boolean = false,
	val activo: Boolean = true,
	val expansion: Expansion = Expansion.NINGUNA,
	val obligatorio: Boolean = false,
	/** Texto de error del campo (setError), con lo que hay que corregir. */
	val error: String? = null,
	val encabezado: Boolean = false,
	/** Nivel de encabezado (1 a 6) si se sabe (web); 0 si no. */
	val nivelEncabezado: Int = 0,
	/** Pista del campo (hintText) o texto de ayuda. */
	val pista: String? = null,
	/** Descripción complementaria (Android 16, o la de AndroidX en los extras). */
	val complementaria: String? = null,
	/** Información sobre herramientas (tooltip): se dice siempre que no repita el nombre. */
	val tooltip: String? = null,
	/**
	 * La pista de algo que no es un campo (hintText; en una web, el `title` o la
	 * `aria-description`): es parte de lo que se dice de él, no una pista de uso.
	 */
	val pistaNodo: String? = null,
	/** Un campo de texto que se ve pero no se puede escribir (el de una fecha que abre un calendario). */
	val soloLectura: Boolean = false,
	val rango: Rango? = null,
	val posicion: Posicion? = null,
	val pulsable: Boolean = false,
	val mantenible: Boolean = false,
	val editable: Boolean = false,
	val contrasena: Boolean = false,
	/**
	 * Datos sensibles: una contraseña o lo que la app marca así (Android 14,
	 * `isAccessibilityDataSensitive`: banca, códigos). Tiresias lo lee, pero nunca
	 * lo da a un complemento, a la IA ni al reconocimiento de texto.
	 */
	val sensible: Boolean = false,
	/** Si tiene acciones propias de la app (además de pulsar y mantener). */
	val tieneAcciones: Boolean = false,
	/** Cuántos enlaces hay dentro de su texto («Más información» en medio de una frase). */
	val enlaces: Int = 0,
	/** Cuántas faltas de ortografía (o de gramática) marca el corrector en lo escrito en un campo. */
	val faltas: Int = 0,
	/** Si es un campo en el que se está escribiendo ahora (tiene el foco de entrada). */
	val escribiendo: Boolean = false,
	/** Identificador de la vista (com.app:id/boton_enviar), para los elementos sin nombre. */
	val identificador: String? = null,
	/** Etiqueta que el usuario puso a este elemento: manda sobre el nombre. */
	val etiquetaPersonal: String? = null,
	/** Enlace ya visitado (web). */
	val visitado: Boolean = false,
	/** Título del panel o de la ventana a la que pertenece, si es su primer elemento. */
	val tituloPanel: String? = null,
)
