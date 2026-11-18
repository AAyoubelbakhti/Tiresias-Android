package com.xebolax.tiresias.muestrario

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Lo que contaron en el foro tras la 1.0.16: un deslizador dentro de una fila que se pulsa (el volumen de una
 * alarma; el precio de un filtro de Wallapop), un selector de rueda (la hora de la alarma), la barra de un mensaje
 * de voz que avanza sola mientras suena y un botón «Transcribir» dentro de un marco con el mismo nombre (WhatsApp).
 */
class ActividadAlarma : AppCompatActivity() {
	private val manejador = Handler(Looper.getMainLooper())
	private var avance: Runnable? = null

	override fun onCreate(estado: Bundle?) {
		super.onCreate(estado)
		title = "Muestrario alarma"
		val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
		// La fila de la alarma: se pulsa entera, y dentro lleva su volumen
		val fila = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			isClickable = true
			isFocusable = true
			setOnClickListener { }
			addView(TextView(this@ActividadAlarma).apply { text = "Alarma de las siete" })
			addView(SeekBar(this@ActividadAlarma).apply {
				contentDescription = "Volumen de la alarma"
				max = 100
				progress = 50
			})
		}
		raiz.addView(fila)
		raiz.addView(TextView(this).apply { text = "Hora" })
		raiz.addView(NumberPicker(this).apply {
			minValue = 0
			maxValue = 23
			value = 7
			contentDescription = "Hora"
		})
		// La barra de un mensaje de voz que suena: avanza sola
		val audio = SeekBar(this).apply {
			contentDescription = "Mensaje de voz"
			max = 1000
			progress = 0
		}
		raiz.addView(audio)
		val r = object : Runnable {
			override fun run() {
				audio.progress = (audio.progress + 7) % 1000
				manejador.postDelayed(this, 150)
			}
		}
		avance = r
		manejador.postDelayed(r, 150)
		// Un marco con el nombre «Transcribir» que no se pulsa y, dentro, el botón «Transcribir»
		raiz.addView(FrameLayout(this).apply {
			contentDescription = "Transcribir"
			isFocusable = true
			addView(Button(this@ActividadAlarma).apply { text = "Transcribir" })
		})
		raiz.addView(Button(this).apply { text = "Guardar alarma" })
		setContentView(raiz)
	}

	override fun onDestroy() {
		avance?.let { manejador.removeCallbacks(it) }
		super.onDestroy()
	}
}
