package com.xebolax.tiresias.arbol

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.xebolax.tiresias.base.Registro
import com.xebolax.tiresias.nucleo.Rol

/**
 * Ayudas sobre los nodos de accesibilidad: rol, si reciben el foco, su texto.
 *
 * Cada llamada a un nodo puede ser una consulta a la app que se está leyendo
 * (entre procesos), así que se leen las propiedades una vez y se guarda lo que
 * se necesita. Todo se usa desde el hilo del lector.
 */
object Nodos {
	private const val ETIQUETA = "Nodos"
	private const val PROFUNDIDAD_MAXIMA = 60
	/** Hasta cuántos niveles se mira dentro de un control para saber si agrupa a otros (página, contenedor, icono). */
	private const val NIVELES_AGRUPAR = 4
	/** La parte de la pantalla que ocupa el marco de una página entera (de alto). */
	private const val PARTE_PAGINA = 0.6f
	/** La parte de la pantalla que ocupa de ancho el marco o el velo de una web. */
	private const val PARTE_ANCHO = 0.8f
	/** Cuántos nodos se miran como mucho para saber si un marco de página tiene controles muy hondos. */
	private const val MAXIMO_NODOS_MARCO = 400
	/** A partir de cuántos textos o letras algo pulsable de una web que no es un control no se lee de una vez. */
	private const val TEXTOS_CONTENEDOR_WEB = 6
	private const val LETRAS_CONTENEDOR_WEB = 400

	/**
	 * «Enfocar el texto de dentro por separado» (Ajustes, Navegación; SandFly 8d046e83b): cada texto de dentro de un
	 * elemento que se pulsa es su propia parada, y el elemento solo lo es si dice algo por sí mismo. Para las filas que
	 * esconden su texto detrás de su propia descripción. Lo pone el navegador antes de hacer cada orden.
	 */
	@Volatile var textoDentroAparte = false

	/** El rol de [n] por su clase (como `Role.java` de TalkBack), su rol web y sus propiedades. */
	fun rol(n: AccessibilityNodeInfo): Rol {
		val clase = n.className?.toString().orEmpty()
		val web = rolWeb(n)
		if (web != null) return web
		return when {
			n.isEditable || clase.endsWith("EditText") || clase.endsWith("AutoCompleteTextView") -> Rol.CAMPO_TEXTO
			clase.endsWith("Switch") || clase.endsWith("SwitchCompat") || clase.endsWith("SwitchMaterial") || clase.endsWith("MaterialSwitch") -> Rol.INTERRUPTOR
			clase.endsWith("ToggleButton") -> Rol.BOTON_ALTERNAR
			clase.endsWith("CheckBox") || clase.endsWith("MaterialCheckBox") -> Rol.CASILLA
			clase.endsWith("CheckedTextView") -> if (n.isCheckable) Rol.CASILLA else Rol.TEXTO
			clase.endsWith("RadioButton") || clase.endsWith("MaterialRadioButton") -> Rol.OPCION
			clase.endsWith("SeekBar") || clase.endsWith("Slider") || clase.endsWith("RangeSlider") || clase.endsWith("RatingBar") -> Rol.DESLIZADOR
			clase.endsWith("ProgressBar") || clase.endsWith("LinearProgressIndicator") || clase.endsWith("CircularProgressIndicator") ->
				if (n.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id }) Rol.DESLIZADOR else Rol.BARRA_PROGRESO
			clase.endsWith("Spinner") -> Rol.DESPLEGABLE
			clase.endsWith("ImageButton") || clase.endsWith("FloatingActionButton") -> Rol.BOTON_IMAGEN
			clase.endsWith("Chip") -> Rol.CHIP
			clase.endsWith("Button") || clase.endsWith("MaterialButton") -> Rol.BOTON
			clase.endsWith("ImageView") || clase.endsWith("Image") -> if (n.isClickable) Rol.BOTON_IMAGEN else Rol.IMAGEN
			clase.endsWith("WebView") -> Rol.WEB
			clase.endsWith("ViewPager") || clase.endsWith("ViewPager2") -> Rol.PAGINADOR
			clase.endsWith("TabWidget") || clase.endsWith("TabLayout") -> Rol.LISTA_PESTANAS
			clase.endsWith("\$TabView") || clase.endsWith("TabView") -> Rol.PESTANA
			clase.endsWith("GridView") -> Rol.CUADRICULA
			clase.endsWith("ListView") || clase.endsWith("RecyclerView") -> coleccion(n)
			clase.endsWith("ScrollView") || clase.endsWith("HorizontalScrollView") || clase.endsWith("NestedScrollView") -> Rol.DESPLAZABLE
			clase.endsWith("Toolbar") || clase.endsWith("ActionBar") -> Rol.BARRA_HERRAMIENTAS
			clase.endsWith("NumberPicker") -> Rol.SELECTOR_NUMERO
			clase.endsWith("DatePicker") -> Rol.SELECTOR_FECHA
			clase.endsWith("TimePicker") -> Rol.SELECTOR_HORA
			clase.endsWith("CalendarView") -> Rol.CALENDARIO
			clase.endsWith("VideoView") || clase.endsWith("PlayerView") -> Rol.VIDEO
			clase.endsWith("MapView") -> Rol.MAPA
			clase.contains("Dialog") -> Rol.DIALOGO
			clase.endsWith("TextView") -> Rol.TEXTO
			n.isCheckable && n.isClickable -> Rol.CASILLA
			// Un deslizador hecho a mano (Compose, apps propias): tiene un valor y se le puede poner otro
			esAjustable(n) && n.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id } -> Rol.DESLIZADOR
			n.isClickable && clase.endsWith("View") && n.childCount == 0 -> Rol.BOTON
			else -> Rol.NINGUNO
		}
	}

	private fun coleccion(n: AccessibilityNodeInfo): Rol {
		val c = n.collectionInfo ?: return Rol.LISTA
		return if (c.columnCount > 1 && c.rowCount > 1) Rol.CUADRICULA else Rol.LISTA
	}

	/**
	 * Chrome y WebView dicen el rol HTML en los extras (`AccessibilityNodeInfo.chromeRole`).
	 * Así se distinguen los enlaces, los encabezados y las regiones.
	 */
	fun rolWeb(n: AccessibilityNodeInfo): Rol? {
		val extras = runCatching { n.extras }.getOrNull() ?: return null
		val rol = extras.getCharSequence("AccessibilityNodeInfo.chromeRole")?.toString() ?: return null
		return when (rol) {
			"link" -> Rol.ENLACE
			"button", "popUpButton" -> Rol.BOTON
			"checkBox" -> Rol.CASILLA
			"switch" -> Rol.INTERRUPTOR
			"radioButton" -> Rol.OPCION
			"textField", "searchBox", "textFieldWithComboBox" -> Rol.CAMPO_TEXTO
			"slider" -> Rol.DESLIZADOR
			"progressIndicator", "meter" -> Rol.BARRA_PROGRESO
			"comboBoxSelect", "comboBoxMenuButton", "listBox" -> Rol.DESPLEGABLE
			"image", "img", "svgRoot", "graphicsDocument" -> Rol.IMAGEN
			"list", "descriptionList" -> Rol.LISTA
			"listItem", "listBoxOption" -> Rol.ELEMENTO_LISTA
			"table", "grid", "treeGrid" -> Rol.TABLA
			"cell", "gridCell", "columnHeader", "rowHeader" -> Rol.CELDA
			"tab" -> Rol.PESTANA
			"tabList" -> Rol.LISTA_PESTANAS
			"menu", "menuBar" -> Rol.MENU
			"menuItem", "menuItemCheckBox", "menuItemRadio" -> Rol.ELEMENTO_MENU
			"dialog", "alertDialog" -> Rol.DIALOGO
			"alert" -> Rol.ALERTA
			"navigation" -> Rol.NAVEGACION
			"banner", "contentInfo", "main", "complementary", "form", "region", "search" -> Rol.REGION
			"toolbar" -> Rol.BARRA_HERRAMIENTAS
			"status", "log", "timer" -> Rol.ESTADO
			"date", "dateTime" -> Rol.SELECTOR_FECHA
			"inputTime" -> Rol.SELECTOR_HORA
			"video", "audio" -> Rol.VIDEO
			"heading" -> Rol.TEXTO
			else -> null
		}
	}

	/** Si [n] es un encabezado (Android 9+, o el rol web, o un elemento de colección marcado). */
	fun esEncabezado(n: AccessibilityNodeInfo): Boolean {
		if (Build.VERSION.SDK_INT >= 28 && n.isHeading) return true
		@Suppress("DEPRECATION")
		if (n.collectionItemInfo?.isHeading == true) return true
		return runCatching { n.extras?.getCharSequence("AccessibilityNodeInfo.chromeRole")?.toString() == "heading" }.getOrDefault(false)
	}

	/** El nivel de un encabezado web (1 a 6) por su descripción de rol («encabezado 2», «heading 2»), o 0. */
	fun nivelEncabezado(n: AccessibilityNodeInfo): Int {
		val descripcion = runCatching { n.extras?.getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString() }.getOrNull()
		return descripcion?.let { Regex("([1-6])").find(it)?.value?.toIntOrNull() } ?: 0
	}

	/** Si [n] se puede pulsar, mantener, marcar o editar: entonces sus hijos se leen dentro de él. */
	fun accionable(n: AccessibilityNodeInfo): Boolean =
		n.isClickable || n.isLongClickable || n.isEditable ||
			n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK || it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }

	/**
	 * Una casilla o un interruptor que no se pulsa por sí mismo y está dentro de
	 * algo que sí (el interruptor de una fila de ajustes): no es una parada aparte,
	 * lo dice la fila con su estado. Como en TalkBack, marcable no es pulsable.
	 */
	fun marcableDeFila(n: AccessibilityNodeInfo): Boolean = n.isCheckable && !accionable(n) && antepasadoQueLee(n)

	/** Si la lista acepta desplazarse por accesibilidad (en alguna dirección): si no, hay que arrastrarla. */
	fun seDesplazaPorAccesibilidad(lista: AccessibilityNodeInfo): Boolean {
		val ids = setOf(
			AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
			android.R.id.accessibilityActionScrollUp, android.R.id.accessibilityActionScrollDown,
			android.R.id.accessibilityActionScrollLeft, android.R.id.accessibilityActionScrollRight,
		)
		return lista.actionList.any { it.id in ids }
	}

	/** Si [n] está dentro de [contenedor] (es él o un descendiente). */
	fun dentroDe(n: AccessibilityNodeInfo, contenedor: AccessibilityNodeInfo): Boolean {
		var p: AccessibilityNodeInfo? = n
		var nivel = 0
		while (p != null && nivel < 25) {
			if (p == contenedor) return true
			p = runCatching { p!!.parent }.getOrNull()
			nivel++
		}
		return false
	}

	/** El único interruptor o casilla «de fila» dentro de [n] (hasta tres niveles), o null. */
	fun marcableInterior(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
		var encontrado: AccessibilityNodeInfo? = null
		fun buscar(x: AccessibilityNodeInfo, nivel: Int): Boolean {
			if (nivel > 3) return true
			for (i in 0 until x.childCount) {
				val h = runCatching { x.getChild(i) }.getOrNull() ?: continue
				if (h.isCheckable && !accionable(h)) {
					if (encontrado != null) return false
					encontrado = h
				} else if (!accionable(h) && !buscar(h, nivel + 1)) return false
			}
			return true
		}
		return if (buscar(n, 1)) encontrado else null
	}

	/** Si [n] tiene algo que decir por sí mismo (texto, descripción, estado o es un control). */
	fun tieneTexto(n: AccessibilityNodeInfo): Boolean =
		!n.contentDescription.isNullOrBlank() || !n.text.isNullOrBlank() ||
			(Build.VERSION.SDK_INT >= 30 && !n.stateDescription.isNullOrBlank())

	// --- La regla única: qué es una parada ---------------------------------------------------------------------------
	//
	// Como `shouldFocusNode` de TalkBack: una sola regla para lo que se recorre al deslizar, lo que lee cada parada y lo
	// que se enfoca al tocar. Antes estaba repartida en cuatro funciones con límites distintos (lo que leía una parada
	// llegaba a 9 niveles; lo que quitaba del recorrido, a 5), y donde no coincidían se saltaban cosas, se juntaban o el
	// foco del toque quedaba fuera del orden (Play Store «Acerca de esta app»: todo dentro de un marco pulsable a pantalla
	// completa con los textos muy hondos). Ahora no hay límite de profundidad: cada texto es una parada o lo lee
	// exactamente una parada, la primera de las de encima.

	/** Memoria de lo decidido para cada nodo (como `speakingNodesCache` de TalkBack): vale lo que dura un orden. */
	private val memoriaParada = java.util.concurrent.ConcurrentHashMap<AccessibilityNodeInfo, Boolean>()
	private val memoriaParadaOculta = java.util.concurrent.ConcurrentHashMap<AccessibilityNodeInfo, Boolean>()
	private val memoriaHabla = java.util.concurrent.ConcurrentHashMap<AccessibilityNodeInfo, Boolean>()
	private val memoriaContenido = java.util.concurrent.ConcurrentHashMap<AccessibilityNodeInfo, Any>()
	@Volatile private var memoriaDesde = 0L
	/** Lo que dura lo decidido fuera de un orden (al tocar, al describir): la pantalla puede haber cambiado. */
	private const val VIDA_MEMORIA_MS = 400L
	private val SIN_CONTENIDO = Any()

	/** Olvida lo decidido (al empezar un orden nuevo, o si ya es viejo). */
	private fun olvidar(forzar: Boolean) {
		val ahora = android.os.SystemClock.uptimeMillis()
		if (!forzar && ahora - memoriaDesde < VIDA_MEMORIA_MS) return
		memoriaParada.clear()
		memoriaParadaOculta.clear()
		memoriaHabla.clear()
		memoriaContenido.clear()
		memoriaDesde = ahora
	}

	/**
	 * Si [n] «toma el foco» por sí mismo (`takesFocusWhenVisible` de TalkBack): se pulsa, se mantiene, se edita, la app
	 * lo hace parada (`screenReaderFocusable`), es un deslizador, o es enfocable (como en TalkBack, enfocable cuenta: la
	 * forma clásica de agrupar «Etiqueta: valor» y las vistas `accessible` de React Native). Salvo:
	 * - un texto que solo se pulsa porque lleva enlaces, dentro de algo que se pulsa (el mensaje de WhatsApp): va con
	 *   su fila;
	 * - las listas y las páginas web, que no son paradas por sí mismas;
	 * - un marco pulsable que repite a su padre pulsable, del mismo tamaño y sin decir nada distinto.
	 */
	fun tomaFoco(n: AccessibilityNodeInfo): Boolean {
		if (Build.VERSION.SDK_INT >= 28 && n.isScreenReaderFocusable) return true
		if (esAjustable(n) && n.isVisibleToUser) return true
		val pulsa = accionable(n)
		if (!pulsa && !n.isFocusable) return false
		if (esLista(n) || rol(n) == Rol.WEB) return false
		if (!pulsa && soloEnfocableQueNoAgrupa(n)) return false
		// Lo marcable que no se pulsa (el interruptor de una fila de ajustes) lo dice la fila con su estado
		if (n.isCheckable && !pulsa) return false
		if (pulsa && esTextoConEnlacesEnFila(n)) return false
		if (esEnvolturaRepetida(n)) return false
		return true
	}

	/**
	 * Algo que solo es enfocable (no se pulsa) y no sirve para agrupar: la vista raíz de las apps hechas con Compose y otros
	 * contenedores de toda la pantalla, que son enfocables para el teclado; y en una web, lo enfocable que no es un control
	 * (un `main` con `tabindex`). Si contaran, la pantalla o la página entera serían una sola parada.
	 */
	private fun soloEnfocableQueNoAgrupa(n: AccessibilityNodeInfo): Boolean {
		val web = rolChrome(n)
		if (web != null) return web !in ROLES_CONTROL_WEB
		if (n.childCount == 0) return false
		val clase = n.className?.toString().orEmpty()
		if (clase.endsWith("ComposeView") || clase.endsWith("AndroidComposeView") || clase.endsWith("DecorView")) return true
		val m = android.content.res.Resources.getSystem().displayMetrics
		val r = limites(n)
		return m.heightPixels > 0 && r.height() >= m.heightPixels / 2
	}

	/** Un texto que es pulsable solo por llevar enlaces, con un antepasado cercano que se pulsa (se lee con él). */
	private fun esTextoConEnlacesEnFila(n: AccessibilityNodeInfo): Boolean {
		if (n.className?.toString()?.endsWith("TextView") != true || !n.contentDescription.isNullOrBlank()) return false
		if (!tieneEnlacesEnTexto(n)) return false
		var p = runCatching { n.parent }.getOrNull()
		repeat(3) {
			val x = p ?: return false
			if (esLista(x)) return false
			if (accionable(x)) return true
			p = runCatching { x.parent }.getOrNull()
		}
		return false
	}

	/** Un marco pulsable sin nada propio que ocupa lo mismo que su padre pulsable: el mismo control dos veces. */
	private fun esEnvolturaRepetida(n: AccessibilityNodeInfo): Boolean {
		if (tieneTexto(n) || n.isCheckable || n.isEditable || n.childCount == 0) return false
		val p = runCatching { n.parent }.getOrNull() ?: return false
		if (!accionable(p) || esLista(p)) return false
		val a = limites(n)
		val b = limites(p)
		if (a.isEmpty || b.isEmpty) return false
		return a.width().toLong() * a.height() >= b.width().toLong() * b.height() * 9 / 10
	}

	/**
	 * Si [n] tiene algo que decir (`isSpeakingNode` de TalkBack): texto, estado, se marca, es un encabezado, o lleva
	 * dentro, sin límite de niveles, algo visible que habla y que no es parada por su cuenta.
	 */
	fun habla(n: AccessibilityNodeInfo): Boolean = habla(n, 0)

	private fun habla(n: AccessibilityNodeInfo, nivel: Int): Boolean {
		memoriaHabla[n]?.let { return it }
		// (un encabezado sin texto, como los separadores de categoría de los Ajustes, no dice nada)
		val r = tieneTexto(n) || n.isCheckable || (nivel < PROFUNDIDAD_MAXIMA && hablaDentro(n, nivel))
		memoriaHabla[n] = r
		return r
	}

	private fun hablaDentro(n: AccessibilityNodeInfo, nivel: Int): Boolean {
		for (i in 0 until n.childCount) {
			val h = runCatching { n.getChild(i) }.getOrNull() ?: continue
			if (!h.isVisibleToUser) continue
			if (esCita(h)) return true
			if (tomaFoco(h) || esParadaSuelta(h)) continue
			if (habla(h, nivel + 1)) return true
		}
		return false
	}

	/**
	 * Una fila de una lista que no se pulsa (`isTopLevelScrollItem` de TalkBack): el hijo directo de una lista de verdad
	 * (con colección o de clase de lista). Es una parada si habla, y lee lo de dentro: un mensaje de un chat que no se
	 * pulsa es una parada, no tres (nombre, texto, hora). Un «hijo» que ocupa casi toda la lista no es una fila: es la
	 * página entera dentro de una lista.
	 */
	private fun esFila(n: AccessibilityNodeInfo): Boolean {
		if (n.childCount == 0 || n.isEditable) return false
		val p = runCatching { n.parent }.getOrNull() ?: return false
		if (rol(p) == Rol.DESPLEGABLE) return false
		if (p.collectionInfo == null && !esClaseDeLista(p)) return false
		if (rolChrome(n) != null) return false
		val a = limites(n)
		val b = limites(p)
		return b.height() <= 0 || a.height() < b.height() * 8 / 10
	}

	/** Lo que hay dentro de [n] contado una vez por orden. */
	private fun contenidoGuardado(n: AccessibilityNodeInfo): Dentro? {
		memoriaContenido[n]?.let { return it as? Dentro }
		val d = contenido(n)
		memoriaContenido[n] = d ?: SIN_CONTENIDO
		return d
	}

	/**
	 * Si [p], siendo parada, lee lo de dentro que no es parada: no el marco pulsable de una página entera, ni algo de
	 * una web con mucho texto dentro, ni con el texto de dentro por separado.
	 */
	private fun paradaQueLee(p: AccessibilityNodeInfo): Boolean {
		if (textoDentroAparte) return false
		if (!(tomaFoco(p) || esFila(p))) return false
		if (esVeloDePagina(p) || esMarcoDePagina(p, contenidoGuardado(p)) || contenedorWebLargo(p)) return false
		return true
	}

	/** Si [n] tiene hijos, no se pulsa y mide lo mismo que su ventana (`areBoundsIdenticalToWindow`): no es parada. */
	private fun comoLaVentana(n: AccessibilityNodeInfo): Boolean {
		if (n.childCount == 0 || accionable(n)) return false
		val ventana = runCatching { n.window }.getOrNull() ?: return false
		val v = Rect()
		ventana.getBoundsInScreen(v)
		return limites(n) == v
	}

	/**
	 * Si [n] recibe el foco al navegar: la regla única (`shouldFocusNode` de TalkBack con lo propio de Tiresias). Con
	 * [aunqueNoSeVea], también lo que está fuera de la pantalla (lo de una página web que se ve).
	 */
	fun esParada(n: AccessibilityNodeInfo, aunqueNoSeVea: Boolean = false): Boolean {
		val memoria = if (aunqueNoSeVea) memoriaParadaOculta else memoriaParada
		memoria[n]?.let { return it }
		val r = calcularParada(n, aunqueNoSeVea)
		memoria[n] = r
		return r
	}

	private fun calcularParada(n: AccessibilityNodeInfo, aunqueNoSeVea: Boolean): Boolean {
		if (!aunqueNoSeVea && !n.isVisibleToUser) return false
		if (rol(n) == Rol.WEB && n.childCount > 0) return false
		// Una lista con filas no es una parada por sí misma aunque la app la haga pulsable o mantenible y le ponga
		// nombre (la de los chats de WhatsApp): son sus filas, como en TalkBack
		if (esLista(n) && n.childCount > 0) return false
		// Un velo sin nombre que tapa la página web entera no es un control (CaixaBank)
		if (esVeloDePagina(n)) return false
		// Algo vacío que no se pulsa y que la app no da como importante, o un encabezado sin texto (los separadores de
		// categoría de los Ajustes): no dice nada, no es parada aunque se marque como parada o como encabezado
		val vacio = !accionable(n) && !habla(n) && !esAjustable(n) && (!n.isImportantForAccessibility || esEncabezado(n))
		if (vacio) return false
		// Algo de tamaño cero y sin texto (las filas vacías que separan las categorías de los Ajustes de Android 16): no
		// se ve ni dice nada, aunque se marque como pulsable
		if (!aunqueNoSeVea && !tieneTexto(n) && limites(n).let { it.width() <= 0 || it.height() <= 0 }) return false
		if (Build.VERSION.SDK_INT >= 28 && n.isScreenReaderFocusable) return true
		if (comoLaVentana(n) && !tieneTexto(n)) return false
		val rol = rol(n)
		if (tomaFoco(n) || esFila(n)) {
			val dentro = contenidoGuardado(n)
			// El marco pulsable de una página entera y lo pulsable de una web con mucho texto no son parada si no dicen
			// nada propio: lo son sus textos y sus controles, cada uno el suyo
			if (esMarcoDePagina(n, dentro)) return false
			if (contenedorWebLargo(n) && !tieneTexto(n)) return false
			// Con el texto de dentro por separado: algo que se pulsa y no dice nada por sí mismo (ni texto, ni estado, ni
			// es un encabezado o lo seleccionado) no es parada; lo son sus textos
			if (textoDentroAparte && accionable(n) && !tieneTexto(n) && !n.isCheckable && !n.isEditable && !n.isSelected &&
				!esEncabezado(n) && textoHijos(n, 200).isNotBlank()) return false
			// Sin hijos que se vean, siempre (un botón sin etiqueta se dice «sin nombre», como en TalkBack); algo que solo es
			// enfocable y no dice nada, no
			if (!tieneHijosVisibles(n)) return accionable(n) || tieneTexto(n) || n.isCheckable || esAjustable(n) ||
				(Build.VERSION.SDK_INT >= 28 && n.isScreenReaderFocusable)
			// Con hijos: si habla (por sí o por lo de dentro que no es parada); si no, solo envuelve otros controles
			return habla(n)
		}
		val suelto = tieneTexto(n) || n.isCheckable || rol == Rol.DESLIZADOR || rol == Rol.BARRA_PROGRESO || (esEncabezado(n) && habla(n))
		if (!suelto) return false
		// Texto suelto: no si una parada de encima lo lee como parte de ella
		return lectorDe(n) == null
	}

	private fun tieneHijosVisibles(n: AccessibilityNodeInfo): Boolean {
		for (i in 0 until n.childCount) {
			val h = runCatching { n.getChild(i) }.getOrNull() ?: continue
			if (h.isVisibleToUser) return true
		}
		return false
	}

	/**
	 * La parada que lee a [n]: el primer antepasado que es parada y lee lo de dentro, sin límite de niveles (una lista
	 * corta la subida: no lee a sus filas). Un encabezado sin texto propio (la fila de una categoría de ajustes) lee su
	 * texto de dentro. Null si nadie lo lee: entonces [n], si habla, es su propia parada.
	 */
	fun lectorDe(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
		var p = runCatching { n.parent }.getOrNull()
		var nivel = 0
		while (p != null && nivel < PROFUNDIDAD_MAXIMA) {
			// Una lista no lee a sus filas, aunque la app la haga mantenible: en «Reenviar a» de WhatsApp las filas
			// son casillas que no se pulsan solas, y se leía la lista entera de una vez, encabezados incluidos
			if (esLista(p)) return null
			// (en una web, aunque no se vea: lo de fuera de la pantalla también son paradas, y un enlace de más abajo lee
			// su texto igual que si se viera)
			val aLaVista = p.isVisibleToUser || rolChrome(p) != null
			if (aLaVista) {
				if (Build.VERSION.SDK_INT >= 28 && p.isScreenReaderFocusable) return p
				if (paradaQueLee(p) && esParada(p, !p.isVisibleToUser)) return p
				if (esEncabezado(p) && !tieneTexto(p)) return p
			}
			p = runCatching { p!!.parent }.getOrNull()
			nivel++
		}
		return null
	}

	/** Si [n] recibe el foco al navegar. Es [esParada]: se queda con este nombre porque lo usan el lector y las pruebas. */
	fun recibeFoco(n: AccessibilityNodeInfo, aunqueNoSeVea: Boolean = false): Boolean {
		olvidar(false)
		return esParada(n, aunqueNoSeVea)
	}

	/** Si algún antepasado es una parada que leerá a [n] con ella. */
	fun antepasadoQueLee(n: AccessibilityNodeInfo): Boolean {
		olvidar(false)
		return lectorDe(n) != null
	}

	/**
	 * La parte de [n] que se ve dentro de su ventana, después de pedir que se
	 * muestre (si estaba a medias fuera de la lista). Vacía si no se ve nada.
	 */
	fun visibleEnVentana(n: AccessibilityNodeInfo): android.graphics.Rect {
		if (!n.isVisibleToUser || limites(n).let { r -> r.width() <= 0 || r.height() <= 0 }) {
			runCatching { n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) }
			runCatching { n.refresh() }
		}
		val r = limites(n)
		val ventana = runCatching { n.window }.getOrNull() ?: return r
		val v = android.graphics.Rect()
		ventana.getBoundsInScreen(v)
		return if (r.intersect(v)) r else android.graphics.Rect()
	}

	/**
	 * Una lista, una cuadrícula o algo que se desplaza (no un deslizador). Lo que se pulsa no: un desplegable
	 * (Spinner) se da como lista y es un control. Las listas de WhatsApp se mantienen pulsadas, pero no se pulsan.
	 */
	fun esLista(n: AccessibilityNodeInfo): Boolean =
		(n.collectionInfo != null || (n.isScrollable && n.rangeInfo == null)) &&
			(!n.isClickable || esClaseDeLista(n))

	/**
	 * Una lista de verdad por su clase (ListView, GridView, RecyclerView...), aunque la app la haga pulsable: en
	 * «Reenviar a» de WhatsApp (noviembre de 2026) la lista se pulsa y se mantiene, y se leía entera como una sola
	 * parada que no se desplazaba (Dharius, Cronopio). Un desplegable (Spinner) no lo es: es un control.
	 */
	private fun esClaseDeLista(n: AccessibilityNodeInfo): Boolean {
		val clase = n.className?.toString() ?: return false
		if (clase.endsWith("Spinner")) return false
		return clase.endsWith("ListView") || clase.endsWith("GridView") || clase.endsWith("RecyclerView") ||
			clase == "android.widget.AbsListView"
	}

	/**
	 * El primero de [n] hacia arriba que es parada: el que se lee al tocar (`findFocusFromHover` de TalkBack, con la
	 * misma regla que el recorrido, así que lo tocado siempre está en el orden y el siguiente deslizamiento sigue desde
	 * ahí). Una página web no: lo es lo de dentro.
	 */
	fun enfocable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
		olvidar(false)
		var actual: AccessibilityNodeInfo? = n
		var nivel = 0
		while (actual != null && nivel < PROFUNDIDAD_MAXIMA) {
			if (rol(actual) != Rol.WEB && esParada(actual, rolChrome(actual) != null)) return actual
			actual = runCatching { actual!!.parent }.getOrNull()
			nivel++
		}
		return null
	}

	/** Lo que hay directamente dentro de un control: otros controles y textos sueltos (lo de dentro de esos controles es suyo). */
	private class Dentro(var controles: Int = 0, var textos: Int = 0) {
		val varios: Boolean get() = controles >= 2
	}

	private fun mirarDentro(n: AccessibilityNodeInfo, d: Dentro, nivel: Int) {
		if (nivel > NIVELES_AGRUPAR) return
		for (i in 0 until n.childCount) {
			if (d.varios && d.textos > 0) return
			val h = runCatching { n.getChild(i) }.getOrNull() ?: continue
			if (!h.isVisibleToUser) continue
			// El mensaje citado de una respuesta (WhatsApp) se pulsa para ir al original, pero es parte del mensaje: cuenta
			// como texto. Si contaba como control, la fila «agrupaba controles» y la cita era su propia parada, delante de
			// la respuesta (Cronopio, tras la 1.0.18)
			if (esCita(h)) {
				d.textos++
				continue
			}
			if (accionable(h) || esParadaSuelta(h)) {
				d.controles++
				continue
			}
			if (tieneTexto(h)) d.textos++
			mirarDentro(h, d, nivel + 1)
		}
	}

	/** Lo que hay dentro de [n], si [n] es un control que puede agrupar a otros; null si no lo es. */
	private fun contenido(n: AccessibilityNodeInfo): Dentro? {
		if (!accionable(n) || n.childCount == 0 || n.isEditable || n.isCheckable || n.collectionItemInfo != null) return null
		// La app dice que es una sola parada con todo lo de dentro (mergeDescendants)
		if (Build.VERSION.SDK_INT >= 28 && n.isScreenReaderFocusable) return null
		return Dentro().also { mirarDentro(n, it, 0) }
	}

	/**
	 * Si [n] se pulsa pero dentro tiene varios controles propios: la página y el dock de los escritorios de
	 * EMUI y de los derivados de Launcher3 antiguos, que se pulsan y se mantienen pulsados para editarlos. Cada
	 * control de dentro es su propia parada, como en TalkBack (`shouldFocusNode`): si no, se leía la pantalla
	 * de inicio entera como un solo elemento y no se podía abrir ninguna app deslizando. Una fila de lista no
	 * cuenta: sigue siendo una sola parada aunque tenga dentro varias cosas que se pulsen.
	 */
	fun agrupaControles(n: AccessibilityNodeInfo): Boolean = contenido(n)?.varios == true

	/** Si [n] solo agrupa controles y no tiene nada que decir por sí mismo: no es una parada. */
	fun soloAgrupa(n: AccessibilityNodeInfo): Boolean = sinNadaQueDecir(n, contenido(n))

	/**
	 * Un control que no dice nada por sí mismo y solo envuelve otros controles: él no es una parada, lo son ellos.
	 * También si envuelve uno solo (el marco pulsable de la foto de cada chat de WhatsApp, sin texto, con la foto
	 * dentro: si no, eran dos paradas para lo mismo, una de ellas muda).
	 */
	private fun sinNadaQueDecir(n: AccessibilityNodeInfo, d: Dentro?): Boolean = d != null && d.controles >= 1 && d.textos == 0 && !tieneTexto(n)

	/**
	 * El marco pulsable de una página entera (una web que escucha los toques de toda la página): agrupa varios
	 * controles y ocupa casi toda la pantalla. No es un botón: no es una parada ni lee lo de dentro; lo son sus
	 * controles y sus textos, cada uno el suyo.
	 */
	private fun esMarcoDePagina(n: AccessibilityNodeInfo, d: Dentro?): Boolean {
		if (d == null || tieneTexto(n)) return false
		val m = android.content.res.Resources.getSystem().displayMetrics
		val r = limites(n)
		if (m.heightPixels <= 0 || r.height() < m.heightPixels * PARTE_PAGINA) return false
		if (d.varios) return true
		// En una web, los controles del marco pueden estar mucho más hondos de lo que se mira al agrupar: en la app
		// de CaixaBank (una WebView), el marco pulsable a pantalla completa tiene sus botones y campos diez niveles por
		// debajo. Se tomaba por un botón: el foco se quedaba en él al tocar y al deslizar, sin decir nada útil
		return d.controles == 0 && r.width() >= m.widthPixels * PARTE_ANCHO && enWeb(n) && controlHondo(n)
	}

	/**
	 * Algo de una web que se pulsa, no dice nada, no tiene nada dentro y tapa casi toda la pantalla: un velo que la
	 * web pone encima de la página para escuchar los toques (CaixaBank tiene cuatro). No es una parada ni se enfoca
	 * al explorar: si no, el foco caía en él, mudo, y tapaba lo de debajo.
	 */
	fun esVeloDePagina(n: AccessibilityNodeInfo): Boolean {
		if (n.childCount > 0 || tieneTexto(n) || n.isEditable || n.isCheckable || !accionable(n)) return false
		val m = android.content.res.Resources.getSystem().displayMetrics
		val r = limites(n)
		return m.heightPixels > 0 && r.height() >= m.heightPixels * PARTE_PAGINA && r.width() >= m.widthPixels * PARTE_ANCHO && enWeb(n)
	}

	/** Si [n] está dentro de una página web (su ventana es una WebView o Chrome, que la da como WebView). */
	private fun enWeb(n: AccessibilityNodeInfo): Boolean {
		var p: AccessibilityNodeInfo? = n
		var nivel = 0
		while (p != null && nivel < 40) {
			if (p.className?.toString()?.endsWith("WebView") == true) return true
			p = runCatching { p!!.parent }.getOrNull()
			nivel++
		}
		return false
	}

	/** Si dentro de [n], a cualquier profundidad (con un límite de nodos), hay un control visible. */
	private fun controlHondo(n: AccessibilityNodeInfo): Boolean {
		var vistos = 0
		fun buscar(x: AccessibilityNodeInfo, nivel: Int): Boolean {
			if (nivel > 30) return false
			for (i in 0 until x.childCount) {
				if (++vistos > MAXIMO_NODOS_MARCO) return false
				val h = runCatching { x.getChild(i) }.getOrNull() ?: continue
				if (!h.isVisibleToUser) continue
				if (accionable(h) && !esVeloDePagina(h)) return true
				if (buscar(h, nivel + 1)) return true
			}
			return false
		}
		return buscar(n, 0)
	}

	/**
	 * El texto de los hijos de un contenedor que se lee junto a él (filas de
	 * lista, botones con texto dentro), sin los hijos que reciben el foco por su
	 * cuenta. Como hace TalkBack con la descripción del árbol.
	 */
	fun textoHijos(n: AccessibilityNodeInfo, maximo: Int = 10_000): String {
		olvidar(false)
		val sb = StringBuilder()
		val dichos = HashSet<String>()
		// Los controles de dentro de un contenedor que los agrupa son paradas propias: no se leen con él
		val sinControles = agrupaControles(n)
		// Una fila pegada al borde de su lista puede estar cortada (el título por encima del borde al volver
		// hacia atrás): sus textos que no se ven se leen igual, que son parte de ella
		// (y los de algo que está entero fuera de la vista: la celda de una tabla web más ancha que la pantalla)
		val citas = ArrayList<AccessibilityNodeInfo>()
		val ocultos = aMedias(n) || !n.isVisibleToUser
		// La caja de la fila: lo de dentro que no se ve pero cae dentro de ella está tapado (por la barra de navegación, en
		// las apps de borde a borde), no fuera de la pantalla, y se lee (SandFly e6502d617: si no, la fila se decía a medias)
		val caja = if (n.isVisibleToUser) limites(n) else null
		textoHijos(n, sb, 0, maximo, ocultos, dichos, sinControles, citas, caja)
		// Una fila medio fuera de la pantalla (al final de una lista que aún se está desplazando) tiene
		// sus textos todavía sin ver: antes que «sin nombre», se leen aunque no se vean
		if (sb.isBlank() && n.isVisibleToUser && citas.isEmpty()) textoHijos(n, sb, 0, maximo, true, dichos, sinControles, citas)
		// El mensaje al que se responde, detrás de la respuesta: primero lo que se ha dicho y luego a qué (Cronopio)
		for (c in citas) {
			val cita = StringBuilder()
			textoHijos(c, cita, 1, maximo, ocultos, dichos, sinControles, null)
			if (cita.isBlank()) continue
			if (sb.isNotEmpty()) sb.append(". ")
			sb.append(cita)
		}
		// (sin comas seguidas, que dejan los textos que acaban en coma)
		return sb.toString().replace(Regex("\\s+"), " ").replace(Regex(",(\\s*,)+"), ",").trim().trimEnd(',')
	}

	/**
	 * Si [n] se ve pero está pegado al borde de arriba o de abajo de su lista: puede
	 * estar a medias fuera, con parte de sus textos sin ver.
	 */
	fun aMedias(n: AccessibilityNodeInfo): Boolean {
		if (!n.isVisibleToUser) return false
		val lista = runCatching { n.parent }.getOrNull()?.let { desplazable(it) } ?: return false
		val r = limites(n)
		val l = limites(lista)
		if (r.isEmpty || l.isEmpty) return false
		return r.top <= l.top || r.bottom >= l.bottom
	}

	private fun textoHijos(
		n: AccessibilityNodeInfo, sb: StringBuilder, nivel: Int, maximo: Int, ocultos: Boolean, dichos: MutableSet<String>, sinControles: Boolean,
		citas: MutableList<AccessibilityNodeInfo>?, caja: Rect? = null,
	) {
		if (nivel > PROFUNDIDAD_MAXIMA || sb.length > maximo) return
		for (i in 0 until n.childCount) {
			val hijo = runCatching { n.getChild(i) }.getOrNull() ?: continue
			if (!ocultos && !hijo.isVisibleToUser && (caja == null || !Rect.intersects(caja, limites(hijo)))) continue
			// La cita de un mensaje que responde a otro (WhatsApp) va delante en la fila: se guarda para el final
			if (citas != null && esCita(hijo)) {
				citas.add(hijo)
				continue
			}
			// Lo que es parada por su cuenta (un botón «Borrar» en una fila, un «Me gusta» que se pulsa dentro de una tarjeta
			// que también se pulsa) se lee aparte: con la regla única, cada texto lo lee una sola parada
			if (tomaFoco(hijo) || esParadaSuelta(hijo)) continue
			if (sinControles && accionable(hijo)) continue
			val propio = when {
				hijo.isPassword -> hijo.contentDescription
				!hijo.contentDescription.isNullOrBlank() -> hijo.contentDescription
				else -> hijo.text
			}
			if (!propio.isNullOrBlank()) {
				// El mismo texto otra vez (el título y el deslizador que lo repite como descripción) no se dice dos veces
				// (solo frases con letras: un marcador «2» y «2» sí se dice entero)
				val clave = propio.toString().trim().trimEnd(',').lowercase()
				if (clave.length >= 4 && clave.any { it.isLetter() } && !dichos.add(clave)) continue
				if (sb.isNotEmpty()) sb.append(", ")
				sb.append(propio)
				if (Build.VERSION.SDK_INT >= 30) hijo.stateDescription?.takeIf { it.isNotBlank() }?.let { sb.append(", ").append(it) }
			} else textoHijos(hijo, sb, nivel + 1, maximo, ocultos, dichos, sinControles, citas, caja)
		}
	}

	/** El recuadro del mensaje citado dentro de un mensaje que responde a otro (WhatsApp y WhatsApp Business). */
	private fun esCita(n: AccessibilityNodeInfo): Boolean {
		val id = n.viewIdResourceName ?: return false
		return id.endsWith(":id/quoted_message_holder") && (id.startsWith("com.whatsapp"))
	}

	/**
	 * Una tecla del teclado numérico de una app de teléfono (Teléfono de Google, el de AOSP, Samsung, Xiaomi...) o del PIN
	 * de la pantalla de bloqueo: por su identificador (`key0`…`key9` de SystemUI; `one`…`nine`, `zero`, `star`, `pound`
	 * de los marcadores) o por la clase de la vista. Esas apps las marcan solas al levantar el dedo cuando hay un lector.
	 */
	fun esTeclaNumerica(n: AccessibilityNodeInfo): Boolean {
		val clase = n.className?.toString().orEmpty()
		if (clase.endsWith("DialpadKeyButton") || clase.endsWith("NumPadKey")) return true
		val id = n.viewIdResourceName ?: return false
		val corto = id.substringAfter(":id/", "")
		val paquete = id.substringBefore(":id/").lowercase()
		if (paquete == "com.android.systemui" && TECLA_PIN.matches(corto)) return true
		val deTelefono = paquete.contains("dialer") || paquete.contains("contacts") || paquete.contains("phone") || paquete.contains("incallui")
		return deTelefono && (corto in TECLAS_MARCADOR || TECLA_PIN.matches(corto))
	}

	private val TECLA_PIN = Regex("key[0-9]|key_enter|delete_button")
	private val TECLAS_MARCADOR = setOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "zero", "star", "pound")

	/** «Leer más», «Ver más», «Read more»...: lo que abre el resto de un mensaje o de un texto largo. */
	val LEER_MAS = Regex("(?i)[.…\\s]*(leer|ver|mostrar|read|see|show|lire|mehr|leggi) ?(más|mas|more|la suite|anzeigen|altro)\\.*…?")

	/** Si [n] es un botón (o una vista que se pulsa) que se llama «Leer más» o parecido. */
	fun esBotonLeerMas(n: AccessibilityNodeInfo): Boolean {
		if (!accionable(n)) return false
		val nombre = (n.contentDescription ?: n.text)?.toString()?.trim() ?: return false
		return nombre.length <= 30 && LEER_MAS.matches(nombre)
	}

	/** El botón «Leer más» que hay dentro de [n] (no [n] mismo), si se ve; o null. */
	fun botonLeerMas(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
		var vistos = 0
		fun dentro(p: AccessibilityNodeInfo, nivel: Int): AccessibilityNodeInfo? {
			if (nivel > 6) return null
			for (i in 0 until p.childCount) {
				if (++vistos > 60) return null
				val h = runCatching { p.getChild(i) }.getOrNull() ?: continue
				if (!h.isVisibleToUser) continue
				if (esBotonLeerMas(h)) return h
				dentro(h, nivel + 1)?.let { return it }
			}
			return null
		}
		return dentro(n, 0)
	}

	/** Si [n] tiene un valor que se puede subir y bajar (un deslizador, una barra que se mueve), como TalkBack. */
	fun esAjustable(n: AccessibilityNodeInfo): Boolean {
		if (n.rangeInfo == null) return false
		val ids = n.actionList.map { it.id }
		return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids || AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids ||
			AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id in ids
	}

	/** Si [n] es un selector de rueda (`NumberPicker` y los de Samsung): no tiene valor, pero sube y baja al desplazarlo. */
	fun esSelectorDeRueda(n: AccessibilityNodeInfo): Boolean {
		val clase = n.className?.toString().orEmpty()
		if (!(clase.endsWith("NumberPicker") || clase.endsWith("Picker") && !clase.endsWith("DatePicker") && !clase.endsWith("TimePicker"))) return false
		val ids = n.actionList.map { it.id }
		return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids || AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids
	}

	/**
	 * Lo que se ajusta con arriba y abajo estando el foco en [n]: él mismo si es un deslizador; si es una fila, el
	 * único deslizador de dentro (hasta tres niveles); si está dentro de un selector de rueda (el número de la hora
	 * de una alarma), ese selector. Null si no hay nada que ajustar.
	 */
	fun ajustableDe(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
		if (esAjustable(n) || esSelectorDeRueda(n)) return n
		var encontrado: AccessibilityNodeInfo? = null
		var varios = false
		fun buscar(x: AccessibilityNodeInfo, nivel: Int) {
			if (nivel > 3 || varios) return
			for (i in 0 until x.childCount) {
				val h = runCatching { x.getChild(i) }.getOrNull() ?: continue
				if (!h.isVisibleToUser) continue
				if (esAjustable(h)) {
					if (encontrado != null) { varios = true; return }
					encontrado = h
				} else buscar(h, nivel + 1)
			}
		}
		if (accionable(n) || n.childCount > 0) buscar(n, 1)
		if (!varios && encontrado != null) return encontrado
		var p = runCatching { n.parent }.getOrNull()
		var nivel = 0
		while (p != null && nivel < 3) {
			if (esSelectorDeRueda(p)) return p
			p = runCatching { p!!.parent }.getOrNull()
			nivel++
		}
		return null
	}

	/**
	 * Algo que no se pulsa pero que la app hace enfocable con su propio nombre, dentro de un control: el micrófono
	 * de Telegram es un marco enfocable con «Grabar mensaje de voz» dentro del contenedor de enviar, que se mantiene
	 * pulsado. Es su propia parada, como en TalkBack (para él, enfocable ya es accionable). Si no, la parada era el
	 * contenedor, y el doble toque mantenido caía en su centro, fuera del micrófono: abría «enviar sin sonido» y
	 * «programar».
	 */
	private fun esParadaSuelta(n: AccessibilityNodeInfo): Boolean =
		!accionable(n) && !n.isEditable && n.isVisibleToUser &&
			(n.isFocusable && !n.contentDescription.isNullOrBlank() ||
				// Lo que la app (Compose, con mergeDescendants) hace una parada por sí mismo, con su texto: en «Acerca de
				// esta app» de Play Store cada dato es así, y se leía todo junto con el contenedor (Jesusvz)
				Build.VERSION.SDK_INT >= 28 && n.isScreenReaderFocusable && tieneTexto(n))

	/**
	 * Los nodos que reciben el foco en la ventana de [raiz], en orden de lectura: el del árbol, con lo que pida la app
	 * con `accessibilityTraversalBefore/After` ([reordenarPorRecorrido]).
	 */
	fun ordenLectura(raiz: AccessibilityNodeInfo, maximo: Int = 6000, agrupa: ((AccessibilityNodeInfo) -> Boolean)? = null): List<AccessibilityNodeInfo> {
		val lista = ArrayList<AccessibilityNodeInfo>()
		largosWeb.clear()
		olvidar(true)
		try {
			recorrer(raiz, lista, 0, maximo, agrupa, repartir = true)
			reordenarPorRecorrido(lista)
		} catch (e: Exception) {
			Registro.w(ETIQUETA, "Fallo al recorrer la ventana", e)
		}
		return sinRepetidos(lista)
	}

	/**
	 * Las paradas de dentro de [contenedor], también las que están fuera de la vista (para un `ScrollView`, que tiene en el
	 * árbol todo su contenido aunque no se vea), en orden.
	 */
	fun paradasDe(contenedor: AccessibilityNodeInfo, maximo: Int = 2000): List<AccessibilityNodeInfo> {
		val lista = ArrayList<AccessibilityNodeInfo>()
		olvidar(true)
		try {
			recorrer(contenedor, lista, 0, maximo, null, fueraDeLaVista = true)
		} catch (e: Exception) {
			Registro.w(ETIQUETA, "Fallo al recorrer un contenedor", e)
		}
		return lista
	}

	/**
	 * Aplica el orden que pide la app con `accessibilityTraversalBefore` y `accessibilityTraversalAfter` (lo que usan
	 * Compose para el orden visual y las apps que arreglan su orden de lectura): cada parada que lo pide (con las suyas de
	 * dentro) se mueve justo antes de la parada que nombra, o justo después, como `OrderedTraversalController` de
	 * TalkBack. Antes se ignoraba: la barra de abajo o el botón flotante podían leerse antes que el contenido.
	 */
	private fun reordenarPorRecorrido(lista: MutableList<AccessibilityNodeInfo>) {
		if (Build.VERSION.SDK_INT < 22 || lista.size < 2) return
		var movidas = 0
		for (n in lista.toList()) {
			if (movidas >= 60) break
			val antes = runCatching { n.traversalBefore }.getOrNull()
			val despues = if (antes == null) runCatching { n.traversalAfter }.getOrNull() else null
			val ref = antes ?: despues ?: continue
			if (ref == n || dentroDe(ref, n)) continue
			val i = lista.indexOf(n)
			if (i < 0) continue
			var fin = i + 1
			while (fin < lista.size && dentroDe(lista[fin], n)) fin++
			val bloque = ArrayList(lista.subList(i, fin))
			lista.subList(i, fin).clear()
			val donde = if (antes != null) lista.indexOfFirst { it == ref || dentroDe(it, ref) }
				else lista.indexOfLast { it == ref || dentroDe(it, ref) }.let { if (it >= 0) it + 1 else -1 }
			if (donde < 0) {
				lista.addAll(i, bloque)
				continue
			}
			lista.addAll(donde, bloque)
			movidas++
		}
	}

	/**
	 * Quita la parada que repite a la de al lado cuando una está dentro de la otra y las dos dicen lo mismo: un
	 * marco con la descripción «Transcribir» y, dentro, el botón «Transcribir» (WhatsApp, Lucerito_Stefi). Se queda
	 * la que se pulsa (o la de dentro, si se pulsan las dos).
	 */
	private fun sinRepetidos(lista: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
		if (lista.size < 2) return lista
		val nombres = lista.map { n -> (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase().orEmpty() }
		var quitar: MutableSet<Int>? = null
		for (i in 0 until lista.size - 1) {
			val a = nombres[i]
			if (a.length < 2 || a != nombres[i + 1]) continue
			val fuera = lista[i]
			val dentro = lista[i + 1]
			if (!esAntepasado(fuera, dentro)) continue
			val sobra = if (accionable(fuera) && !accionable(dentro)) i + 1 else i
			(quitar ?: HashSet<Int>().also { quitar = it }).add(sobra)
		}
		val q = quitar ?: return lista
		return lista.filterIndexed { i, _ -> i !in q }
	}

	/** Si [padre] está por encima de [n] (hasta cuatro niveles). */
	private fun esAntepasado(padre: AccessibilityNodeInfo, n: AccessibilityNodeInfo): Boolean {
		var p = runCatching { n.parent }.getOrNull()
		var nivel = 0
		while (p != null && nivel < 4) {
			if (p == padre) return true
			p = runCatching { p!!.parent }.getOrNull()
			nivel++
		}
		return false
	}

	/**
	 * Cuántas ramas a la vez: CAN Talk lee con 8; aquí, tantas como núcleos tenga el móvil, entre 4 y 8 (en uno de
	 * cuatro núcleos, más hilos solo se estorban).
	 */
	private val HILOS_ARBOL = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)

	/**
	 * Hilos para leer en paralelo las ramas de un contenedor con muchos hijos (la
	 * persiana de One UI tardaba de 300 a 600 ms por deslizamiento: Backtalk 229).
	 * Cada pregunta a la app es una llamada entre procesos; varias a la vez van antes.
	 */
	private val repartidor = java.util.concurrent.Executors.newFixedThreadPool(HILOS_ARBOL) { r ->
		Thread(r, "tiresias-arbol").apply { isDaemon = true }
	}
	private const val HIJOS_PARA_REPARTIR = 6
	private const val NIVEL_MAXIMO_REPARTO = 8

	private fun recorrer(
		n: AccessibilityNodeInfo, lista: MutableList<AccessibilityNodeInfo>, nivel: Int, maximo: Int,
		agrupa: ((AccessibilityNodeInfo) -> Boolean)?, repartir: Boolean = false, fueraDeLaVista: Boolean = false, web: Boolean = false,
	) {
		if (nivel > PROFUNDIDAD_MAXIMA || lista.size >= maximo) return
		// Dentro de una página web que se ve, lo que está fuera de la pantalla también es parada, como en TalkBack y
		// SandFly (Chrome lo trae a la vista al enfocarlo): si no, lo siguiente a lo último que se ve era lo que la
		// página deja fijo abajo (el campo de preguntar de un chat de IA, un aviso), y se saltaba el resto del texto
		// (suzuqui, Venice). Solo si la propia vista web se ve: la de una pestaña escondida, no
		val enWeb = web || (n.isVisibleToUser && n.className?.toString()?.endsWith("WebView") == true)
		if (!fueraDeLaVista && !enWeb && !n.isVisibleToUser && n.childCount == 0) return
		// Un elemento que una regla agrupa: una sola parada con todo lo de dentro
		if (agrupa != null && n.isVisibleToUser && agrupa(n)) {
			lista += n
			return
		}
		// La regla única: si es parada, entra; y se baja siempre por todos los hijos, sin tirar ramas (lo de dentro que no
		// es parada lo lee la parada de encima; lo que sí lo es, se recorre aunque esté muy hondo)
		if (esParada(n, fueraDeLaVista || enWeb)) lista += n
		val hijos = (0 until n.childCount).mapNotNull { i -> runCatching { n.getChild(i) }.getOrNull() }
			// Si la cita de una respuesta acaba siendo su propia parada, va detrás de la respuesta, como se lee la fila
			.let { h -> if (h.size > 1 && h.any { esCita(it) }) h.sortedBy { esCita(it) } else h }
		val filaConCeldas = !fueraDeLaVista && !enWeb && hijos.any { it.isVisibleToUser && esCeldaWeb(it) }
		fun fuera(hijo: AccessibilityNodeInfo) = fueraDeLaVista || (filaConCeldas && !hijo.isVisibleToUser && esCeldaWeb(hijo))
		// Muchos hijos (una lista larga, la persiana): cada rama en un hilo, y se juntan en su orden
		if (repartir && hijos.size >= HIJOS_PARA_REPARTIR && nivel <= NIVEL_MAXIMO_REPARTO) {
			val ramas = runCatching {
				repartidor.invokeAll(hijos.map { hijo ->
					java.util.concurrent.Callable {
						val rama = ArrayList<AccessibilityNodeInfo>()
						runCatching { recorrer(hijo, rama, nivel + 1, maximo, agrupa, fueraDeLaVista = fuera(hijo), web = enWeb) }
							.onFailure { Registro.w(ETIQUETA, "Fallo al leer una rama de la ventana", it) }
						rama
					}
				})
			}.getOrNull()
			if (ramas != null) {
				for (r in ramas) {
					lista += runCatching { r.get() }.getOrDefault(emptyList())
					if (lista.size >= maximo) break
				}
				return
			}
		}
		// Las celdas de una fila de una tabla web que se salen de la pantalla por un lado (una tabla más ancha que el
		// móvil): Chrome las da como no visibles, y al deslizar se leían las dos primeras columnas y se saltaba a la
		// fila siguiente (Jesusvz, el foro de la sala). Si la fila se ve, son paradas; al enfocarlas se traen a la vista
		for (hijo in hijos) recorrer(hijo, lista, nivel + 1, maximo, agrupa, repartir, fuera(hijo), enWeb)
	}

	/** El rol HTML que da Chrome (o una WebView) en los extras; null fuera de una web. */
	fun rolChrome(n: AccessibilityNodeInfo): String? =
		runCatching { n.extras?.getCharSequence("AccessibilityNodeInfo.chromeRole")?.toString() }.getOrNull()

	/** Los roles de Chrome que son controles de verdad: solo ellos leen dentro de sí lo que llevan (SandFly WebTreeOrder). */
	/** Las acciones de desplazar que dicen hacia dónde (arriba, abajo, izquierda, derecha). */
	private val DESPLAZAR_CON_DIRECCION = setOf(
		android.R.id.accessibilityActionScrollUp, android.R.id.accessibilityActionScrollDown,
		android.R.id.accessibilityActionScrollLeft, android.R.id.accessibilityActionScrollRight,
	)

	private val ROLES_CONTROL_WEB = setOf(
		"link", "button", "toggleButton", "popUpButton", "checkBox", "radioButton", "switch", "tab", "menuItem",
		"menuItemCheckBox", "menuItemRadio", "menuListOption", "listBoxOption", "disclosureTriangle", "comboBoxMenuButton",
		"comboBoxSelect", "cell", "gridCell", "row", "columnHeader", "rowHeader", "image", "img", "textField", "searchBox",
	)

	/** Lo ya mirado por [contenedorWebLargo] en el orden que se está haciendo. */
	private val largosWeb = java.util.concurrent.ConcurrentHashMap<AccessibilityNodeInfo, Boolean>()

	/**
	 * Algo pulsable de una página web que no es un control (un `div` con `onclick`, la burbuja de una respuesta de
	 * un chat de IA) y lleva dentro mucho texto (más de [TEXTOS_CONTENEDOR_WEB] textos o [LETRAS_CONTENEDOR_WEB]
	 * letras): no lee a sus hijos; cada texto es su propia parada. Si no, se leía todo de golpe y cortado, y el
	 * siguiente deslizamiento saltaba al botón de copiar (suzuqui). Una tarjeta corta (titular y fecha) sí se lee junta.
	 */
	fun contenedorWebLargo(n: AccessibilityNodeInfo): Boolean {
		if (!accionable(n) || n.childCount == 0 || n.isEditable) return false
		val rol = rolChrome(n) ?: return false
		if (rol in ROLES_CONTROL_WEB) return false
		largosWeb[n]?.let { return it }
		var textos = 0
		var letras = 0
		var vistos = 0
		fun contar(x: AccessibilityNodeInfo, nivel: Int): Boolean {
			if (nivel > 12) return false
			for (i in 0 until x.childCount) {
				if (++vistos > MAXIMO_NODOS_MARCO) return false
				val h = runCatching { x.getChild(i) }.getOrNull() ?: continue
				val t = (h.contentDescription ?: h.text)?.toString().orEmpty()
				if (t.isNotBlank() && h.childCount == 0) {
					textos++
					letras += t.length
					if (textos > TEXTOS_CONTENEDOR_WEB || letras > LETRAS_CONTENEDOR_WEB) return true
				}
				if (contar(h, nivel + 1)) return true
			}
			return false
		}
		val largo = contar(n, 0)
		if (largosWeb.size > 500) largosWeb.clear()
		largosWeb[n] = largo
		return largo
	}

	/** La celda de una tabla web en la que está [n] (él mismo o hasta tres niveles por encima), o null. */
	fun celdaWebDe(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
		var p: AccessibilityNodeInfo? = n
		repeat(4) {
			val x = p ?: return null
			if (esCeldaWeb(x)) return x
			p = runCatching { x.parent }.getOrNull()
		}
		return null
	}

	/** Una celda de una tabla de una página web (Chrome da su fila y su columna, y su rol). */
	private fun esCeldaWeb(n: AccessibilityNodeInfo): Boolean =
		n.collectionItemInfo != null && runCatching { n.extras?.getCharSequence("AccessibilityNodeInfo.chromeRole") }.getOrNull() != null

	/** El antepasado desplazable más cercano de [n] (para desplazar la lista en la que está). */
	fun desplazable(n: AccessibilityNodeInfo, alSeguir: Boolean = false): AccessibilityNodeInfo? {
		var p: AccessibilityNodeInfo? = n
		var nivel = 0
		while (p != null && nivel < 20) {
			// Un deslizador «se desplaza» cambiando su valor: no es una lista
			if (p.isScrollable && p.rangeInfo == null && (!alSeguir || seSigueDesplazando(p))) return p
			p = runCatching { p!!.parent }.getOrNull()
			nivel++
		}
		return null
	}

	/**
	 * El antepasado más cercano de [n] que puede desplazarse ahora con [accion]
	 * (hacia delante o hacia atrás), como hace TalkBack. No vale el desplazable más
	 * cercano sin más: la lista de los Ajustes de LineageOS (Android 15) es una
	 * RecyclerView que se declara desplazable pero mide lo que su contenido, y el
	 * que se mueve de verdad es el NestedScrollView que la envuelve. Si se pide el
	 * desplazamiento a la lista, no pasa nada y la navegación se queda clavada al
	 * final de lo que se ve. null si ningún antepasado puede ir hacia ahí.
	 */
	fun desplazableHacia(n: AccessibilityNodeInfo, accion: Int, alSeguir: Boolean = false): AccessibilityNodeInfo? {
		var p: AccessibilityNodeInfo? = n
		var nivel = 0
		while (p != null && nivel < 20) {
			if (p.isScrollable && p.rangeInfo == null && p.actionList.any { it.id == accion } && (!alSeguir || seSigueDesplazando(p))) return p
			p = runCatching { p!!.parent }.getOrNull()
			nivel++
		}
		return null
	}

	/**
	 * El primer nodo desplazable visible de la ventana (si el foco no está en ninguna
	 * lista); con [accion], el primero que pueda desplazarse ahora con ella.
	 */
	fun primerDesplazable(raiz: AccessibilityNodeInfo, accion: Int? = null, nivel: Int = 0, alSeguir: Boolean = false): AccessibilityNodeInfo? {
		if (nivel > 30) return null
		if (raiz.isScrollable && raiz.isVisibleToUser && raiz.rangeInfo == null && (accion == null || raiz.actionList.any { it.id == accion }) &&
			(!alSeguir || seSigueDesplazando(raiz))) return raiz
		for (i in 0 until raiz.childCount) {
			val h = runCatching { raiz.getChild(i) }.getOrNull() ?: continue
			primerDesplazable(h, accion, nivel + 1, alSeguir)?.let { return it }
		}
		return null
	}

	/**
	 * Si [n] es algo que se desplaza para seguir leyendo al llegar al final de lo que se ve: una lista, una cuadrícula,
	 * una página. No un paginador ni sus pestañas (se pasaba a la pestaña siguiente en silencio, en vez de ir a la barra
	 * de abajo), ni un campo de texto, ni un selector de rueda (cambiaría su valor), ni un carrusel que enseña una sola
	 * página cada vez (SandFly d51dc1be8 y b6ef9de0a; TalkBack FILTER_AUTO_SCROLL). Un carrusel de cosas estrechas sí.
	 */
	fun seSigueDesplazando(n: AccessibilityNodeInfo): Boolean {
		if (n.isEditable || esSelectorDeRueda(n)) return false
		// La página web no: lo de fuera de la pantalla ya es parada y Chrome lo trae a la vista al enfocarlo; pasarla por
		// páginas se saltaba lo que aún no estaba cargado (TalkBack FILTER_AUTO_SCROLL_ANY_ROLE)
		if (n.className?.toString()?.endsWith("WebView") == true) return false
		val r = rol(n)
		if (r == Rol.PAGINADOR || r == Rol.LISTA_PESTANAS) return false
		val ids = n.actionList.map { it.id }
		// Una vista sin más («android.view.View») que se desplaza sin decir hacia dónde ni ser una colección: el PageView
		// de Flutter (juegos como Lost Vault), que pasa páginas enteras (SandFly 7ed9f2e97)
		if (n.className?.toString() == "android.view.View" && n.collectionInfo == null &&
			ids.none { it in DESPLAZAR_CON_DIRECCION }) return false
		// Solo de lado (sin arriba ni abajo, y con izquierda o derecha): un carrusel. Si su primer elemento ocupa casi todo
		// el ancho, enseña una página cada vez, como un paginador
		val vertical = android.R.id.accessibilityActionScrollUp in ids || android.R.id.accessibilityActionScrollDown in ids
		val lateral = android.R.id.accessibilityActionScrollLeft in ids || android.R.id.accessibilityActionScrollRight in ids
		val enFila = n.collectionInfo?.let { it.rowCount <= 1 && it.columnCount > 1 } == true
		if (!vertical && (lateral || enFila)) {
			val ancho = limites(n).width()
			val primero = (0 until n.childCount).asSequence().mapNotNull { runCatching { n.getChild(it) }.getOrNull() }.firstOrNull { it.isVisibleToUser }
			if (primero != null && ancho > 0 && limites(primero).width() >= ancho * 9 / 10) return false
		}
		return true
	}

	/**
	 * Los enlaces que hay dentro del texto de [n], con su texto, en orden: los
	 * «Más información» o «Instalar de todas formas» en medio de una frase, que
	 * no son elementos propios (Android los da como ClickableSpan del texto).
	 */
	fun enlacesEnTexto(n: AccessibilityNodeInfo): List<Pair<String, android.text.style.ClickableSpan>> {
		val lista = ArrayList<Pair<String, android.text.style.ClickableSpan>>()
		enlacesDe(n, lista)
		// También los de dentro que no son paradas propias (el texto de un aviso dentro de su fila), como TalkBack
		// (sin recorrer una lista entera: 40 nodos como mucho)
		var vistos = 0
		fun dentro(p: AccessibilityNodeInfo, nivel: Int) {
			if (nivel > 6 || lista.size >= 20) return
			for (i in 0 until p.childCount) {
				if (++vistos > 40) return
				val h = runCatching { p.getChild(i) }.getOrNull() ?: continue
				if (!h.isVisibleToUser || esParadaAparte(h)) continue
				enlacesDe(h, lista)
				dentro(h, nivel + 1)
			}
		}
		dentro(n, 0)
		return lista
	}

	/**
	 * Si [h], dentro de una fila, es una parada por su cuenta (se enfoca o se pulsa): sus enlaces son suyos. Un texto
	 * que solo se pulsa porque lleva enlaces (el de un mensaje de WhatsApp, con su «Leer más») no: se lee con la fila.
	 */
	private fun esParadaAparte(h: AccessibilityNodeInfo): Boolean {
		if (h.isScreenReaderFocusableCompat()) return true
		if (!h.isFocusable && !h.isClickable) return false
		return !(h.className?.toString()?.endsWith("TextView") == true && tieneEnlacesEnTexto(h))
	}

	/** El nodo ([n] o uno de dentro que no es parada propia) cuyo texto lleva el enlace [enlace], o null. */
	fun nodoDeEnlace(n: AccessibilityNodeInfo, enlace: android.text.style.ClickableSpan): AccessibilityNodeInfo? {
		fun lleva(x: AccessibilityNodeInfo): Boolean {
			val texto = (runCatching { x.text }.getOrNull() as? android.text.Spanned)
				?: (runCatching { x.contentDescription }.getOrNull() as? android.text.Spanned) ?: return false
			return texto.getSpans(0, texto.length, android.text.style.ClickableSpan::class.java).any { it === enlace }
		}
		if (lleva(n)) return n
		var vistos = 0
		fun dentro(p: AccessibilityNodeInfo, nivel: Int): AccessibilityNodeInfo? {
			if (nivel > 6) return null
			for (i in 0 until p.childCount) {
				if (++vistos > 40) return null
				val h = runCatching { p.getChild(i) }.getOrNull() ?: continue
				if (!h.isVisibleToUser || esParadaAparte(h)) continue
				if (lleva(h)) return h
				dentro(h, nivel + 1)?.let { return it }
			}
			return null
		}
		return dentro(n, 0)
	}

	/**
	 * El nodo ([n] o uno de dentro que no es parada propia) cuyo texto acaba como dice [final], o null: un «… Leer más»
	 * que la app pinta al final del texto sin hacerlo enlace (los mensajes largos de WhatsApp, noviembre de 2026).
	 */
	fun textoQueAcabaEn(n: AccessibilityNodeInfo, final: Regex): AccessibilityNodeInfo? {
		fun acaba(x: AccessibilityNodeInfo): Boolean = runCatching { x.text }.getOrNull()?.let { final.containsMatchIn(it) } == true
		if (acaba(n)) return n
		var vistos = 0
		fun dentro(p: AccessibilityNodeInfo, nivel: Int): AccessibilityNodeInfo? {
			if (nivel > 6) return null
			for (i in 0 until p.childCount) {
				if (++vistos > 40) return null
				val h = runCatching { p.getChild(i) }.getOrNull() ?: continue
				if (!h.isVisibleToUser || esParadaAparte(h)) continue
				if (acaba(h)) return h
				dentro(h, nivel + 1)?.let { return it }
			}
			return null
		}
		return dentro(n, 0)
	}

	/**
	 * Dónde están en la pantalla las letras de [n] de [inicio] a [inicio] + [largo] (Android 8 o posterior, si la app
	 * lo da: las vistas de texto sí), o null si no se ven o la app no lo dice.
	 */
	fun cajaDeLetras(n: AccessibilityNodeInfo, inicio: Int, largo: Int): android.graphics.RectF? {
		if (Build.VERSION.SDK_INT < 26 || largo <= 0) return null
		val clave = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
		if (clave !in n.availableExtraData) return null
		val datos = android.os.Bundle().apply {
			putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, inicio)
			putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, largo)
		}
		if (!runCatching { n.refreshWithExtraData(clave, datos) }.getOrDefault(false)) return null
		@Suppress("DEPRECATION")
		val cajas = runCatching { n.extras.getParcelableArray(clave) }.getOrNull() ?: return null
		var union: android.graphics.RectF? = null
		for (c in cajas) {
			val r = c as? android.graphics.RectF ?: continue
			if (r.width() <= 0f || r.height() <= 0f) continue
			union = union?.apply { union(r) } ?: android.graphics.RectF(r)
		}
		return union
	}

	private fun AccessibilityNodeInfo.isScreenReaderFocusableCompat(): Boolean = Build.VERSION.SDK_INT >= 28 && isScreenReaderFocusable

	private fun enlacesDe(n: AccessibilityNodeInfo, lista: MutableList<Pair<String, android.text.style.ClickableSpan>>) {
		val texto = (runCatching { n.text }.getOrNull() as? android.text.Spanned)
			?: (runCatching { n.contentDescription }.getOrNull() as? android.text.Spanned) ?: return
		texto.getSpans(0, texto.length, android.text.style.ClickableSpan::class.java)
			.map { s -> Triple(texto.getSpanStart(s), texto.getSpanEnd(s), s) }
			.filter { (a, b, _) -> a >= 0 && b > a }
			.sortedBy { it.first }
			.mapNotNullTo(lista) { (a, b, s) -> texto.subSequence(a, b).toString().trim().takeIf { it.isNotEmpty() }?.let { it to s } }
	}

	/** Si [n] tiene enlaces dentro de su texto. */
	fun tieneEnlacesEnTexto(n: AccessibilityNodeInfo): Boolean {
		val texto = (runCatching { n.text }.getOrNull() as? android.text.Spanned)
			?: (runCatching { n.contentDescription }.getOrNull() as? android.text.Spanned) ?: return false
		return texto.getSpans(0, texto.length, android.text.style.ClickableSpan::class.java).isNotEmpty()
	}

	/** Una falta que marca el corrector en lo escrito: dónde está, la palabra y lo que propone. */
	data class Falta(val inicio: Int, val fin: Int, val palabra: String, val sugerencias: List<String>)

	/**
	 * Las faltas de ortografía (y de gramática, Android 12 o posterior) que el
	 * corrector del teclado ha marcado en lo escrito en [n], en orden: Android las
	 * da como SuggestionSpan del texto, como las ve TalkBack.
	 */
	fun faltas(n: AccessibilityNodeInfo): List<Falta> {
		if (!n.isEditable) return emptyList()
		val texto = runCatching { n.text }.getOrNull() as? android.text.Spanned ?: return emptyList()
		val marcas = android.text.style.SuggestionSpan.FLAG_MISSPELLED or (if (Build.VERSION.SDK_INT >= 31) android.text.style.SuggestionSpan.FLAG_GRAMMAR_ERROR else 0)
		return texto.getSpans(0, texto.length, android.text.style.SuggestionSpan::class.java)
			.filter { it.flags and marcas != 0 }
			.map { s -> Triple(texto.getSpanStart(s), texto.getSpanEnd(s), s) }
			.filter { (a, b, _) -> a >= 0 && b > a && b <= texto.length }
			.sortedBy { it.first }
			.distinctBy { it.first }
			.map { (a, b, s) -> Falta(a, b, texto.subSequence(a, b).toString(), s.suggestions.orEmpty().filter { it.isNotBlank() }.distinct().take(3)) }
	}

	/** Las listas desplazables que se ven en la ventana, de fuera a dentro (los deslizadores no). */
	fun desplazables(raiz: AccessibilityNodeInfo, nivel: Int = 0, lista: MutableList<AccessibilityNodeInfo> = ArrayList()): List<AccessibilityNodeInfo> {
		if (nivel > 30 || lista.size >= 8) return lista
		if (raiz.isScrollable && raiz.isVisibleToUser && raiz.rangeInfo == null) lista += raiz
		for (i in 0 until raiz.childCount) {
			val h = runCatching { raiz.getChild(i) }.getOrNull() ?: continue
			desplazables(h, nivel + 1, lista)
		}
		return lista
	}

	/** El rectángulo de [n] en la pantalla. */
	fun limites(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

	/** Si [n] todavía existe en su ventana (refresh devuelve false si la app lo ha quitado). */
	fun vivo(n: AccessibilityNodeInfo): Boolean = runCatching { n.refresh() }.getOrDefault(false)

	/**
	 * Un botón que se usa manteniéndolo pulsado: grabar una nota de voz en WhatsApp
	 * o Telegram, hablar en un walkie. Ahí el doble toque mantenido tiene que dejarlo
	 * pulsado de verdad hasta que se levanta el dedo (una pulsación larga no basta:
	 * la app graba medio segundo y lo descarta). Se reconoce por su identificador o
	 * por su nombre.
	 */
	fun esDeMantenerPulsado(n: AccessibilityNodeInfo): Boolean {
		// (el de WhatsApp no se dice pulsable ni de pulsación larga: solo trae la acción de pulsación larga; el de
		// Telegram ni eso: es un marco enfocable dentro de un contenedor que se mantiene pulsado)
		if (!accionable(n) && !(esParadaSuelta(n) && padreMantenible(n))) return false
		// Una fila de una lista no es un botón de grabar: cambiar a este modo rehace la exploración táctil de todo
		// Android, y al recorrer los chats de Telegram (cuyas filas acaban en «Mensaje de voz») se cortaba a cada fila
		if (n.collectionItemInfo != null) return false
		val id = n.viewIdResourceName?.substringAfter(":id/", "")?.lowercase().orEmpty()
		if (id.isNotEmpty() && ID_MANTENER.containsMatchIn(id)) return true
		val nombre = (n.contentDescription ?: n.text)?.toString().orEmpty()
		return nombre.length <= 60 && NOMBRE_MANTENER.matches(nombre)
	}

	/** Si el padre o el abuelo de [n] se mantienen pulsados (el contenedor de enviar de Telegram). */
	private fun padreMantenible(n: AccessibilityNodeInfo): Boolean {
		var p = runCatching { n.parent }.getOrNull()
		for (nivel in 0 until 2) {
			val x = p ?: return false
			if (x.isLongClickable || x.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }) return true
			p = runCatching { x.parent }.getOrNull()
		}
		return false
	}

	private val ID_MANTENER = Regex("(^|_)(voice_?note|ptt|record\\w*|mic|microphone|video_?note)(_|$)")
	/** El nombre entero del botón: «Mensaje de voz» dentro de un texto más largo es de lo que habla ese texto, no el botón. */
	private val NOMBRE_MANTENER = Regex(
		// (Telegram cambia el botón entre «Mensaje de voz» y «Mensaje de vídeo» con un toque: los dos se mantienen pulsados)
		"(?i)\\s*(((grabar|enviar|mandar|record|send)\\s+((un|una|a)\\s+)?)?(mensajes? de (voz|v[ií]deo)|v[ií]deo ?mensajes?|notas? de voz|(voice|video) (message|note)s?)|" +
			"grabar audio|grabar voz|grabación de voz|mantén pulsado para (grabar|enviar)( \\S+){0,4}|hold to (record|send)( \\S+){0,4}|" +
			"record (audio|voice)|push to talk|pulsa para hablar)\\s*[.…]?\\s*",
	)

	/**
	 * Cómo está hecha la ventana de [raiz], para contar un fallo de una app que no se puede probar: cada nodo con
	 * su clase, su identificador, si se pulsa, si es encabezado, su posición en la lista, dónde está y si es una de
	 * las [paradas] de Tiresias o el [foco]. **Sin ningún texto de la pantalla**: de los textos y las descripciones
	 * solo se dice si los hay. Como mucho [maximo] nodos. Devuelve el texto y cuántos nodos lleva.
	 */
	fun estructura(raiz: AccessibilityNodeInfo, paradas: Set<AccessibilityNodeInfo>, foco: AccessibilityNodeInfo?, maximo: Int): Pair<String, Int> {
		val sb = StringBuilder()
		sb.append(raiz.packageName).append('\n')
		var nodos = 0
		fun recorrer(n: AccessibilityNodeInfo, nivel: Int) {
			if (nodos >= maximo || nivel > 40) return
			nodos++
			sb.append("  ".repeat(nivel)).append(n.className?.toString()?.substringAfterLast('.') ?: "?")
			n.viewIdResourceName?.substringAfter(":id/", "")?.takeIf { it.isNotEmpty() }?.let { sb.append(" #").append(it) }
			val marcas = buildList {
				if (n == foco) add("FOCO")
				if (n in paradas) add("parada")
				if (!n.isVisibleToUser) add("oculto")
				if (n.isClickable) add("pulsable")
				if (n.isLongClickable) add("mantenible")
				if (n.isFocusable) add("enfocable")
				if (Build.VERSION.SDK_INT >= 28 && n.isScreenReaderFocusable) add("unaParada")
				if (esEncabezado(n)) add("encabezado")
				if (n.isCheckable) add(if (n.isChecked) "marcado" else "marcable")
				if (n.isEditable) add("editable")
				if (n.isScrollable) add("desplazable")
				if (n.isPassword) add("contraseña")
				if (!n.text.isNullOrEmpty()) add("texto")
				if (!n.contentDescription.isNullOrEmpty()) add("descripción")
				n.collectionInfo?.let { add("lista ${it.rowCount}x${it.columnCount}") }
				n.collectionItemInfo?.let { add("fila ${it.rowIndex},${it.columnIndex}") }
				if (esDeMantenerPulsado(n)) add("deMantener")
			}
			sb.append(" [").append(marcas.joinToString(" ")).append("] ").append(limites(n).toShortString()).append('\n')
			for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { recorrer(it, nivel + 1) }
		}
		recorrer(raiz, 0)
		return sb.toString() to nodos
	}

	/**
	 * Un nombre que no dice nada, que pone la app cuando no tiene el de verdad: «Task no name» o «Tarea null» en
	 * Recientes del escritorio de los Infinix, Tecno e itel (XOS), «unknown», «sin nombre».
	 */
	fun esNombreDeRelleno(nombre: String): Boolean = nombre.isNotEmpty() && NOMBRE_RELLENO.matches(nombre)

	private val NOMBRE_RELLENO = Regex("""(?i)\s*((task|tarea|app|aplicaci[oó]n)[\s:,.-]*)?(no[\s_-]?name|null|unknown|unnamed|sin nombre|desconocid[ao])\s*\.?\s*""")

	/** Una contraseña o lo que la app marca como datos sensibles (Android 14). */
	fun sensible(n: AccessibilityNodeInfo): Boolean =
		n.isPassword || (android.os.Build.VERSION.SDK_INT >= 34 && runCatching { n.isAccessibilityDataSensitive }.getOrDefault(false))

	/**
	 * Si en [raiz] (hasta [maximo] nodos) hay algo que la app marca como datos
	 * sensibles (Android 14): entonces la pantalla no se captura para la IA ni el
	 * OCR. Un campo de contraseña visible no basta (sale tapado con puntos): si no,
	 * no se podría describir ninguna pantalla de inicio de sesión.
	 */
	fun haySensibles(raiz: AccessibilityNodeInfo?, maximo: Int = 600): Boolean {
		if (android.os.Build.VERSION.SDK_INT < 34) return false
		raiz ?: return false
		val cola = ArrayDeque<AccessibilityNodeInfo>()
		cola.add(raiz)
		var vistos = 0
		while (cola.isNotEmpty() && vistos++ < maximo) {
			val n = cola.removeFirst()
			if (runCatching { n.isAccessibilityDataSensitive }.getOrDefault(false) && n.isVisibleToUser) return true
			for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { cola.add(it) }
		}
		return false
	}
}
