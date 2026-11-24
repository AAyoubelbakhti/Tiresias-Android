package com.xebolax.tiresias.arbol

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.xebolax.tiresias.nucleo.Elemento
import com.xebolax.tiresias.nucleo.Expansion
import com.xebolax.tiresias.nucleo.Marcado
import com.xebolax.tiresias.nucleo.Posicion
import com.xebolax.tiresias.nucleo.Rango
import com.xebolax.tiresias.nucleo.Rol
import com.xebolax.tiresias.nucleo.Roles

/**
 * Convierte un nodo de accesibilidad en el [Elemento] que dice Tiresias.
 *
 * Usa las propiedades nuevas de cada versión cuando existen (triestado,
 * expandido, obligatorio y descripción complementaria de Android 16;
 * descripción de estado de Android 11; encabezados de Android 9...) y, antes,
 * las que AndroidX deja en los extras del nodo (las apps hechas con Compose o
 * con `AccessibilityNodeInfoCompat`); si no hay, lo deduce.
 *
 * [etiqueta] da la etiqueta que el usuario puso a un elemento (por paquete e
 * identificador), si la hay.
 */
class Describir(private val etiqueta: (paquete: String, id: String) -> String? = { _, _ -> null }) {

	fun elemento(n: AccessibilityNodeInfo): Elemento {
		val acciones = n.actionList
		val pulsable = n.isClickable || acciones.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
		val mantenible = n.isLongClickable || acciones.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }
		// Una fila pulsable con su interruptor o casilla dentro (los ajustes de Android): se dice como ese control
		val interior = if (!n.isCheckable && pulsable) Nodos.marcableInterior(n) else null
		val textoPropio = n.text?.toString().orEmpty()
		val descripcionPropia = n.contentDescription?.toString()?.trim().orEmpty()
		val rol = interior?.let { Nodos.rol(it) } ?: rolCorregido(n, Nodos.rol(n), textoPropio, descripcionPropia)
		// Un campo de texto que no se puede escribir (el desplegable expuesto de Material, el de una fecha que abre un
		// calendario): «solo lectura», sin la pista de escribir (TalkBack, `getReadOnlyStateText`)
		val soloLectura = rol == Rol.CAMPO_TEXTO && n.isEnabled && !n.isEditable
		val campo = n.isEditable || rol == Rol.CAMPO_TEXTO
		val editable = campo && !soloLectura
		val id = n.viewIdResourceName
		val paquete = n.packageName?.toString().orEmpty()
		// Un campo vacío que enseña su pista da la pista como texto (Android 8+ lo marca): no es lo escrito
		val texto = if (campo && Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) "" else textoPropio
		// Una descripción de relleno («Task no name» en Recientes de los Infinix): si dentro hay algo mejor, eso
		val descripcion = descripcionPropia.let { d ->
			if (Nodos.esNombreDeRelleno(d) && (texto.isNotBlank() || Nodos.textoHijos(n).isNotBlank())) "" else d
		}

		val complementaria = complementaria(n)
		val tooltip = tooltip(n)
		// Las vistas que lo etiquetan (`labelFor`, `labeledBy`): en cualquier elemento, no solo en los campos (TalkBack,
		// `treeDescriptionWithLabel`). Un deslizador o un interruptor sin nombre con su texto «Volumen» al lado
		val etiquetas = etiquetas(n)
		val nombreBase = when {
			campo -> etiquetaCampo(n, descripcion, etiquetas)
			descripcion.isNotEmpty() -> descripcion
			texto.isNotBlank() -> texto.trim()
			// La descripción complementaria ya describe lo de dentro: no se leen los hijos (si no, se oía dos veces el
			// artículo web nombrado con `aria-labelledby`; TalkBack `TreeNodesDescription`, SandFly 70c1288ad)
			complementaria != null -> ""
			else -> Nodos.textoHijos(n)
		}
		val nombre = when {
			campo || etiquetas.isEmpty() -> nombreBase
			nombreBase.isBlank() -> etiquetas
			nombreBase.contains(etiquetas, ignoreCase = true) -> nombreBase
			else -> "$etiquetas, $nombreBase"
		}.ifBlank { complementaria.orEmpty() }.ifBlank { tooltip.orEmpty() }
		val web = Nodos.rolChrome(n) != null

		return Elemento(
			nombre = nombre,
			texto = if (campo) texto else "",
			rol = rol,
			descripcionRol = descripcionRol(n, rol),
			estado = estadoPropio(interior ?: n),
			marcado = marcado(interior ?: n),
			seleccionado = n.isSelected,
			// Antes de Android 12, «no disponible» solo en lo que se usa (TalkBack, `announceDisabled`): hay textos
			// desactivados sin que signifique nada
			activo = n.isEnabled || (Build.VERSION.SDK_INT < 31 && !pulsable && !mantenible && !campo && !web),
			expansion = expansion(n, acciones),
			obligatorio = obligatorio(n),
			error = if (n.isContentInvalid) n.error?.toString() else null,
			encabezado = Nodos.esEncabezado(n) || encabezadoCompat(n),
			nivelEncabezado = Nodos.nivelEncabezado(n),
			pista = pista(n, campo, texto),
			pistaNodo = pistaNodo(n, campo),
			complementaria = complementaria,
			tooltip = tooltip,
			rango = rango(n),
			posicion = posicion(n),
			pulsable = pulsable,
			mantenible = mantenible,
			editable = editable,
			soloLectura = soloLectura,
			contrasena = n.isPassword,
			sensible = Nodos.sensible(n),
			tieneAcciones = accionesPropias(acciones) > 0,
			enlaces = Nodos.enlacesEnTexto(n).size,
			faltas = Nodos.faltas(n).size,
			// «Editando» solo con el teclado a la vista (TalkBack, `EditTextDescription`): con el teclado escondido no se escribe
			escribiendo = editable && n.isFocused && tecladoVisible(),
			identificador = id,
			etiquetaPersonal = etiquetaPersonal(n, id, paquete, nombre, campo),
		)
	}

	/**
	 * El rol de [n] para decirlo, con lo que la regla por clase de [Nodos.rol] no distingue ([Roles.paraDecir]: el
	 * «botón» de más de lo que solo se pulsa, `CompoundButton`, `CheckedTextView` de una sola opción, rangos sin
	 * clase) y los roles web que [Nodos.rolWeb] no conoce (árbol, opciones de un menú desplegable).
	 */
	private fun rolCorregido(n: AccessibilityNodeInfo, rol: Rol, texto: String, descripcion: String): Rol {
		val rolWeb = Nodos.rolWeb(n)
		if (rolWeb == null) Nodos.rolChrome(n)?.let { ROLES_WEB_MAS[it] }?.let { return it }
		return Roles.paraDecir(
			rol = rol,
			clase = n.className?.toString().orEmpty(),
			conNombre = texto.isNotBlank() || descripcion.isNotBlank(),
			web = rolWeb != null,
			conRango = rangoValido(n),
			poneValor = n.actionList.any { it.id == AccessibilityAction.ACTION_SET_PROGRESS.id },
			unaSolaOpcion = { unaSolaOpcion(n) },
		)
	}

	/** Si la lista de [n] solo deja elegir una opción (un diálogo de `setSingleChoiceItems`). */
	private fun unaSolaOpcion(n: AccessibilityNodeInfo): Boolean =
		runCatching { coleccionDe(n)?.collectionInfo?.selectionMode }.getOrNull() == AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE

	/** Un rango con sentido: más de un valor posible, o en por ciento. */
	private fun rangoValido(n: AccessibilityNodeInfo): Boolean {
		val r = n.rangeInfo ?: return false
		return r.max > r.min || r.type == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT
	}

	/**
	 * El texto de las vistas que etiquetan a [n] (todas desde Android 16), sin
	 * repetir: de cada una, su texto, su descripción o lo que lleva dentro. Vacío si no tiene.
	 */
	private fun etiquetas(n: AccessibilityNodeInfo): String {
		val lista = if (Build.VERSION.SDK_INT >= 36) runCatching { n.labeledByList }.getOrNull().orEmpty()
		else listOfNotNull(runCatching { n.labeledBy }.getOrNull())
		if (lista.isEmpty()) return ""
		return lista.take(MAXIMO_ETIQUETAS).mapNotNull { e ->
			e.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
				?: e.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
				?: runCatching { Nodos.textoHijos(e, 200) }.getOrNull()?.takeIf { it.isNotBlank() }
		}.distinctBy { it.lowercase() }.joinToString(", ")
	}

	/**
	 * La etiqueta que el usuario puso a [n], si la hay y vale para él (SandFly 9e69895d9, `canReplaceText`): sustituye
	 * a su nombre solo si el identificador es de ese elemento solo (no es un campo, no es de Android, no está en una
	 * fila de una lista y no hay otro con él en la ventana). Si no, solo cuando no tiene nombre: etiquetar el título de
	 * una fila no renombra todas las filas.
	 */
	private fun etiquetaPersonal(n: AccessibilityNodeInfo, id: String?, paquete: String, nombre: String, campo: Boolean): String? {
		if (id == null) return null
		val propia = etiqueta(paquete, id)?.takeIf { it.isNotBlank() } ?: return null
		if (nombre.isBlank()) return propia
		return if (puedeSustituir(n, id, campo)) propia else null
	}

	private fun puedeSustituir(n: AccessibilityNodeInfo, id: String, campo: Boolean): Boolean {
		if (campo || n.isEditable || id.startsWith("android:")) return false
		if (n.collectionItemInfo != null) return false
		// Ni dentro de una fila de una lista; de paso se llega a la raíz para buscar el identificador
		var raiz = n
		var p = runCatching { n.parent }.getOrNull()
		var nivel = 0
		while (p != null && nivel++ < PROFUNDIDAD_RAIZ) {
			if (p.collectionItemInfo != null) return false
			raiz = p
			p = runCatching { p!!.parent }.getOrNull()
		}
		return runCatching { raiz.findAccessibilityNodeInfosByViewId(id).size <= 1 }.getOrDefault(false)
	}

	/** Si se ve el teclado en pantalla (si no se puede saber, sí). */
	private fun tecladoVisible(): Boolean = runCatching {
		com.xebolax.tiresias.servicio.ServicioTiresias.instancia?.windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
	}.getOrNull() ?: true

	/**
	 * La descripción de estado que da la app, salvo la que pone Android solo en
	 * las casillas, interruptores y opciones estándar («Checked», «On», «Not
	 * selected»...): esa viene en el idioma del sistema, que puede no ser el de
	 * Tiresias, y Tiresias ya dice el estado en el suyo.
	 */
	private fun estadoPropio(n: AccessibilityNodeInfo): String? {
		val e = (if (Build.VERSION.SDK_INT >= 30) n.stateDescription else extra(n, CLAVE_ESTADO))?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		if (n.isCheckable && esEstadoDelSistema(e)) return null
		return e
	}

	/** La etiqueta de un campo: las vistas que lo etiquetan, su descripción o su pista. */
	private fun etiquetaCampo(n: AccessibilityNodeInfo, descripcion: String, etiquetas: String): String {
		if (etiquetas.isNotEmpty()) return etiquetas
		if (descripcion.isNotEmpty()) return descripcion
		return pistaDe(n).orEmpty()
	}

	/** La pista de [n]: la de Android, la de una web (en los extras) o la de AndroidX antes de Android 8. */
	private fun pistaDe(n: AccessibilityNodeInfo): String? {
		val propia = if (Build.VERSION.SDK_INT >= 26) n.hintText else extra(n, CLAVE_PISTA)
		return (propia?.takeIf { it.isNotBlank() } ?: extra(n, CLAVE_PISTA_WEB))?.toString()?.trim()?.takeIf { it.isNotEmpty() }
	}

	private fun pista(n: AccessibilityNodeInfo, campo: Boolean, texto: String): String? {
		// En un campo vacío, la pista es lo que hay que escribir; si ya hay texto, no se dice. La de lo que no es un
		// campo va en [pistaNodo]
		return if (campo && texto.isBlank()) pistaDe(n) else null
	}

	/**
	 * La pista de lo que no es un campo (el `title` o la `aria-description` de una
	 * web): se dice con él, como TalkBack (`getHintDescription`), salvo si ya la
	 * enseña como su texto.
	 */
	private fun pistaNodo(n: AccessibilityNodeInfo, campo: Boolean): String? {
		if (campo) return null
		if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return null
		return pistaDe(n)
	}

	/** La descripción complementaria (Android 16, o la de AndroidX en los extras). */
	private fun complementaria(n: AccessibilityNodeInfo): String? {
		if (Build.VERSION.SDK_INT >= 36) n.supplementalDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
		// Antes de Android 16, AndroidX (y Chrome 156, que nombra así las casillas por su <label>) la deja en los extras (Backtalk)
		return extra(n, CLAVE_COMPLEMENTARIA)?.toString()?.takeIf { it.isNotBlank() }
	}

	/** La información sobre herramientas (Android 9, o la de AndroidX en los extras antes). */
	private fun tooltip(n: AccessibilityNodeInfo): String? {
		val t = if (Build.VERSION.SDK_INT >= 28) n.tooltipText else extra(n, CLAVE_TOOLTIP)
		return t?.toString()?.trim()?.takeIf { it.isNotEmpty() }
	}

	private fun obligatorio(n: AccessibilityNodeInfo): Boolean =
		if (Build.VERSION.SDK_INT >= 36) n.isFieldRequired
		else runCatching { n.extras?.getBoolean(CLAVE_OBLIGATORIO) }.getOrNull() == true

	/** Encabezado marcado con AndroidX antes de Android 9 (en Android 9 o posterior ya lo da [Nodos.esEncabezado]). */
	private fun encabezadoCompat(n: AccessibilityNodeInfo): Boolean {
		if (Build.VERSION.SDK_INT >= 28) return false
		val propiedades = runCatching { n.extras?.getInt(CLAVE_PROPIEDADES, 0) }.getOrNull() ?: 0
		return propiedades and PROPIEDAD_ENCABEZADO != 0
	}

	private fun extra(n: AccessibilityNodeInfo, clave: String): CharSequence? = runCatching { n.extras?.getCharSequence(clave) }.getOrNull()

	private fun descripcionRol(n: AccessibilityNodeInfo, rol: Rol): String? {
		val extras = runCatching { n.extras }.getOrNull() ?: return null
		// La de AndroidX (ViewCompat.setAccessibilityDelegate con roleDescription) y la de Chrome
		val propia = extras.getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString()
		// Chrome pone a todo su nombre de rol («link», «button») en el idioma del móvil: si Tiresias
		// conoce el rol, lo dice él, en el idioma del usuario (las descripciones propias de la web se mantienen)
		if (extras.containsKey("AccessibilityNodeInfo.chromeRole") && rol !in setOf(Rol.NINGUNO, Rol.TEXTO)) {
			val chrome = extras.getCharSequence("AccessibilityNodeInfo.chromeRole")?.toString().orEmpty()
			if (chrome in ROLES_CHROME) return null
		}
		// Firefox y los navegadores hechos con él (Tor Browser, Fennec) dan a lo de dentro de la página un rol en inglés
		// aunque el móvil esté en español: «paragraph» al final de cada párrafo (Ideafix). Los de solo estructura no
		// dicen nada útil; los de controles, Tiresias ya los dice en el idioma del usuario
		if (propia != null) {
			val minusculas = propia.trim().lowercase()
			if (minusculas in ROLES_ESTRUCTURA) return null
			if (minusculas in ROLES_EN_INGLES && rol !in setOf(Rol.NINGUNO, Rol.TEXTO)) return null
		}
		return propia?.takeIf { it.isNotBlank() }
	}

	private fun marcado(n: AccessibilityNodeInfo): Marcado {
		if (!n.isCheckable) return Marcado.NO_MARCABLE
		if (Build.VERSION.SDK_INT >= 36) {
			return when (n.checked) {
				AccessibilityNodeInfo.CHECKED_STATE_TRUE -> Marcado.MARCADO
				AccessibilityNodeInfo.CHECKED_STATE_PARTIAL -> Marcado.PARCIAL
				else -> Marcado.NO_MARCADO
			}
		}
		// El triestado de AndroidX antes de Android 16
		val compat = runCatching { n.extras?.takeIf { it.containsKey(CLAVE_MARCADO) }?.getInt(CLAVE_MARCADO) }.getOrNull()
		if (compat == ESTADO_PARCIAL) return Marcado.PARCIAL
		@Suppress("DEPRECATION")
		return if (n.isChecked) Marcado.MARCADO else Marcado.NO_MARCADO
	}

	private fun expansion(n: AccessibilityNodeInfo, acciones: List<AccessibilityAction>): Expansion {
		val explicito = if (Build.VERSION.SDK_INT >= 36) n.expandedState else runCatching { n.extras?.getInt(CLAVE_EXPANDIDO, 0) }.getOrNull() ?: 0
		val r = when (explicito) {
			EXPANDIDO_COMPLETO -> Expansion.EXPANDIDO
			EXPANDIDO_CONTRAIDO -> Expansion.CONTRAIDO
			EXPANDIDO_PARCIAL -> Expansion.EXPANDIDO_PARCIAL
			else -> {
				val expandir = acciones.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND }
				val contraer = acciones.any { it.id == AccessibilityNodeInfo.ACTION_COLLAPSE }
				when {
					contraer && !expandir -> Expansion.EXPANDIDO
					expandir && !contraer -> Expansion.CONTRAIDO
					else -> Expansion.NINGUNA
				}
			}
		}
		// Casi todas las notificaciones de la persiana empiezan contraídas: decirlo en cada una es ruido (SandFly a88a4132a)
		if (r == Expansion.CONTRAIDO && esNotificacion(n)) return Expansion.NINGUNA
		return r
	}

	/** Si [n] es (o está dentro de) una notificación de la persiana. */
	private fun esNotificacion(n: AccessibilityNodeInfo): Boolean {
		if (n.packageName?.toString() != "com.android.systemui") return false
		var x: AccessibilityNodeInfo? = n
		var nivel = 0
		while (x != null && nivel++ <= 3) {
			if (x.viewIdResourceName in IDS_NOTIFICACION) return true
			x = runCatching { x!!.parent }.getOrNull()
		}
		return false
	}

	private fun rango(n: AccessibilityNodeInfo): Rango? {
		val r = n.rangeInfo ?: return null
		val clase = n.className?.toString().orEmpty()
		// Una barra de progreso sin valor (girando): «cargando»
		val indeterminado = r.max <= r.min && clase.endsWith("ProgressBar")
		return Rango(r.min, r.max, r.current, r.type, n.text?.toString()?.takeIf { it.isNotBlank() }, indeterminado)
	}

	private fun posicion(n: AccessibilityNodeInfo): Posicion? {
		// Un enlace o un texto dentro de una celda de una tabla web: la fila y la columna son las de su celda (TalkBack
		// CollectionState). Fuera de una web no: un botón dentro de una fila de una lista no repite su posición
		val celda = if (n.collectionItemInfo == null && Nodos.rolChrome(n) != null) Nodos.celdaWebDe(n) else null
		val item = n.collectionItemInfo ?: celda?.collectionItemInfo ?: return null
		val coleccion = coleccionDe(celda ?: n)?.collectionInfo
		// -1 es «no se sabe» (una lista de Compose, un ChipGroup de varias líneas): sin «5 de 1» ni «1 de 2»
		val filas = coleccion?.rowCount ?: 0
		val columnas = coleccion?.columnCount ?: 0
		val encabezadoFila = if (Build.VERSION.SDK_INT >= 33) item.rowTitle else null
		val encabezadoColumna = if (Build.VERSION.SDK_INT >= 33) item.columnTitle else null
		// Una celda de encabezado de una tabla (web, o de una tabla de verdad): «encabezado de columna»
		val rolCelda = Nodos.rolChrome(celda ?: n)
		@Suppress("DEPRECATION")
		val esEncabezado = rolCelda == "columnHeader" || rolCelda == "rowHeader" || (item.isHeading && filas > 1 && columnas > 1)
		return Posicion.enColeccion(
			filas = filas,
			columnas = columnas,
			indiceFila = item.rowIndex,
			indiceColumna = item.columnIndex,
			encabezadoFila = encabezadoFila,
			encabezadoColumna = encabezadoColumna,
			esEncabezado = esEncabezado,
			esEncabezadoFila = rolCelda == "rowHeader",
		)
	}

	/** La colección (lista, cuadrícula) que contiene a [n], hasta tres niveles arriba. */
	fun coleccionDe(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
		var p = runCatching { n.parent }.getOrNull()
		var nivel = 0
		while (p != null && nivel < 4) {
			if (p.collectionInfo != null) return p
			p = runCatching { p!!.parent }.getOrNull()
			nivel++
		}
		return null
	}

	companion object {
		/** Donde AndroidX guarda lo de las versiones nuevas en las anteriores (`AccessibilityNodeInfoCompat`, androidx.core 1.19). */
		private const val CLAVE_COMPAT = "androidx.view.accessibility.AccessibilityNodeInfoCompat."
		private const val CLAVE_COMPLEMENTARIA = CLAVE_COMPAT + "SUPPLEMENTAL_DESCRIPTION_KEY"
		private const val CLAVE_OBLIGATORIO = CLAVE_COMPAT + "IS_REQUIRED_KEY"
		private const val CLAVE_EXPANDIDO = CLAVE_COMPAT + "EXPANDED_STATE_KEY"
		private const val CLAVE_MARCADO = CLAVE_COMPAT + "CHECKED_KEY"
		private const val CLAVE_ESTADO = CLAVE_COMPAT + "STATE_DESCRIPTION_KEY"
		private const val CLAVE_TOOLTIP = CLAVE_COMPAT + "TOOLTIP_TEXT_KEY"
		private const val CLAVE_PISTA = CLAVE_COMPAT + "HINT_TEXT_KEY"
		private const val CLAVE_PROPIEDADES = CLAVE_COMPAT + "BOOLEAN_PROPERTY_KEY"
		/** El bit de encabezado en [CLAVE_PROPIEDADES] (`BOOLEAN_PROPERTY_IS_HEADING`). */
		private const val PROPIEDAD_ENCABEZADO = 2
		/** La pista de una web (Chrome, WebView) en los extras. */
		private const val CLAVE_PISTA_WEB = "AccessibilityNodeInfo.hint"
		/** Los valores de expandido y marcado (los mismos en Android 16 y en AndroidX). */
		private const val EXPANDIDO_CONTRAIDO = 1
		private const val EXPANDIDO_PARCIAL = 2
		private const val EXPANDIDO_COMPLETO = 3
		private const val ESTADO_PARCIAL = 2

		/** Cuántas vistas que etiquetan a un elemento se leen como mucho. */
		private const val MAXIMO_ETIQUETAS = 4
		/** Hasta dónde se sube para saber si algo está en una fila de una lista y llegar a la raíz. */
		private const val PROFUNDIDAD_RAIZ = 40

		/** Las notificaciones de la persiana (SystemUI). */
		private val IDS_NOTIFICACION = setOf("com.android.systemui:id/expandableNotificationRow", "com.android.systemui:id/notificationShelf")

		/** Roles web que [Nodos.rolWeb] no conoce y Tiresias dice con sus palabras (no con las de Chrome, en el idioma del móvil). */
		private val ROLES_WEB_MAS = mapOf(
			"tree" to Rol.LISTA, "treeItem" to Rol.ELEMENTO_LISTA, "menuListPopup" to Rol.MENU, "menuListOption" to Rol.ELEMENTO_MENU,
		)

		/** Los roles de Chrome que Tiresias dice con sus propias palabras. */
		val ROLES_CHROME = setOf("link", "button", "checkBox", "radioButton", "image", "list", "listItem", "table", "cell", "textField", "comboBoxGrouping", "comboBoxMenuButton", "slider", "switch", "tab", "tabList", "menuItem", "menu", "heading", "searchBox", "textFieldWithComboBox", "popUpButton", "toggleButton", "rowHeader", "columnHeader", "row", "navigation", "main", "banner", "contentInfo", "form", "region", "gridCell", "tree", "treeItem", "menuListPopup", "menuListOption")
		/** Roles de solo estructura que dan algunos navegadores (Firefox, Tor Browser) en inglés: no se dicen. */
		private val ROLES_ESTRUCTURA = setOf("paragraph", "text", "text leaf", "generic", "section", "document", "text container", "statictext", "static text")
		/** Los nombres en inglés de roles que Tiresias sabe decir él (en el idioma del usuario). */
		private val ROLES_EN_INGLES = setOf(
			"link", "button", "check box", "checkbox", "radio button", "image", "graphic", "list", "list item", "table", "cell", "edit text",
			"entry", "combo box", "slider", "switch", "tab", "tab list", "menu item", "menu", "heading", "row", "column header", "row header",
			"toggle button", "search box", "progress bar", "grid", "landmark", "navigation", "main", "banner", "form", "region",
		)
		/** Los estados que Android pone solo a los controles estándar (en los idiomas de Tiresias y los más comunes). */
		private val ESTADOS_DEL_SISTEMA = setOf(
			"checked", "not checked", "unchecked", "on", "off", "selected", "not selected", "partially checked",
			"marcado", "no marcado", "marcada", "no marcada", "activado", "desactivado", "activada", "desactivada", "sí", "no",
			"seleccionado", "no seleccionado", "seleccionada", "no seleccionada", "parcialmente marcado",
			"coché", "non coché", "activé", "désactivé", "aktiviert", "deaktiviert", "ativado", "desativado",
		)

		/**
		 * Si [estado] es uno de los que Android pone solo a las casillas, interruptores
		 * y opciones estándar (en el idioma del sistema): Tiresias dice el suyo.
		 */
		fun esEstadoDelSistema(estado: String): Boolean = estado.trim().lowercase() in ESTADOS_DEL_SISTEMA

		private val ESTANDAR = setOf(
			AccessibilityNodeInfo.ACTION_FOCUS, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS, AccessibilityNodeInfo.ACTION_SELECT,
			AccessibilityNodeInfo.ACTION_CLEAR_SELECTION, AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_LONG_CLICK,
			AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
			AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
			AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT, AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT,
			AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_COPY,
			AccessibilityNodeInfo.ACTION_PASTE, AccessibilityNodeInfo.ACTION_CUT, AccessibilityNodeInfo.ACTION_SET_SELECTION,
			AccessibilityNodeInfo.ACTION_EXPAND, AccessibilityNodeInfo.ACTION_COLLAPSE, AccessibilityNodeInfo.ACTION_DISMISS,
			AccessibilityNodeInfo.ACTION_SET_TEXT,
		)

		/** Cuántas acciones propias de la app tiene (no las de navegación ni las básicas). */
		// Los identificadores de acción son constantes que se copian al compilar: en un Android sin ellas
		// simplemente no coinciden con ninguna acción, sin fallo
		@android.annotation.SuppressLint("InlinedApi")
		fun accionesPropias(acciones: List<AccessibilityAction>): Int = acciones.count { a ->
			a.id !in ESTANDAR && a.id != android.R.id.accessibilityActionShowOnScreen &&
				a.id != android.R.id.accessibilityActionScrollToPosition && a.id != android.R.id.accessibilityActionScrollUp &&
				a.id != android.R.id.accessibilityActionScrollDown && a.id != android.R.id.accessibilityActionScrollLeft &&
				a.id != android.R.id.accessibilityActionScrollRight && a.id != android.R.id.accessibilityActionSetProgress &&
				a.id != android.R.id.accessibilityActionContextClick && a.id != android.R.id.accessibilityActionPageUp &&
				a.id != android.R.id.accessibilityActionPageDown && a.id != android.R.id.accessibilityActionPageLeft &&
				a.id != android.R.id.accessibilityActionPageRight && a.label != null
		}
	}
}
