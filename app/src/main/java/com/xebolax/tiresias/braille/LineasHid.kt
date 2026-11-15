package com.xebolax.tiresias.braille

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.BrailleDisplayController
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import androidx.annotation.RequiresApi
import com.xebolax.tiresias.base.Registro
import com.xebolax.tiresias.nucleo.braille.LineaHid
import com.xebolax.tiresias.nucleo.braille.TeclaLinea
import java.util.concurrent.Executor

/**
 * Las líneas braille HID con BrailleDisplayController (Android 15 o
 * posterior): Android abre el dispositivo HID de la línea, USB o Bluetooth, y
 * Tiresias le escribe las celdas y lee sus teclas. Vale para cualquier línea
 * que siga la norma HID Braille (las actuales de Freedom Scientific,
 * HumanWare, APH, Orbit, HIMS, Help Tech...).
 */
@RequiresApi(35)
class LineasHid(private val servicio: AccessibilityService, private val enHilo: Executor) {
	private val controlador: BrailleDisplayController? = runCatching { servicio.brailleDisplayController }.getOrNull()

	val disponible: Boolean get() = controlador != null

	/** Lo que pasa con una prueba: todo llega en el hilo de [enHilo]. */
	interface Oyente {
		fun alConectar(c: ConexionLinea)
		fun alFallar()
		fun alTeclas(t: Set<TeclaLinea>)
		fun alDesconectar()
	}

	/** Prueba si [d] (un UsbDevice o un BluetoothDevice) es una línea HID. */
	fun probar(d: Any, o: Oyente) {
		val c = controlador ?: return o.alFallar()
		var linea: LineaHid? = null
		val llamada = object : BrailleDisplayController.BrailleDisplayCallback {
			override fun onConnected(descriptor: ByteArray) {
				val l = LineaHid.crear(descriptor)
				if (l == null || l.celdas == 0) {
					runCatching { c.disconnect() }
					o.alFallar()
					return
				}
				linea = l
				o.alConectar(object : ConexionLinea {
					override val celdas = l.celdas
					override fun escribir(contenido: ByteArray): Boolean = runCatching { c.write(l.informe(contenido)) }
						.onFailure { Registro.w(ETIQUETA, "No se pudo escribir en la línea", it) }.isSuccess
					override fun sigue(): Boolean = c.isConnected
					override fun cerrar() {
						runCatching { if (c.isConnected) c.disconnect() }
					}
				})
			}

			override fun onConnectionFailed(error: Int) {
				Registro.i(ETIQUETA, "No es una línea braille o no se pudo abrir (error $error)")
				o.alFallar()
			}

			override fun onInput(informe: ByteArray) {
				linea?.let { o.alTeclas(it.teclas(informe)) }
			}

			override fun onDisconnected() = o.alDesconectar()
		}
		val ok = runCatching {
			when (d) {
				is UsbDevice -> c.connect(d, enHilo, llamada)
				is BluetoothDevice -> {
					// Los Bluetooth solo se buscan con el permiso, pero puede haberse quitado mientras tanto
					if (servicio.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Sin permiso de dispositivos cercanos")
					c.connect(d, enHilo, llamada)
				}
				else -> throw IllegalArgumentException("No es un dispositivo")
			}
		}
		if (ok.isFailure) {
			Registro.w(ETIQUETA, "No se pudo probar un dispositivo", ok.exceptionOrNull())
			o.alFallar()
		}
	}

	/** Suelta la línea que haya (al parar o al desconectar). */
	fun soltar() {
		runCatching { if (controlador?.isConnected == true) controlador.disconnect() }
	}

	companion object {
		private const val ETIQUETA = "LineaBraille"
	}
}
