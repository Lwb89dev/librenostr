package net.primal.android.core.compose

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.primal.android.BuildConfig
import net.primal.android.core.compose.icons.LibreNavigationIcons
import net.primal.android.theme.AppTheme

@Composable
fun MediaPickerIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color,
    onMediaSelected: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    var pickerVisible by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickerVisible = false
        if (uri != null) onMediaSelected(listOf(uri))
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        pickerVisible = false
        pendingCameraUri?.takeIf { captured }?.let { onMediaSelected(listOf(it)) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCameraUri?.let(cameraLauncher::launch)
    }

    IconButton(onClick = { pickerVisible = true }) {
        Icon(imageVector = imageVector, contentDescription = contentDescription, tint = tint)
    }

    if (pickerVisible) {
        AlertDialog(
            onDismissRequest = { pickerVisible = false },
            containerColor = AppTheme.colorScheme.surfaceVariant,
            title = { Text("Add media", style = AppTheme.typography.titleMedium) },
            text = {
                androidx.compose.foundation.layout.Column {
                    ListItem(
                        headlineContent = { Text("Photo or video from device") },
                        leadingContent = { Icon(imageVector = LibreNavigationIcons.Gallery, contentDescription = null, tint = tint) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = androidx.compose.ui.Modifier.clickable {
                            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Camera") },
                        leadingContent = { Icon(imageVector = LibreNavigationIcons.Camera, contentDescription = null, tint = tint) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = androidx.compose.ui.Modifier.clickable {
                            val file = File.createTempFile(
                                "JPEG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}_",
                                ".jpg",
                                context.externalCacheDir,
                            )
                            val cameraUri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
                            pendingCameraUri = cameraUri
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                cameraLauncher.launch(cameraUri)
                            } else {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                    )
                }
            },
            confirmButton = {},
        )
    }
}
