@file:OptIn(ExperimentalComposeApi::class, ExperimentalPermissionsApi::class)
@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.racion.diariomercado.ui.screens

import androidx.compose.runtime.ExperimentalComposeApi
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import android.Manifest
import android.content.Context
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.racion.diariomercado.domain.model.DiaryEntry
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.ui.components.*
import com.racion.diariomercado.ui.preview.PreviewData
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pantalla 03 · Escáner - Lector de código de barras real con ML Kit + CameraX.
 *
 * Flow:
 * 1. Check/request CAMERA permission (Accompanist Permissions)
 * 2. If granted -> show camera preview with scanning frame
 * 3. On barcode detected -> lookup via ViewModel -> navigate to Confirmar
 * 4. If denied -> show rationale + "Abrir ajustes" button
 * 5. Manual entry row navigates to Agregar screen
 */
@Composable
fun EscanerScreen(
    viewModel: EscanerViewModel,
    recentScans: List<DiaryEntry> = PreviewData.recentScans,
    onManualEntry: () -> Unit = {},
    onScanResult: (FoodProduct) -> Unit = {},
    onNavigate: (NavDestination) -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

    // Camera permission state via Accompanist
    val permissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val permissionStatus = permissionState.status

    // Handle permission changes
    LaunchedEffect(permissionStatus) {
        when (permissionStatus) {
            PermissionStatus.Granted -> viewModel.onPermissionResult(true)
            else -> viewModel.onPermissionResult(false)
        }
    }

    // Handle UI state changes - navigate on result
    LaunchedEffect(uiState) {
        when (uiState) {
            is EscanerUiState.Result -> {
                onScanResult(uiState.product)
            }
            is EscanerUiState.Error -> {
                // Error shown inline, user can retry
            }
            else -> {}
        }
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = { AppBottomBar(selected = NavDestination.Escanear, onSelect = onNavigate) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text(
                    "Escanea el producto",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFF7F2E9)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Apunta la cámara al código de barras del empaque",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB8B2A2)
                )
            }

            // Camera area or permission UI
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(MaterialTheme.shapes.extraLarge),
                contentAlignment = Alignment.Center
            ) {
                when {
                    permissionStatus == PermissionStatus.Granted && uiState is EscanerUiState.Scanning -> {
                        // Real camera preview with scanning frame
                        CameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            onBarcodeDetected = { barcode ->
                                viewModel.onBarcodeDetected(barcode)
                            }
                        )
                        ScanFrame(modifier = Modifier.fillMaxSize().padding(24.dp))
                    }
                    permissionStatus == PermissionStatus.Granted && uiState is EscanerUiState.LookingUp -> {
                        // Show loading while looking up
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Buscando ${uiState.barcode}...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    permissionStatus != PermissionStatus.Granted -> {
                        // Permission rationale / request
                        PermissionRequestView(
                            onRequestPermission = { permissionState.launchPermissionRequest() },
                            onOpenSettings = { permissionState.launchPermissionRequest() }
                        )
                    }
                    uiState is EscanerUiState.Error -> {
                        // Error state with retry
                        ErrorView(
                            message = uiState.message,
                            onRetry = { viewModel.onRetry() }
                        )
                    }
                    else -> {
                        // Fallback
                        PermissionRequestView(
                            onRequestPermission = { permissionState.launchPermissionRequest() },
                            onOpenSettings = { permissionState.launchPermissionRequest() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Manual entry row
            Text(
                "Ingresar código manualmente",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onManualEntry)
                    .padding(16.dp)
            )

            Spacer(Modifier.height(20.dp))

            // Recent scans
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp)
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel("ESCANEADO RECIENTEMENTE")
                Spacer(Modifier.height(12.dp))
                recentScans.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.product.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                timeAgoLabel(item.loggedAtEpochMillis).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${item.totalNutrition.kcal}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Camera preview with ML Kit barcode scanning using CameraX PreviewView.
 * Uses AndroidView to host the PreviewView directly.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onBarcodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    // Bind/unbind camera with lifecycle
    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation((context as? androidx.fragment.app.FragmentActivity)?.windowManager?.defaultDisplay?.rotation ?: 0)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation((context as? androidx.fragment.app.FragmentActivity)?.windowManager?.defaultDisplay?.rotation ?: 0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            analyzeImage(imageProxy, barcodeScanner, onBarcodeDetected)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraExecutor.shutdown()
            barcodeScanner.close()
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    cameraProviderFuture.get().unbindAll()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
        update = { it }
    )
}

private fun analyzeImage(
    imageProxy: ImageProxy,
    barcodeScanner: BarcodeScanner,
    onBarcodeDetected: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                imageProxy.close()
                if (barcodes.isNotEmpty()) {
                    val barcode = barcodes.firstOrNull { it.rawValue != null }?.rawValue
                    barcode?.let { onBarcodeDetected(it) }
                }
            }
            .addOnFailureListener { e ->
                imageProxy.close()
                e.printStackTrace()
            }
    } else {
        imageProxy.close()
    }
}

@Composable
private fun PermissionRequestView(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.NoPhotography,
            contentDescription = null,
            tint = Color(0xFF888888),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Se necesita permiso de cámara",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Para escanear códigos de barras, la app necesita acceso a la cámara.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFBBBBBB),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(24.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PrimaryButton(text = "Permitir cámara", onClick = onRequestPermission)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onOpenSettings) {
                Text("Abrir ajustes")
            }
        }
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = "Reintentar", onClick = onRetry)
    }
}

/** Marco de escaneo con esquinas amarillas y línea láser central. */
@Composable
private fun ScanFrame(modifier: Modifier = Modifier) {
    val cornerColor = MaterialTheme.colorScheme.tertiary
    val laserColor = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val cornerLen = size.width * 0.12f
        val stroke = 6f

        drawLine(cornerColor, androidx.compose.ui.geometry.Offset(0f, cornerLen), androidx.compose.ui.geometry.Offset(0f, 0f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(cornerColor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(cornerLen, 0f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(cornerColor, androidx.compose.ui.geometry.Offset(size.width - cornerLen, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(cornerColor, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width, cornerLen), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(cornerColor, androidx.compose.ui.geometry.Offset(0f, size.height - cornerLen), androidx.compose.ui.geometry.Offset(0f, size.height), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(cornerColor, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(cornerLen, size.height), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(cornerColor, androidx.compose.ui.geometry.Offset(size.width - cornerLen, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(cornerColor, androidx.compose.ui.geometry.Offset(size.width, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height - cornerLen), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

        drawLine(
            laserColor,
            androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
            androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
            strokeWidth = 4f
        )
    }
}

/**
 * "Hace 2 días" style label, computed from the real timestamp.
 */
private fun timeAgoLabel(loggedAtEpochMillis: Long): String {
    val days = Duration.between(Instant.ofEpochMilli(loggedAtEpochMillis), Instant.now()).toDays()
    return when {
        days <= 0L -> "Hoy"
        days == 1L -> "Ayer"
        days < 7L -> "Hace $days días"
        days < 30L -> "Hace ${days / 7} sem"
        else -> "Hace ${days / 30} mes"
    }
}