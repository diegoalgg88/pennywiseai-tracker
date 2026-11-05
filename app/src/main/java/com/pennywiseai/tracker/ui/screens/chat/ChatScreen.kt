package com.pennywiseai.tracker.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import com.pennywiseai.tracker.ui.effects.overScrollVertical
import com.pennywiseai.tracker.ui.effects.rememberOverscrollFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.pennywiseai.tracker.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.data.repository.ModelState
import com.pennywiseai.tracker.ui.components.CustomTitleTopAppBar
import com.pennywiseai.tracker.ui.components.cards.PennyWiseCardV2
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.pennywiseai.tracker.utils.TokenUtils
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import kotlinx.coroutines.CoroutineScope
import android.net.Uri
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.layout.ContentScale
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.pennywiseai.tracker.utils.AudioRecorder
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentResponse by viewModel.currentResponse.collectAsStateWithLifecycle()
    val pendingAction by viewModel.pendingAction.collectAsStateWithLifecycle()
    val isConfirming by viewModel.isConfirming.collectAsStateWithLifecycle()
    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val isDeveloperMode by viewModel.isDeveloperModeEnabled.collectAsStateWithLifecycle()
    val chatStats by viewModel.chatStats.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadedMB by viewModel.downloadedMB.collectAsStateWithLifecycle()
    val totalMB by viewModel.totalMB.collectAsStateWithLifecycle()

    // Attached image for preview before sending (W13)
    val attachedImage by viewModel.attachedImage.collectAsStateWithLifecycle<Uri?>()
    
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    // --- Adjuntos de imagen: cámara (FileProvider) y galería ---
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.attachImage(it) } }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCameraUri?.let { viewModel.attachImage(it) }
        } else {
            // El usuario canceló la captura: el archivo temporal no se usa.
            pendingCameraFile?.delete()
        }
        pendingCameraUri = null
        pendingCameraFile = null
    }

    // --- Nota de voz: mantener presionado para grabar ---
    val voiceRecorder = remember(context, scope) {
        VoiceNoteRecorder(context = context, scope = scope) { uri -> viewModel.sendAudio(uri) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Conceder el permiso no inicia la captura: el usuario vuelve a mantener
        // presionado el micrófono para grabar.
    }

    // El grabador corta solo al llegar a la duración máxima.
    LaunchedEffect(voiceRecorder.isRecording) {
        if (!voiceRecorder.isRecording) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        while (voiceRecorder.isRecording) {
            voiceRecorder.elapsedMs = System.currentTimeMillis() - startedAt
            if (voiceRecorder.elapsedMs >= AudioRecorder.maxDurationMs) {
                voiceRecorder.stop(send = true)
                break
            }
            delay(100)
        }
    }

    val launchCamera: () -> Unit = {
        val dir = File(context.cacheDir, "camera_temp").apply { mkdirs() }
        val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCameraFile = file
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }
    
    // Auto-scroll: discreto para cambios de lista, muestreado durante el
    // streaming. El índice real sale de layoutInfo porque la lista también
    // contiene la tarjeta pendiente, el panel de thinking y el indicador de
    // escritura: messages.size no es el último item.
    LaunchedEffect(messages.size, pendingAction, uiState.isLoading) {
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { currentResponse.length }
            .sample(100)
            .collect { if (it > 0) listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1) }
    }

    // Scroll behaviors for TopAppBar
    val scrollBehaviorSmall = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollBehaviorLarge = scrollBehaviorSmall
    val hazeState = remember { HazeState() }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            CustomTitleTopAppBar(
                scrollBehaviorSmall = scrollBehaviorSmall,
                scrollBehaviorLarge = scrollBehaviorLarge,
                title = stringResource(R.string.chat_title),
                hazeState = hazeState
            )
        }
    ) { paddingValues ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(hazeState)
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
    ) {
        when (modelState) {
            ModelState.NOT_DOWNLOADED, ModelState.DOWNLOADING, ModelState.ERROR -> {
                val isDownloading = modelState == ModelState.DOWNLOADING
                // Show existing messages if any, but disable input
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // If no messages, show the download prompt centered
                    if (messages.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                                modifier = Modifier.padding(horizontal = Dimensions.Padding.content)
                            ) {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(if (isDownloading) R.string.chat_downloading_model else R.string.chat_model_required_title),
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Text(
                                    text = if (isDownloading) stringResource(R.string.chat_download_progress, downloadedMB, totalMB) else stringResource(R.string.chat_model_required_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isDownloading) {
                                    LinearProgressIndicator(
                                        progress = { downloadProgress / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(Spacing.sm)
                                            .clip(RoundedCornerShape(Spacing.xs)),
                                        drawStopIndicator = {}
                                    )
                                    OutlinedButton(onClick = { viewModel.cancelDownload() }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(Dimensions.Icon.small)
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.xs))
                                        Text(stringResource(R.string.chat_cancel))
                                    }
                                } else {
                                    Button(onClick = { viewModel.startModelDownload() }) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(Dimensions.Icon.small)
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.xs))
                                        Text(stringResource(R.string.chat_download_with_size, totalMB))
                                    }
                                }
                                TextButton(onClick = onNavigateToSettings) {
                                    Text(stringResource(R.string.chat_settings))
                                }
                            }
                        }
                    } else {
                        // Show existing messages (read-only)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .overScrollVertical(),
                            contentPadding = PaddingValues(
                                start = Dimensions.Padding.content,
                                end = Dimensions.Padding.content,
                                top = Dimensions.Padding.content,
                                bottom = Spacing.lg
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            flingBehavior = rememberOverscrollFlingBehavior { listState }
                        ) {
                            items(messages, key = { it.id }) { message ->
                                ChatMessageItem(message = message)
                            }
                        }
                    }

                    // Show model required banner at bottom
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        tonalElevation = 3.dp
                    ) {
                        if (isDownloading) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Dimensions.Padding.content),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.chat_downloading_model),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = stringResource(R.string.chat_download_progress, downloadedMB, totalMB),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.cancelDownload() },
                                        modifier = Modifier.padding(start = Spacing.sm)
                                    ) {
                                        Text(stringResource(R.string.chat_cancel))
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(Spacing.sm)
                                        .clip(CircleShape),
                                    drawStopIndicator = {}
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Dimensions.Padding.content),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.chat_model_required_short),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        // 1.6 GB via the system Download Manager; some phones (Samsung
                                        // with Data saver) won't start it on mobile data.
                                        text = stringResource(R.string.chat_model_size_hint),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Button(
                                    onClick = { viewModel.startModelDownload() },
                                    modifier = Modifier.padding(start = Spacing.sm)
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(Dimensions.Icon.small)
                                    )
                                    Spacer(modifier = Modifier.width(Spacing.xs))
                                    Text(stringResource(R.string.chat_download))
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(Dimensions.Component.bottomBarHeight))
                }
            }

            ModelState.READY, ModelState.LOADING -> {
                // Show loading overlay when model is loading
                if (modelState == ModelState.LOADING) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.chat_initializing_model),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.chat_initializing_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Developer info card
                        AnimatedVisibility(
                            visible = isDeveloperMode && messages.isNotEmpty(),
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            DeveloperInfoCard(chatStats = chatStats)
                        }
                        
                        // Token limit warning
                        AnimatedVisibility(
                            visible = chatStats.contextUsagePercent >= 80,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            TokenLimitWarning(
                                usagePercent = chatStats.contextUsagePercent,
                                onClearChat = { viewModel.clearChat() }
                            )
                        }

                        // Clear chat button when there are messages
                        AnimatedVisibility(
                            visible = messages.isNotEmpty(),
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = Dimensions.Padding.content,
                                            vertical = Spacing.sm
                                        ),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = { viewModel.clearChat() },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(Dimensions.Icon.small)
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.xs))
                                        Text(stringResource(R.string.chat_clear_chat))
                                    }
                                }
                            }
                        }

                        // Messages list
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .overScrollVertical(),
                            contentPadding = PaddingValues(
                                start = Dimensions.Padding.content,
                                end = Dimensions.Padding.content,
                                top = Dimensions.Padding.content,
                                bottom = Spacing.lg
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            flingBehavior = rememberOverscrollFlingBehavior { listState },
                            reverseLayout = false
                        ) {
                            // Example prompts when no messages
                            if (messages.isEmpty() && currentResponse.isEmpty() && !uiState.isLoading) {
                                item {
                                    ChatEmptyState(
                                        onPromptClick = { prompt ->
                                            viewModel.sendMessage(prompt)
                                        }
                                    )
                                }
                            }

                            items(messages, key = { it.id }) { message ->
                                ChatMessageItem(message = message)
                            }

                            // A transaction the model proposed — the user confirms it (#170)
                            pendingAction?.let { action ->
                                item {
                                    PendingActionCard(
                                        action = action,
                                        currency = baseCurrency,
                                        enabled = !isConfirming,
                                        onConfirm = { viewModel.confirmPendingAction() },
                                        onDismiss = { viewModel.dismissPendingAction() }
                                    )
                                }
                            }

                            // Panel de thinking (solo developer mode): el razonamiento crudo
                            // que el modelo emite aparte del texto final.
                            if (isDeveloperMode && uiState.thinkingTokens.isNotBlank()) {
                                item {
                                    ThinkingPanel(thinkingTokens = uiState.thinkingTokens)
                                }
                            }

                            // Show streaming response if available
                            if (currentResponse.isNotEmpty()) {
                                item {
                                    ChatMessageItem(
                                        message = com.pennywiseai.tracker.data.database.entity.ChatMessage(
                                            message = currentResponse,
                                            isUser = false,
                                            timestamp = System.currentTimeMillis()
                                        ),
                                        isStreaming = true
                                    )
                                }
                            } else if (uiState.isLoading) {
                                // Show typing indicator while waiting for response
                                item {
                                    // One status line per request, picked when the wait starts,
                                    // so a tool call's silent few seconds don't look like a hang.
                                    val status = remember(uiState.isLoading) { THINKING_LINES.random() }
                                    TypingIndicator(status = stringResource(status))
                                }
                            }
                        }

                        // Error message
                        AnimatedVisibility(
                            visible = uiState.error != null,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            PennyWiseCardV2(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Dimensions.Padding.content),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = uiState.error?.asString().orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { viewModel.clearError() }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.chat_dismiss),
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }

                        // Input field
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 3.dp
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                // Attached image preview (W13)
                                attachedImage?.let { uri ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(Dimensions.Padding.content),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Thumbnail
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(uri)
                                                .build(),
                                            contentDescription = stringResource(R.string.chat_image_preview),
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.sm))
                                        // Send/Remove buttons
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                                        ) {
                                            Button(
                                                onClick = { viewModel.sendAttachedImage() },
                                                enabled = !uiState.isLoading,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.primary
                                                )
                                            ) {
                                                Text(stringResource(R.string.chat_send))
                                            }
                                            TextButton(
                                                onClick = { viewModel.clearAttachment() },
                                                enabled = !uiState.isLoading,
                                                colors = ButtonDefaults.textButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.error
                                                )
                                            ) {
                                                Text(stringResource(R.string.chat_remove))
                                            }
                                        }
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = Dimensions.Padding.content),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }

                                // Indicador de grabación de la nota de voz
                                AnimatedVisibility(
                                    visible = voiceRecorder.isRecording,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(Dimensions.Padding.content),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(Dimensions.Icon.small)
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.xs))
                                        Text(
                                            text = stringResource(R.string.chat_recording),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.xs))
                                        Text(
                                            text = formatRecordingTime(voiceRecorder.elapsedMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(
                                            text = stringResource(R.string.chat_release_to_send),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Dimensions.Padding.content),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    // Menú de adjuntos: cámara o galería
                                    Box {
                                        IconButton(
                                            onClick = { showAttachMenu = true },
                                            enabled = !uiState.isLoading
                                        ) {
                                            Icon(
                                                Icons.Default.AttachFile,
                                                contentDescription = stringResource(R.string.chat_attach_content_description)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showAttachMenu,
                                            onDismissRequest = { showAttachMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chat_camera_take_photo)) },
                                                onClick = {
                                                    showAttachMenu = false
                                                    launchCamera()
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chat_camera_choose_gallery)) },
                                                onClick = {
                                                    showAttachMenu = false
                                                    galleryLauncher.launch("image/*")
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Image, contentDescription = null)
                                                }
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = inputText,
                                        onValueChange = { inputText = it },
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(focusRequester),
                                        placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                                        enabled = !uiState.isLoading,
                                        maxLines = 3,
                                        shape = MaterialTheme.shapes.extraLarge
                                    )

                                    Spacer(modifier = Modifier.width(Spacing.sm))

                                    // Micrófono: mantener presionado para grabar la nota de voz
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (voiceRecorder.isRecording) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.primary
                                            )
                                            .pointerInput(Unit) {
                                                detectTapGestures(
                                                    onPress = {
                                                        val granted = ContextCompat.checkSelfPermission(
                                                            context,
                                                            Manifest.permission.RECORD_AUDIO
                                                        ) == PackageManager.PERMISSION_GRANTED
                                                        if (granted) {
                                                            voiceRecorder.start()
                                                            tryAwaitRelease()
                                                            voiceRecorder.stop(send = true)
                                                        } else {
                                                            // Se pide el permiso primero; la grabación
                                                            // arranca en el siguiente intento de presión.
                                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                        }
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = stringResource(R.string.chat_microphone_content_description),
                                            tint = if (voiceRecorder.isRecording) MaterialTheme.colorScheme.onError
                                            else MaterialTheme.colorScheme.onPrimary
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(Spacing.sm))

                                    FilledIconButton(
                                        onClick = {
                                            viewModel.sendMessage(inputText)
                                            inputText = ""
                                            // Keep keyboard open by requesting focus
                                            focusRequester.requestFocus()
                                        },
                                        enabled = inputText.isNotBlank() && !uiState.isLoading,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        if (uiState.isLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(Dimensions.Icon.medium),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                contentDescription = stringResource(R.string.chat_send)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(Dimensions.Component.bottomBarHeight))
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenLimitWarning(
    usagePercent: Int,
    onClearChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        usagePercent >= 95 -> MaterialTheme.colorScheme.errorContainer
        usagePercent >= 90 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val contentColor = when {
        usagePercent >= 95 -> MaterialTheme.colorScheme.onErrorContainer
        usagePercent >= 90 -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    
    val icon = when {
        usagePercent >= 95 -> Icons.Default.Error
        else -> Icons.Default.Warning
    }
    
    val message = when {
        usagePercent >= 95 -> stringResource(R.string.chat_memory_almost_full)
        usagePercent >= 90 -> stringResource(R.string.chat_memory_warning, usagePercent)
        else -> stringResource(R.string.chat_memory_usage, usagePercent)
    }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimensions.Padding.content),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(Dimensions.Icon.medium)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
            if (usagePercent >= 90) {
                TextButton(
                    onClick = onClearChat,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = contentColor
                    )
                ) {
                    Text(stringResource(R.string.chat_clear), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun DeveloperInfoCard(
    chatStats: ChatStats,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val usageHint = remember(chatStats.contextUsagePercent) {
        TokenUtils.getUsageColorHint(chatStats.contextUsagePercent)
    }
    val usageColor = when (usageHint) {
        "critical" -> MaterialTheme.colorScheme.error
        "warning" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    
    PennyWiseCardV2(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.Padding.content)
            .padding(bottom = Spacing.sm),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        onClick = { isExpanded = !isExpanded }
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.Icon.small),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        // El plural usa %1$d (conteo) y %2$s (modelo activo).
                        text = pluralStringResource(
                            R.plurals.chat_stats_model_messages,
                            chatStats.messageCount,
                            chatStats.messageCount,
                            chatStats.modelDisplayName
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.chat_stats_tokens, TokenUtils.formatNumber(chatStats.estimatedTokens)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = usageColor
                    )
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(if (isExpanded) R.string.chat_collapse else R.string.chat_expand),
                        modifier = Modifier.size(Dimensions.Icon.small),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    // Context usage
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.chat_context_usage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.chat_context_percent, chatStats.contextUsagePercent),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = usageColor
                        )
                    }

                    LinearProgressIndicator(
                        progress = { chatStats.contextUsagePercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Spacing.xs)
                            .clip(CircleShape),
                        color = usageColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        drawStopIndicator = {}
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.chat_stats_tokens_of_max, TokenUtils.formatNumber(chatStats.estimatedTokens), TokenUtils.formatNumber(chatStats.maxTokens)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (chatStats.systemPromptTokens > 0) {
                            Text(
                                text = stringResource(R.string.chat_stats_system_tokens, TokenUtils.formatNumber(chatStats.systemPromptTokens)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
    }
}

/**
 * Panel de chain-of-thought (solo developer mode). Muestra el razonamiento crudo
 * que LiteRT-LM emite por separado del texto final de la respuesta.
 */
@Composable
private fun ThinkingPanel(
    thinkingTokens: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val tokenCount = remember(thinkingTokens) { TokenUtils.estimateTokens(thinkingTokens) }

    PennyWiseCardV2(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        onClick = { expanded = !expanded }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(Dimensions.Icon.small),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.chat_thinking_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.chat_thinking_tokens, tokenCount),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.chat_thinking_collapse else R.string.chat_thinking_expand
                    ),
                    modifier = Modifier.size(Dimensions.Icon.small),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Text(
                text = thinkingTokens,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }
    }
}

/**
 * Estado del grabador de notas de voz (mantener presionado para grabar).
 * La captura PCM/WAV vive en [AudioRecorder]; aquí solo se orquestan inicio,
 * cronómetro y entrega del Uri al ViewModel.
 */
private class VoiceNoteRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onRecorded: (Uri) -> Unit,
) {
    var isRecording by mutableStateOf(false)
        private set

    var elapsedMs by mutableStateOf(0L)

    /** Inicia la captura. Devuelve false si ya había una grabación o el recorder falló. */
    fun start(): Boolean {
        if (isRecording) return false
        if (!AudioRecorder.start(context)) return false
        isRecording = true
        elapsedMs = 0L
        return true
    }

    /** Detiene la captura; con [send] true entrega el Uri para enviarlo al chat. */
    fun stop(send: Boolean) {
        if (!isRecording) return
        isRecording = false
        if (!send) {
            AudioRecorder.cancel()
            return
        }
        scope.launch {
            AudioRecorder.stop(context)?.let(onRecorded)
        }
    }
}

/** Formatea la duración de la grabación como m:ss. */
private fun formatRecordingTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private val THINKING_LINES = listOf(
    R.string.chat_thinking_reading,
    R.string.chat_thinking_working,
    R.string.chat_thinking_checking,
    R.string.chat_thinking_crunching,
    R.string.chat_thinking_one_moment
)

@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier,
    status: String? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        PennyWiseCardV2(
            modifier = Modifier.widthIn(
                max = (LocalConfiguration.current.screenWidthDp * 0.85f).dp.coerceIn(280.dp, 560.dp)
            ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Three animated dots
                val infiniteTransition = rememberInfiniteTransition(label = "typing")
                
                for (i in 0..2) {
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = keyframes {
                                durationMillis = 1200
                                0.3f at 0
                                1f at 400
                                0.3f at 800
                            },
                            repeatMode = RepeatMode.Restart,
                            initialStartOffset = StartOffset(i * 200)
                        ),
                        label = "dot_alpha_$i"
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha),
                                shape = RoundedCornerShape(50)
                            )
                    )
                }
                if (status != null) {
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    message: com.pennywiseai.tracker.data.database.entity.ChatMessage,
    isStreaming: Boolean = false
) {
    val context = LocalContext.current
    // La hora sigue la preferencia 12/24 h del sistema, no un formato fijo.
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    // La burbuja ocupa hasta 85% del ancho en móvil y se ensancha en tablet/landscape.
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.85f).dp.coerceIn(280.dp, 560.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        PennyWiseCardV2(
            modifier = Modifier
                .widthIn(max = maxBubble)
                .animateContentSize(),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            SelectionContainer {
                Text(
                    text = message.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.dp
                    )
                }
                Text(
                    text = timeFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatEmptyState(
    onPromptClick: (String) -> Unit
) {
    val examplePrompts = listOf(
        stringResource(R.string.chat_example_prompt_coffee),
        stringResource(R.string.chat_example_prompt_salary),
        stringResource(R.string.chat_example_prompt_groceries),
        stringResource(R.string.chat_example_prompt_spent)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )

        Text(
            text = stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = stringResource(R.string.chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            examplePrompts.forEach { prompt ->
                SuggestionChip(
                    onClick = { onPromptClick(prompt) },
                    label = { Text(prompt) },
                    icon = {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(Dimensions.Icon.small)
                        )
                    }
                )
            }
        }
    }
}

/** Confirm card for an action the AI proposed (#170): add, delete or update. Nothing happens without the tap. */
@Composable
private fun PendingActionCard(
    action: com.pennywiseai.tracker.data.model.PendingChatAction,
    currency: String,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // A new draft is in the base currency; an existing row is shown in its own.
    val fmt = { a: java.math.BigDecimal -> com.pennywiseai.tracker.utils.CurrencyFormatter.formatCurrency(a, currency) }
    val own = { t: com.pennywiseai.tracker.data.database.entity.TransactionEntity -> com.pennywiseai.tracker.utils.CurrencyFormatter.formatCurrency(t.amount, t.currency) }
    val income = com.pennywiseai.tracker.data.database.entity.TransactionType.INCOME
    val (title, headline, detail, button) = when (action) {
        is com.pennywiseai.tracker.data.model.PendingChatAction.Add -> {
            val d = action.draft
            listOf(
                stringResource(if (d.type == income) R.string.chat_action_add_income else R.string.chat_action_add_expense),
                "${fmt(d.amount)} · ${d.merchant}",
                stringResource(R.string.chat_action_add_detail, d.category, d.accountLabel),
                stringResource(R.string.chat_action_add)
            )
        }
        is com.pennywiseai.tracker.data.model.PendingChatAction.Delete -> {
            val t = action.transaction
            listOf(
                stringResource(R.string.chat_action_delete_title),
                "${own(t)} · ${t.merchantName}",
                "${t.category} · ${t.dateTime.toLocalDate()}",
                stringResource(R.string.chat_action_delete)
            )
        }
        is com.pennywiseai.tracker.data.model.PendingChatAction.Update -> {
            val t = action.transaction
            val changes = listOfNotNull(
                action.newMerchant?.let { stringResource(R.string.chat_action_change_merchant, it) },
                action.newCategory?.let { stringResource(R.string.chat_action_change_category, it) }
            ).joinToString(", ")
            listOf(
                stringResource(R.string.chat_action_update_title),
                "${own(t)} · ${t.merchantName}",
                "${t.category} · ${t.dateTime.toLocalDate()}\n$changes",
                stringResource(R.string.chat_action_update)
            )
        }
    }
    val isDelete = action is com.pennywiseai.tracker.data.model.PendingChatAction.Delete
    PennyWiseCardV2(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDelete) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        val fg = if (isDelete) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(text = title, style = MaterialTheme.typography.labelMedium, color = fg)
            Text(text = headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = fg)
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = fg)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = onDismiss, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.chat_cancel)) }
                Button(
                    onClick = onConfirm,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    colors = if (isDelete) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                ) { Text(button) }
            }
        }
    }
}
