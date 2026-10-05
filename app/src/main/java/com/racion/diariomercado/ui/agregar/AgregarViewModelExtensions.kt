package com.racion.diariomercado.ui.agregar

import com.racion.diariomercado.core.AppError

/**
 * Maps AppError to user-facing message for Agregar screen.
 * RateLimited needs its own message as per requirements.
 */
fun AppError.toAgregarUserMessage(): String = when (this) {
    AppError.Network -> "Sin conexión. Revisa tu internet e inténtalo de nuevo."
    AppError.RateLimited -> "El presupuesto de búsqueda se agotó. Espera un momento e inténtalo de nuevo."
    is AppError.Server -> "Open Food Facts no pudo responder en este momento. Inténtalo de nuevo en un rato."
    is AppError.Unknown -> "Ocurrió un error inesperado al buscar. Inténtalo de nuevo."
    AppError.NotFound -> "No encontramos productos para esa búsqueda."
}
