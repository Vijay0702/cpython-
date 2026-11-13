package app.expirymate.android

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class ScanMode { BARCODE, TEXT }

/**
 * Live camera scanner for either barcodes or printed text. Pinch to zoom is built in.
 *
 * Each increase of [captureRequest] takes a still of the current view and reads it
 * several ways (see [readVariants]); the texts are delivered to [onCapturedTexts].
 */
@Composable
fun ScannerView(
    mode: ScanMode,
    torchOn: Boolean,
    onBarcode: (String) -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
    captureRequest: Int = 0,
    onCapturedTexts: (List<String>) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val currentOnText by rememberUpdatedState(onText)
    val currentOnCapturedTexts by rememberUpdatedState(onCapturedTexts)
    val controller = remember { LifecycleCameraController(context) }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(captureRequest) {
        if (captureRequest > 0) {
            val still = previewView.bitmap
            currentOnCapturedTexts(if (still == null) emptyList() else readVariants(still))
        }
    }

    DisposableEffect(mode) {
        val executor = ContextCompat.getMainExecutor(context)
        if (mode == ScanMode.BARCODE) {
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    )
                    .build()
            )
            controller.setImageAnalysisAnalyzer(
                executor,
                MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, executor) { result ->
                    val code = result.getValue(scanner)?.firstOrNull()?.rawValue
                    if (code != null) currentOnBarcode(code)
                },
            )
            onDispose {
                controller.clearImageAnalysisAnalyzer()
                scanner.close()
            }
        } else {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            controller.setImageAnalysisAnalyzer(
                executor,
                MlKitAnalyzer(listOf(recognizer), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, executor) { result ->
                    val text = result.getValue(recognizer)?.text
                    if (!text.isNullOrBlank()) currentOnText(text)
                },
            )
            onDispose {
                controller.clearImageAnalysisAnalyzer()
                recognizer.close()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    LaunchedEffect(torchOn) {
        controller.enableTorch(torchOn)
    }

    AndroidView(
        factory = { previewView.apply { this.controller = controller } },
        modifier = modifier,
    )
}

/**
 * Reads text from a still after treating it in several ways, for print the live
 * scanner cannot read: dot-matrix, embossed or faint dates.
 *
 * Dot-matrix characters are separate dots, which text recognition tends to see
 * as specks. Shrinking the picture and enlarging it again smears neighbouring
 * dots into strokes. No single strength suits every label, so each variant is
 * read separately and the caller uses the first one that yields a date.
 */
private suspend fun readVariants(still: Bitmap): List<String> {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val variants = withContext(Dispatchers.Default) {
            listOf(still) + listOf(2, 3, 4).map { smear(still, it) }
        }
        return variants.map { recognise(recognizer, it) }
    } finally {
        recognizer.close()
    }
}

private fun smear(source: Bitmap, factor: Int): Bitmap {
    val small = Bitmap.createScaledBitmap(
        source, maxOf(1, source.width / factor), maxOf(1, source.height / factor), true,
    )
    return Bitmap.createScaledBitmap(small, source.width, source.height, true)
}

private suspend fun recognise(recognizer: TextRecognizer, bitmap: Bitmap): String =
    suspendCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { continuation.resume(it.text) }
            .addOnFailureListener { continuation.resume("") }
    }
