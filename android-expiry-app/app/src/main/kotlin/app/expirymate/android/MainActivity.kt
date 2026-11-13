package app.expirymate.android

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ItemStore.load(this)
        Reminders.ensureChannel(this)
        setContent { ExpiryMateApp() }
    }

    override fun onResume() {
        super.onResume()
        Reminders.reschedule(this)
    }
}

private enum class Screen { LIST, ADD, EDIT, SETTINGS }

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@Composable
private fun ExpiryMateApp() {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var screen by rememberSaveable { mutableStateOf(Screen.LIST) }
        var editingId by rememberSaveable { mutableStateOf<String?>(null) }
        BackHandler(enabled = screen != Screen.LIST) { screen = Screen.LIST }
        when (screen) {
            Screen.LIST -> ListScreen(
                onAdd = { screen = Screen.ADD },
                onSettings = { screen = Screen.SETTINGS },
                onEdit = { item ->
                    editingId = item.id
                    screen = Screen.EDIT
                },
            )
            Screen.ADD -> AddScreen(onDone = { screen = Screen.LIST })
            Screen.EDIT -> EditScreen(itemId = editingId, onDone = { screen = Screen.LIST })
            Screen.SETTINGS -> SettingsScreen(onDone = { screen = Screen.LIST })
        }
    }
}

// region List

@Composable
private fun ListScreen(onAdd: () -> Unit, onSettings: () -> Unit, onEdit: (Item) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val all by ItemStore.items.collectAsState()
    val today = LocalDate.now()
    val items = all.filter { it.isActive }.sortedBy { it.expiry }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !isGranted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun setStatus(item: Item, status: String) {
        ItemStore.update(context) { list -> list.map { if (it.id == item.id) it.copy(status = status) else it } }
        Reminders.cancelItem(context, item.id)
        Reminders.reschedule(context)
    }

    fun resolve(item: Item, status: String) {
        setStatus(item, status)
        scope.launch {
            // Replace any earlier message so Undo always refers to the latest action.
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = "${item.name} marked as ${if (status == Item.USED) "used" else "thrown away"}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) setStatus(item, Item.ACTIVE)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ExpiryMate") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Reminder settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Scan item") },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No items yet.\nScan a grocery item to start tracking its expiry date.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    // Leaves room so the last row is not hidden behind the Scan button.
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
            ) {
                section("Expired", items.filter { it.daysLeft(today) < 0 }, today, onEdit, ::resolve)
                section("Expiring soon", items.filter { it.daysLeft(today) in 0..3 }, today, onEdit, ::resolve)
                section("Fresh", items.filter { it.daysLeft(today) > 3 }, today, onEdit, ::resolve)
            }
        }
    }
}

private fun LazyListScope.section(
    title: String,
    sectionItems: List<Item>,
    today: LocalDate,
    onEdit: (Item) -> Unit,
    onResolve: (Item, String) -> Unit,
) {
    if (sectionItems.isEmpty()) return
    item(key = title) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
    }
    items(sectionItems, key = { it.id }) { item ->
        ItemRow(item, today, onEdit, onResolve)
        HorizontalDivider()
    }
}

@Composable
private fun ItemRow(item: Item, today: LocalDate, onEdit: (Item) -> Unit, onResolve: (Item, String) -> Unit) {
    val days = item.daysLeft(today)
    val dot = when {
        days < 0 -> Color(0xFFE5484D)
        days <= 3 -> Color(0xFFF59E0B)
        else -> Color(0xFF30A46C)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Edit ${item.name}") { onEdit(item) }
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            Text(
                describeDaysLeft(days).replaceFirstChar { it.uppercase() } + " · " + item.expiry.format(dateFormat),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onResolve(item, Item.USED) }) {
            Icon(Icons.Default.Check, contentDescription = "Mark ${item.name} as used")
        }
        IconButton(onClick = { onResolve(item, Item.DISCARDED) }) {
            Icon(Icons.Default.Delete, contentDescription = "Mark ${item.name} as thrown away")
        }
    }
}

// endregion

// region Add item

private enum class AddStep { PERMISSION, BARCODE, DATE, CONFIRM }

/**
 * Three steps: scan the barcode, scan the printed expiry date, confirm and save.
 * Every step can be skipped, so the app still works without a camera.
 */
@Composable
private fun AddScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cameraGranted by remember { mutableStateOf(isGranted(context, Manifest.permission.CAMERA)) }
    var step by remember { mutableStateOf(if (cameraGranted) AddStep.BARCODE else AddStep.PERMISSION) }
    var barcode by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(LocalDate.now().plusDays(7)) }
    var detected by remember { mutableStateOf<ParsedExpiry?>(null) }
    var captureRequest by remember { mutableIntStateOf(0) }
    var enhancing by remember { mutableStateOf(false) }
    var enhanceFailed by remember { mutableStateOf(false) }
    var lookingUp by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        step = if (granted) AddStep.BARCODE else AddStep.CONFIRM
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun save() {
        val item = Item(id = UUID.randomUUID().toString(), name = name.trim(), barcode = barcode, expiry = expiry)
        ItemStore.update(context) { it + item }
        Reminders.reschedule(context)
        onDone()
    }

    val title = when (step) {
        AddStep.PERMISSION -> "Add item"
        AddStep.BARCODE -> "Scan barcode"
        AddStep.DATE -> "Scan expiry date"
        AddStep.CONFIRM -> "Confirm item"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onDone) { Text("Cancel") } },
                actions = {
                    if (step == AddStep.CONFIRM) {
                        TextButton(onClick = ::save, enabled = name.isNotBlank()) { Text("Save") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                AddStep.PERMISSION -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                AddStep.BARCODE -> ScanStep(
                    mode = ScanMode.BARCODE,
                    torchOn = torchOn,
                    onToggleTorch = { torchOn = !torchOn },
                    onBarcode = { code ->
                        // The scanner reports the same code on every frame; act on the first only.
                        if (barcode == null) {
                            barcode = code
                            lookingUp = true
                            scope.launch {
                                // A name the user gave this product before beats the database's.
                                val remembered = ItemStore.items.value.lastOrNull { it.barcode == code }?.name
                                (remembered ?: ProductLookup.name(code))?.let { name = it }
                                lookingUp = false
                                if (step == AddStep.BARCODE) step = AddStep.DATE
                            }
                        }
                    },
                ) {
                    if (lookingUp) {
                        CircularProgressIndicator()
                        Text("Looking up product…")
                    } else {
                        Text("Point the camera at the product's barcode", textAlign = TextAlign.Center)
                    }
                    OutlinedButton(onClick = { step = AddStep.DATE }) { Text("No barcode? Skip") }
                }

                AddStep.DATE -> ScanStep(
                    mode = ScanMode.TEXT,
                    torchOn = torchOn,
                    onToggleTorch = { torchOn = !torchOn },
                    onText = { text ->
                        val parsed = ExpiryDateParser.parseDetailed(text)
                        if (parsed != null && parsed != detected) detected = parsed
                    },
                    captureRequest = captureRequest,
                    onCapturedTexts = { texts ->
                        val parsed = texts.firstNotNullOfOrNull { ExpiryDateParser.parseDetailed(it) }
                        enhancing = false
                        if (parsed != null) detected = parsed else enhanceFailed = true
                    },
                ) {
                    if (name.isNotEmpty()) {
                        Text(name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                    val found = detected
                    if (found != null) {
                        Text("Found: ${found.date.format(dateFormat)}")
                        found.note?.let {
                            Text(
                                "Calculated: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = {
                            expiry = found.date
                            step = AddStep.CONFIRM
                        }) { Text("Use this date") }
                    } else if (enhancing) {
                        CircularProgressIndicator()
                        Text("Reading the picture…")
                    } else {
                        Text(
                            if (enhanceFailed) {
                                "No date found in the picture. Try the light at an angle, or enter the date manually."
                            } else {
                                "Point the camera at the printed expiry date. Pinch to zoom."
                            },
                            textAlign = TextAlign.Center,
                        )
                        OutlinedButton(onClick = {
                            enhancing = true
                            enhanceFailed = false
                            captureRequest++
                        }) { Text("Dotted or faint print? Capture") }
                    }
                    OutlinedButton(onClick = { step = AddStep.CONFIRM }) { Text("Enter date manually") }
                }

                AddStep.CONFIRM -> ConfirmStep(
                    name = name,
                    onNameChange = { name = it },
                    barcode = barcode,
                    expiry = expiry,
                    onExpiryChange = { expiry = it },
                    onScanAgain = if (cameraGranted) {
                        {
                            barcode = null
                            detected = null
                            enhanceFailed = false
                            torchOn = false
                            step = AddStep.BARCODE
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun ScanStep(
    mode: ScanMode,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onBarcode: (String) -> Unit = {},
    onText: (String) -> Unit = {},
    captureRequest: Int = 0,
    onCapturedTexts: (List<String>) -> Unit = {},
    panel: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        ScannerView(mode, torchOn, onBarcode, onText, Modifier.fillMaxSize(), captureRequest, onCapturedTexts)
        FilledTonalButton(onClick = onToggleTorch, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            Text(if (torchOn) "Light off" else "Light on")
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                panel()
            }
        }
    }
}

@Composable
private fun ConfirmStep(
    name: String,
    onNameChange: (String) -> Unit,
    barcode: String?,
    expiry: LocalDate,
    onExpiryChange: (LocalDate) -> Unit,
    onScanAgain: (() -> Unit)?,
) {
    val context = LocalContext.current
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Product name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (barcode != null) {
            Text("Barcode $barcode", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Expires on ${expiry.format(dateFormat)}", Modifier.weight(1f))
            OutlinedButton(onClick = {
                DatePickerDialog(
                    context,
                    { _, year, month, day -> onExpiryChange(LocalDate.of(year, month + 1, day)) },
                    expiry.year, expiry.monthValue - 1, expiry.dayOfMonth,
                ).show()
            }) { Text("Change") }
        }

        // One-tap expiry for when the printed date cannot be scanned.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onExpiryChange(today.plusDays(3)) }) { Text("3 days") }
            OutlinedButton(onClick = { onExpiryChange(today.plusDays(7)) }) { Text("1 week") }
            OutlinedButton(onClick = { onExpiryChange(today.plusDays(14)) }) { Text("2 weeks") }
            OutlinedButton(onClick = { onExpiryChange(today.plusMonths(1)) }) { Text("1 month") }
        }

        if (onScanAgain != null) {
            TextButton(onClick = onScanAgain) { Text("Scan again") }
        }
    }
}

// endregion

// region Edit item

/** Corrects the name or expiry date of a saved item. */
@Composable
private fun EditScreen(itemId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val original = remember(itemId) { ItemStore.items.value.firstOrNull { it.id == itemId } }
    if (original == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    var name by remember { mutableStateOf(original.name) }
    var expiry by remember { mutableStateOf(original.expiry) }

    fun save() {
        ItemStore.update(context) { list ->
            // A corrected date starts with a clean slate of reminders.
            list.map { if (it.id == original.id) it.copy(name = name.trim(), expiry = expiry, snoozedUntil = null) else it }
        }
        Reminders.cancelItem(context, original.id)
        Reminders.reschedule(context)
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit item") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Cancel") } },
                actions = { TextButton(onClick = ::save, enabled = name.isNotBlank()) { Text("Save") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ConfirmStep(
                name = name,
                onNameChange = { name = it },
                barcode = original.barcode,
                expiry = expiry,
                onExpiryChange = { expiry = it },
                onScanAgain = null,
            )
        }
    }
}

// endregion

// region Settings

@Composable
private fun SettingsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(Reminders.settings(context)) }

    fun change(updated: ReminderSettings) {
        settings = updated
        Reminders.saveSettings(context, updated)
    }

    fun hourLabel(hour: Int) = LocalTime.of(hour, 0).format(timeFormat)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reminders") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Daily summary", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("One notification every day listing the items closest to expiry.", Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> change(settings.copy(digestHour = hour, digestMinute = minute)) },
                        settings.digestHour, settings.digestMinute, DateFormat.is24HourFormat(context),
                    ).show()
                }) { Text(LocalTime.of(settings.digestHour, settings.digestMinute).format(timeFormat)) }
            }

            HorizontalDivider()

            Text("Day before expiry", style = MaterialTheme.typography.titleMedium)
            Text("A reminder every hour between these times, until you mark the item as used or thrown away.")
            HourStepper("From", hourLabel(settings.hourlyStart)) { delta ->
                val start = (settings.hourlyStart + delta).coerceIn(0, 23)
                change(settings.copy(hourlyStart = start, hourlyEnd = maxOf(settings.hourlyEnd, start)))
            }
            HourStepper("Until", hourLabel(settings.hourlyEnd)) { delta ->
                val end = (settings.hourlyEnd + delta).coerceIn(0, 23)
                change(settings.copy(hourlyEnd = end, hourlyStart = minOf(settings.hourlyStart, end)))
            }

            HorizontalDivider()

            Text(
                "Product names come from the volunteer-run Open Food Facts, Open Beauty Facts and Open Products " +
                    "Facts databases (openfoodfacts.org), available under the Open Database Licence. " +
                    "Only the barcode number is sent to them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HourStepper(label: String, value: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { onStep(-1) }) { Text("−") }
        Text(value, Modifier.width(96.dp), textAlign = TextAlign.Center)
        OutlinedButton(onClick = { onStep(1) }) { Text("+") }
    }
}

// endregion

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
