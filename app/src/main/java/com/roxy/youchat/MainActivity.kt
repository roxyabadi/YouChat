package com.roxy.youchat

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.photopicker.compose.EmbeddedPhotoPicker
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.photopicker.compose.ExperimentalPhotoPickerComposeApi
import androidx.photopicker.compose.rememberEmbeddedPhotoPickerState
import coil.compose.AsyncImage
import com.roxy.youchat.ui.theme.HelloWorldTheme
import kotlin.math.abs

// ========================================================================================
// DATA MODELS & ENUMS
// ========================================================================================

/**
 * Represents the types of messages supported in the chat.
 */
enum class MessageType {
    TEXT, IMAGE, VIDEO
}

/**
 * UI State for the Message object.
 * @param text Optional text content of the message.
 * @param isFromMe Boolean to determine bubble alignment (Right for 'me', Left for 'others').
 * @param mediaUri Optional Uri for image or video content.
 * @param type The [MessageType] determining how to render the content.
 */
data class Message(
    val text: String? = null,
    val isFromMe: Boolean,
    val mediaUri: Uri? = null,
    val type: MessageType = MessageType.TEXT
)

/**
 * Represents the visual state of the Embedded Photo Picker.
 */
enum class PickerUIState {
    CLOSED,    // Picker is hidden
    COLLAPSED, // Picker takes up ~1/3 of the screen
    EXPANDED   // Picker takes up the full screen height
}

// ========================================================================================
// MAIN ACTIVITY
// ========================================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Take manual control of system windows to handle keyboard (IME) insets precisely.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContent {
            HelloWorldTheme {
                ChatScreen()
            }
        }
    }
}

// ========================================================================================
// MAIN UI COMPONENT (State Management & Orchestration)
// ========================================================================================

/**
 * The primary Chat Screen. This composable manages the heavy lifting of state for:
 * 1. Chat history (list of messages).
 * 2. Embedded Photo Picker state and its system requirements.
 * 3. Media selection and preview flow.
 * 4. Nested scrolling logic for the picker's expansion/collapse.
 */
@SuppressLint("NewApi")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPhotoPickerComposeApi::class)
@Composable
fun ChatScreen() {
    // ------------------------------------------------------------------------------------
    // STATE VARIABLES
    // ------------------------------------------------------------------------------------
    
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    
    // The list of messages in the chat.
    val messages = remember {
        mutableStateListOf(
            Message("Hey there!", false),
            Message("Hi Taylor! How's it going?", true),
            Message("Good! Just working on a new project.", false),
            Message("That sounds exciting! What is it?", true),
            Message("A simple messaging app UI with Compose.", false)
        )
    }

    // Tracks currently selected media from the picker.
    val selectedUris = remember { mutableStateListOf<Uri>() }
    // Tracks which selected item is currently being previewed.
    var currentPreviewIndex by remember { mutableIntStateOf(0) }
    
    // Controls the visibility and size of the Embedded Photo Picker.
    var pickerUIState by remember { mutableStateOf(PickerUIState.CLOSED) }
    
    /**
     * EDUCATIONAL NOTE: pickerResetKey
     * We use this integer as a 'key' for the EmbeddedPhotoPicker state. 
     * When we want to completely clear the picker's internal selection or 'reset' it 
     * (e.g., after sending or canceling), we increment this key. Compose detects 
     * the change and re-initializes the state, ensuring no stale UI artifacts remain.
     */
    var pickerResetKey by remember { mutableIntStateOf(0) }

    /**
     * EDUCATIONAL NOTE: System Requirements
     * The Embedded Photo Picker is a modern Android feature. It requires:
     * 1. Android 14 (API 34) or higher.
     * 2. SdkExtensions version 15 or higher.
     * Always check these before attempting to initialize the picker state.
     */
    val isExtensionSupported = remember(isPreview) {
        if (isPreview) false else {
            try {
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 15
            } catch (_: Exception) {
                false
            } catch (_: NoClassDefFoundError) {
                false
            }
        }
    }

    // We use Any? to avoid EmbeddedPhotoPickerFeatureInfo appearing in synthetic method signatures
    // which can cause NoClassDefFoundError in Android Studio Preview.
    val featureInfo: Any? = remember(isExtensionSupported) {
        if (isExtensionSupported) {
            EmbeddedPhotoPickerFeatureInfo.Builder()
                .setMaxSelectionLimit(10)
                .setOrderedSelection(true)
                .build()
        } else null
    }

    val context = LocalContext.current

    /**
     * Initialize the Photo Picker State.
     * This handles the core logic of granting/revoking URI permissions as the user 
     * interacts with the embedded grid.
     */
    val pickerState = if (!isPreview && isExtensionSupported) {
        rememberEmbeddedPhotoPickerState(
            pickerResetKey,
            onUriPermissionGranted = { uris ->
                var lastAddedIndex = -1
                uris.forEach { uri ->
                    if (!selectedUris.contains(uri)) {
                        selectedUris.add(uri)
                        lastAddedIndex = selectedUris.size - 1
                    }
                }
                if (lastAddedIndex != -1) {
                    currentPreviewIndex = lastAddedIndex
                }
                // Auto-collapse the chat and show preview if a selection is made.
                if (selectedUris.isNotEmpty() && pickerUIState == PickerUIState.CLOSED) {
                    pickerUIState = PickerUIState.COLLAPSED
                }
            },
            onUriPermissionRevoked = { uris ->
                val currentUri = if (currentPreviewIndex in selectedUris.indices) selectedUris[currentPreviewIndex] else null
                selectedUris.removeAll(uris)
                
                if (selectedUris.isEmpty()) {
                    if (pickerUIState != PickerUIState.EXPANDED) {
                        pickerUIState = PickerUIState.CLOSED
                    }
                    currentPreviewIndex = 0
                } else {
                    val newIndex = selectedUris.indexOf(currentUri)
                    currentPreviewIndex = if (newIndex != -1) newIndex else 0
                }
            },
            onSelectionComplete = {
                // When the user is done with the full-screen view, collapse it back.
                if (pickerUIState == PickerUIState.EXPANDED) {
                    pickerUIState = PickerUIState.COLLAPSED
                }
            }
        )
    } else null

    // Synchronize the picker's internal 'expanded' state with our UI state.
    LaunchedEffect(pickerUIState) {
        if (isExtensionSupported && pickerState != null) {
            pickerState.setCurrentExpanded(pickerUIState == PickerUIState.EXPANDED)
        }
    }

    // Ensure the preview index doesn't go out of bounds if items are removed.
    LaunchedEffect(selectedUris.size) {
        if (currentPreviewIndex >= selectedUris.size && selectedUris.isNotEmpty()) {
            currentPreviewIndex = 0
        }
    }

    /**
     * EDUCATIONAL NOTE: Nested Scroll Logic
     * This connection allows us to coordinate scrolling between the Photo Picker's 
     * internal scrollable grid and the outer container.
     */
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            /**
             * onPreScroll: Intercepts the scroll event BEFORE the child (the picker grid) sees it.
             * If the user drags UP on a collapsed picker, we expand the picker instead 
             * of scrolling the grid inside it.
             */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -5f && pickerUIState == PickerUIState.COLLAPSED) {
                    pickerUIState = PickerUIState.EXPANDED
                    return Offset(0f, available.y) // Consume the scroll
                }
                return Offset.Zero
            }

            /**
             * onPostScroll: Handles scroll events AFTER the child (the picker grid) has 
             * consumed what it can. If the user scrolls DOWN and the grid is already 
             * at the top (consumed.y == 0), we collapse or close the picker.
             */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = available.y
                if (delta > 10f && consumed.y == 0f) {
                    if (pickerUIState == PickerUIState.EXPANDED) {
                        pickerUIState = PickerUIState.COLLAPSED
                        return Offset(0f, delta)
                    } else if (pickerUIState == PickerUIState.COLLAPSED && selectedUris.isEmpty()) {
                        pickerUIState = PickerUIState.CLOSED
                        return Offset(0f, delta)
                    }
                }
                return Offset.Zero
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // UI LAYOUT
    // ------------------------------------------------------------------------------------

    Scaffold(
        // Restore the beige background color and disable default inset handling to prevent layout jumps.
        containerColor = Color(0xFFECE5DD),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "User Icon",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Taylor", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(if (selectedUris.isNotEmpty()) Color.Black else Color(0xFFECE5DD))
        ) {
            val maxHeight = maxHeight
            val collapsedHeight = maxHeight / 3

            Column(modifier = Modifier.fillMaxSize()) {
                val isPreviewVisible = selectedUris.isNotEmpty() && pickerUIState != PickerUIState.EXPANDED
                
                if (isPreviewVisible) {
                    // Show media preview when items are selected but picker isn't full screen.
                    Box(modifier = Modifier.weight(1f)) {
                        MultiMediaPreview(
                            uris = selectedUris,
                            currentIndex = currentPreviewIndex,
                            onIndexChange = { currentPreviewIndex = it },
                            onSend = {
                                selectedUris.forEach { uri ->
                                    val type = if (context.contentResolver.getType(uri)?.contains("video") == true) {
                                        MessageType.VIDEO
                                    } else {
                                        MessageType.IMAGE
                                    }
                                    messages.add(Message(mediaUri = uri, isFromMe = true, type = type))
                                }
                                // Reset state after sending
                                selectedUris.clear()
                                currentPreviewIndex = 0
                                pickerUIState = PickerUIState.CLOSED
                                pickerResetKey++
                            },
                            onCancel = {
                                // Reset state on cancel
                                selectedUris.clear()
                                currentPreviewIndex = 0
                                pickerUIState = PickerUIState.CLOSED
                                pickerResetKey++
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(collapsedHeight))
                } else {
                    // Show standard chat list when not in preview mode.
                    MessageList(messages = messages, modifier = Modifier.weight(1f))
                    
                    val animatedSpacerHeight by animateDpAsState(
                        targetValue = if (pickerUIState == PickerUIState.COLLAPSED) collapsedHeight else 0.dp,
                        label = "SpacerHeight"
                    )

                    if (pickerUIState != PickerUIState.EXPANDED) {
                        // Dynamically apply insets and background based on picker state.
                        // When CLOSED, we need navigation padding to respect the system bar.
                        // When COLLAPSED, the picker handles the bottom edge, so we omit it to avoid dead space.
                        val inputModifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (pickerUIState == PickerUIState.CLOSED) {
                                    Modifier
                                        .background(Color(0xFFECE5DD))
                                        .navigationBarsPadding()
                                } else Modifier
                            )
                            .imePadding()

                        Box(inputModifier) {
                            ChatInput(
                                onMessageSent = { text ->
                                    messages.add(Message(text = text, isFromMe = true, type = MessageType.TEXT))
                                },
                                onTogglePicker = {
                                    if (isExtensionSupported) {
                                        pickerUIState = if (pickerUIState == PickerUIState.CLOSED) {
                                            PickerUIState.COLLAPSED
                                        } else {
                                            PickerUIState.CLOSED
                                        }
                                    }
                                },
                                pickerUIState = pickerUIState
                            )
                        }
                    }

                    // Spacer to push content up when picker is collapsed.
                    Spacer(modifier = Modifier.height(animatedSpacerHeight))
                }
            }

            // The Embedded Photo Picker Surface
            val showPickerSurface = (isExtensionSupported || isPreview) && 
                                   (pickerUIState != PickerUIState.CLOSED || selectedUris.isNotEmpty())

            if (showPickerSurface) {
                val targetHeight = if (pickerUIState == PickerUIState.EXPANDED) maxHeight else collapsedHeight
                val animatedHeight by animateDpAsState(targetValue = targetHeight, label = "PickerHeight")

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(animatedHeight)
                        .pointerInput(pickerUIState) {
                            // Manual gesture handling for expansion when the picker is collapsed.
                            if (pickerUIState == PickerUIState.EXPANDED) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.first()
                                    if (change.pressed) {
                                        val diffY = change.position.y - change.previousPosition.y
                                        val diffX = change.position.x - change.previousPosition.x
                                        if (pickerUIState == PickerUIState.COLLAPSED && diffY < -5f && abs(diffY) > abs(diffX)) {
                                            pickerUIState = PickerUIState.EXPANDED
                                            change.consume()
                                            break
                                        }
                                    } else break
                                }
                            }
                        }
                        .nestedScroll(nestedScrollConnection),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column {
                        // Drag bar and Collapse button
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        if (pickerUIState == PickerUIState.COLLAPSED) {
                                            pickerUIState = PickerUIState.EXPANDED
                                        }
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        if (dragAmount.y > 10f && pickerUIState == PickerUIState.EXPANDED) {
                                            pickerUIState = PickerUIState.COLLAPSED
                                            change.consume()
                                        } else if (dragAmount.y < -10f && pickerUIState == PickerUIState.COLLAPSED) {
                                            pickerUIState = PickerUIState.EXPANDED
                                            change.consume()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp, 4.dp)
                                    .clip(CircleShape)
                                    .background(Color.LightGray)
                            )
                            if (pickerUIState == PickerUIState.EXPANDED) {
                                IconButton(
                                    onClick = { pickerUIState = PickerUIState.COLLAPSED },
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Collapse Picker"
                                    )
                                }
                            }
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isExtensionSupported && pickerState != null && featureInfo != null) {
                                EmbeddedPhotoPicker(
                                    state = pickerState,
                                    embeddedPhotoPickerFeatureInfo = featureInfo as EmbeddedPhotoPickerFeatureInfo
                                )
                            } else if (isPreview) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Photo Picker Placeholder",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ========================================================================================
// HELPER COMPONENTS (Stateless UI)
// ========================================================================================

/**
 * A media previewer that supports images and videos with navigation controls.
 */
@Composable
fun MultiMediaPreview(
    uris: List<Uri>,
    currentIndex: Int,
    onIndexChange: (Int) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val currentUri = uris[currentIndex]
    val isVideo = remember(currentUri) {
        context.contentResolver.getType(currentUri)?.contains("video") == true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isVideo) {
            VideoPlayerItem(uri = currentUri)
        } else {
            AsyncImage(
                model = currentUri,
                contentDescription = "Preview Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        // Left/Right navigation for multiple selections
        if (uris.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { onIndexChange(if (currentIndex > 0) currentIndex - 1 else uris.size - 1) },
                    modifier = Modifier.background(Color.Black.copy(0.4f), CircleShape)
                ) {
                    Icon(Icons.Default.ChevronLeft, "Previous", tint = Color.White)
                }
                IconButton(
                    onClick = { onIndexChange(if (currentIndex < uris.size - 1) currentIndex + 1 else 0) },
                    modifier = Modifier.background(Color.Black.copy(0.4f), CircleShape)
                ) {
                    Icon(Icons.Default.ChevronRight, "Next", tint = Color.White)
                }
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("Cancel", color = Color.White)
            }
            Button(onClick = onSend) {
                Text("Send (${uris.size})")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            }
        }
    }
}

/**
 * Wraps Media3 ExoPlayer for video playback.
 * 
 * EDUCATIONAL NOTE: ExoPlayer Lifecycle
 * We use `DisposableEffect` to manage the lifecycle of the ExoPlayer instance. 
 * Since ExoPlayer holds onto heavy system resources (hardware decoders, memory), 
 * failing to call `release()` when the composable leaves the screen will lead to 
 * memory leaks and performance degradation.
 */
@Composable
fun VideoPlayerItem(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false // Wait for user tap
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    val isPlayingState = remember(uri) { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayingState.value = isPlaying
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release() // CRITICAL: Free up resources
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay play icon when paused
        if (!isPlayingState.value) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Paused",
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    .padding(16.dp),
                tint = Color.White
            )
        }
    }
}

/**
 * Scrollable list of chat messages.
 */
@Composable
fun MessageList(messages: List<Message>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // Auto-scroll to the bottom when a new message arrives.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages) { message -> MessageBubble(message) }
    }
}

/**
 * Individual message bubble supporting text, image, and video content.
 */
@Composable
fun MessageBubble(message: Message) {
    val alignment = if (message.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (message.isFromMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (message.isFromMe) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    val shape = if (message.isFromMe) {
        RoundedCornerShape(12.dp, 12.dp, 0.dp, 12.dp)
    } else {
        RoundedCornerShape(12.dp, 12.dp, 12.dp, 0.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Surface(
            color = backgroundColor,
            contentColor = contentColor,
            shape = shape,
            shadowElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(all = 4.dp)) {
                if (message.mediaUri != null) {
                    Box(
                        modifier = Modifier
                            .sizeIn(maxWidth = 240.dp, maxHeight = 320.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (message.type == MessageType.VIDEO) {
                            VideoPlayerItem(uri = message.mediaUri)
                        } else {
                            AsyncImage(
                                model = message.mediaUri,
                                contentDescription = "Message Image",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
                if (message.text != null) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 16.sp,
                        color = contentColor
                    )
                }
            }
        }
    }
}

/**
 * Bottom input bar for typing messages and toggling the media picker.
 */
@Composable
fun ChatInput(
    onMessageSent: (String) -> Unit,
    onTogglePicker: () -> Unit,
    pickerUIState: PickerUIState
) {
    var text by remember { mutableStateOf("") }
    // Modifier.imePadding() moved to caller's container to ensure the background fills correctly.
    Surface(tonalElevation = 4.dp) {
        Crossfade(targetState = pickerUIState, label = "InputBarTransition") { state ->
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state == PickerUIState.COLLAPSED) {
                    // Show keyboard toggle when the picker is open.
                    IconButton(onClick = onTogglePicker) {
                        Icon(Icons.Default.Keyboard, "Switch to Keyboard", tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    // Standard text input bar.
                    IconButton(onClick = onTogglePicker) {
                        Icon(Icons.Default.AddPhotoAlternate, "Toggle Picker", tint = MaterialTheme.colorScheme.primary)
                    }
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        )
                    )
                    IconButton(onClick = { if (text.isNotBlank()) { onMessageSent(text); text = "" } }) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@SuppressLint("NewApi")
@Preview(showBackground = true)
@Composable
fun ChatScreenPreview() {
    HelloWorldTheme { ChatScreen() }
}
