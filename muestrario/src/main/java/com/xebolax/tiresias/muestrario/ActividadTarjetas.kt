package com.xebolax.tiresias.muestrario

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Los casos de la regla única de paradas (1.0.19): una tarjeta que se pulsa con «Me gusta» y «Comentar» pulsables
 * dentro (no eran paradas), un grupo enfocable «Etiqueta: valor» (salía en dos paradas), un texto y un botón muy hondos
 * dentro de una tarjeta pulsable (se perdían) y los mensajes de un chat cuyas filas no se pulsan (salían a trozos).
 */
class ActividadTarjetas : AppCompatActivity() {

	override fun onCreate(estado: Bundle?) {
		super.onCreate(estado)
		title = "Muestrario tarjetas"
		val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
		// 1. La tarjeta de una publicación: se pulsa entera y lleva dentro dos controles hechos con contenedores
		val tarjeta = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			isClickable = true
			isFocusable = true
			setOnClickListener { }
			addView(texto("Publicación de Ana"))
			addView(texto("Hola a todos"))
			addView(LinearLayout(this@ActividadTarjetas).apply {
				orientation = LinearLayout.HORIZONTAL
				addView(LinearLayout(this@ActividadTarjetas).apply {
					isClickable = true
					setOnClickListener { }
					addView(ImageView(this@ActividadTarjetas).apply { setImageResource(android.R.drawable.star_on) })
					addView(texto("Me gusta 12"))
				})
				addView(FrameLayout(this@ActividadTarjetas).apply {
					isClickable = true
					setOnClickListener { }
					addView(texto("Comentar"))
				})
			})
		}
		raiz.addView(tarjeta)
		// 2. Un grupo enfocable que no se pulsa: «Correo» y su valor, una sola parada
		raiz.addView(LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			isFocusable = true
			addView(texto("Correo"))
			addView(texto("ana@ejemplo.es"))
		})
		// 3. Una tarjeta pulsable con un texto y un botón muy hondos (doce niveles)
		var hondo: ViewGroup = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			isClickable = true
			setOnClickListener { }
		}
		val tarjetaHonda = hondo
		repeat(12) {
			val capa = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
			hondo.addView(capa)
			hondo = capa
		}
		hondo.addView(texto("Texto muy hondo"))
		hondo.addView(Button(this).apply { text = "Botón muy hondo" })
		raiz.addView(tarjetaHonda)
		// 4. Un chat cuyas filas no se pulsan: cada mensaje, una parada con el nombre, el texto y la hora
		raiz.addView(RecyclerView(this).apply {
			layoutManager = LinearLayoutManager(this@ActividadTarjetas)
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 700)
			adapter = Mensajes()
		})
		raiz.addView(Button(this).apply { text = "Final de las tarjetas" })
		setContentView(raiz)
	}

	private fun texto(t: String) = TextView(this).apply { text = t; textSize = 18f }

	private class Fila(v: View) : RecyclerView.ViewHolder(v)

	private inner class Mensajes : RecyclerView.Adapter<Fila>() {
		override fun getItemCount() = 3
		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int): Fila = Fila(LinearLayout(padre.context).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			addView(texto(""))
			addView(texto(""))
			addView(texto(""))
		})

		override fun onBindViewHolder(f: Fila, i: Int) {
			val fila = f.itemView as LinearLayout
			(fila.getChildAt(0) as TextView).text = "Remitente ${i + 1}"
			(fila.getChildAt(1) as TextView).text = "Mensaje número ${i + 1}"
			(fila.getChildAt(2) as TextView).text = "10:0${i + 1}"
		}
	}
}
