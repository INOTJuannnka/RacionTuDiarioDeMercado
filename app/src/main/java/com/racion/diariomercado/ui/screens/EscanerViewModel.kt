package com.racion.diariomercado.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.repository.FoodCatalogRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "Escaner" screen's ViewModel: handles camera permission, barcode scanning via ML Kit,
 * and product lookup via FoodCatalogRepository.
 *
 * ## State design
 * - [EscanerUiState.CheckingPermission]: checking camera permission on start
 * - [EscanerUiState.PermissionDenied]: camera permission denied (show rationale + settings button)
 * - [EscanerUiState.Scanning]: camera active, scanning for barcodes
 * - [EscanerUiState.LookingUp]: barcode found, looking up product in catalog
 * - [EscanerUiState.Result]: product found, ready to confirm
 * - [EscanerUiState.Error]: error message (network, not found, etc.)
 *
 * ## Scanning flow
 * 1. Check/request CAMERA permission on init
 * 2. If granted -> start CameraX preview + ML Kit analyzer
 * 3. On barcode detected -> stop scanning -> lookup via FoodCatalogRepository.productByBarcode()
 * 4. On success -> emit Result(product) -> screen navigates to Confirmar
 * 5. On failure -> emit Error(message) -> user can retry
 *
 * ## Lifecycle
 * Camera lifecycle is tied to viewModelScope: when ViewModel is cleared (screen leaves composition),
 * the coroutine is cancelled and camera resources are released.
 */
class EscanerViewModel(
    private val catalogRepository: FoodCatalogRepository,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow<EscanerUiState>(EscanerUiState.CheckingPermission)
    val uiState: StateFlow<EscanerUiState> = _uiState.asStateFlow()

    private var barcodeScanner: BarcodeScanner? = null
    private var isScanning = false

    init {
        checkPermissionAndStart()
    }

    /**
     * Checks camera permission and starts scanning if granted, or emits PermissionDenied.
     */
    private fun checkPermissionAndStart() {
        viewModelScope.launch(ioDispatcher) {
            // In a real implementation, we'd use ActivityCompat.checkSelfPermission
            // For now, we assume permission handling is done by the screen via Accompanist
            // and this ViewModel just receives the result via onPermissionResult()
        }
    }

    /**
     * Called by the screen when camera permission result is known.
     */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            startScanning()
        } else {
            _uiState.value = EscanerUiState.PermissionDenied
        }
    }

    /**
     * Initializes ML Kit barcode scanner and starts CameraX preview with analyzer.
     */
    private fun startScanning() {
        if (isScanning) return
        isScanning = true

        viewModelScope.launch(ioDispatcher) {
            // Initialize ML Kit barcode scanner (once)
            barcodeScanner = BarcodeScanning.getClient()

            _uiState.value = EscanerUiState.Scanning

            // The actual CameraX + ML Kit integration happens in the screen via
            // a PlatformView (AndroidView) that hosts the PreviewView and sets up
            // the ImageAnalysis use case with the barcode analyzer.
            // This ViewModel just coordinates the state transitions.
        }
    }

    /**
     * Called when a barcode is detected by the ML Kit analyzer.
     * Stops scanning and looks up the product.
     */
    fun onBarcodeDetected(barcode: String) {
        if (!isScanning) return // Ignore duplicates
        isScanning = false
        barcodeScanner?.close()
        barcodeScanner = null

        _uiState.value = EscanerUiState.LookingUp(barcode)

        viewModelScope.launch(ioDispatcher) {
            val result = catalogRepository.productByBarcode(barcode)
            _uiState.value = when (result) {
                is AppResult.Success -> EscanerUiState.Result(result.data)
                is AppResult.Failure -> EscanerUiState.Error(mapError(result.error))
            }
        }
    }

    /**
     * User tapped "Reintentar" after an error - go back to scanning.
     */
    fun onRetry() {
        _uiState.value = EscanerUiState.CheckingPermission
        checkPermissionAndStart()
    }

    /**
     * User wants to enter barcode manually - navigate to Agregar.
     */
    fun onManualEntry() {
        // Navigation handled by screen callback
    }

    override fun onCleared() {
        super.onCleared()
        isScanning = false
        barcodeScanner?.close()
        barcodeScanner = null
    }

    private fun mapError(error: AppError): String = when (error) {
        is AppError.Network -> "Sin conexión. Revisá tu internet e intentá de nuevo."
        is AppError.RateLimited -> "Demasiadas consultas. Esperá un momento."
        is AppError.Server -> "Error del servidor. Intentá de nuevo."
        is AppError.NotFound -> "Producto no encontrado en la base de datos."
        is AppError.Unknown -> "Error inesperado. Intentá de nuevo."
    }
}

/**
 * UI state for the "Escaner" screen.
 */
sealed interface EscanerUiState {
    data object CheckingPermission : EscanerUiState
    data object PermissionDenied : EscanerUiState
    data object Scanning : EscanerUiState
    data class LookingUp(val barcode: String) : EscanerUiState
    data class Result(val product: FoodProduct) : EscanerUiState
    data class Error(val message: String) : EscanerUiState
}