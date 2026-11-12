package app.expirymate.android

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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

enum class ScanMode { BARCODE, TEXT }

/** Live camera scanner for either barcodes or printed text. Pinch to zoom is built in. */
@Composable
fun ScannerView(
    mode: ScanMode,
    torchOn: Boolean,
    onBarcode: (String) -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val currentOnText by rememberUpdatedState(onText)
    val controller = remember { LifecycleCameraController(context) }

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
        factory = { PreviewView(it).apply { this.controller = controller } },
        modifier = modifier,
    )
}
