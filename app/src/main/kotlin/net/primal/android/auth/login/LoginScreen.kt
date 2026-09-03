package net.primal.android.auth.login

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.primal.android.R
import net.primal.android.auth.OnboardingTestTags
import net.primal.android.auth.compose.DefaultOnboardingAvatar
import net.primal.android.auth.compose.OnboardingButton
import net.primal.android.auth.compose.defaultOnboardingAvatarBackground
import net.primal.android.core.compose.AppBarIcon
import net.primal.android.core.compose.ColumnWithBackground
import net.primal.android.core.compose.PrimalDarkTextColor
import net.primal.android.core.compose.PrimalDefaults
import net.primal.android.core.compose.SecureScreen
import net.primal.android.core.compose.PrimalGradientAlpha
import net.primal.android.core.compose.PrimalGradientBackgroundColor
import net.primal.android.core.compose.UiDensityMode
import net.primal.android.core.compose.UniversalAvatarThumbnail
import net.primal.android.core.compose.detectUiDensityModeFromMaxHeight
import net.primal.android.core.compose.foundation.keyboardVisibilityAsState
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.isCompactOrLower
import net.primal.android.core.compose.preview.PrimalPreview
import net.primal.android.core.compose.primalGradientBrush
import net.primal.android.core.compose.profile.model.ProfileDetailsUi
import net.primal.android.core.utils.pasteText
import net.primal.android.signer.bunker.isBunkerUrl
import net.primal.android.signer.client.launchGetPublicKey
import net.primal.android.signer.client.rememberAmberPubkeyLauncher
import net.primal.android.stream.player.hideStreamMiniPlayer
import net.primal.android.theme.AppTheme
import net.primal.android.theme.domain.PrimalTheme
import net.primal.android.user.domain.CredentialType
import net.primal.domain.nostr.utils.isValidNostrPrivateKey
import net.primal.domain.nostr.utils.isValidNostrPublicKey

@Composable
fun LoginScreen(viewModel: LoginViewModel, callbacks: LoginContract.ScreenCallbacks) {
    LaunchedEffect(viewModel, callbacks) {
        viewModel.effect.collect {
            when (it) {
                is LoginContract.SideEffect.LoginSuccess -> callbacks.onLoginSuccess()
            }
        }
    }

    LaunchedErrorHandler(viewModel = viewModel)

    hideStreamMiniPlayer()
    val uiState = viewModel.state.collectAsState()
    LoginScreen(
        state = uiState.value,
        eventPublisher = { viewModel.setEvent(it) },
        callbacks = callbacks,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    state: LoginContract.UiState,
    eventPublisher: (LoginContract.UiEvent) -> Unit,
    callbacks: LoginContract.ScreenCallbacks,
) {
    SecureScreen()
    val keyboardController = LocalSoftwareKeyboardController.current
    val onClose = {
        keyboardController?.hide()
        callbacks.onClose()
    }

    val context = LocalContext.current
    val pubkeyLauncher = rememberAmberPubkeyLauncher(
        onFailure = { eventPublisher(LoginContract.UiEvent.ResetLoginState) },
    ) { pubkey ->
        eventPublisher(
            LoginContract.UiEvent.LoginRequestEvent(
                nostrKey = pubkey,
                credentialType = CredentialType.ExternalSigner,
            ),
        )
    }

    BackHandler(enabled = state.loading) { }
    ColumnWithBackground(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        backgroundBrushProvider = ::primalGradientBrush,
        brushAlpha = PrimalGradientAlpha,
        backgroundColor = PrimalGradientBackgroundColor,
    ) { size ->
        val uiMode = size.height.detectUiDensityModeFromMaxHeight()

        CenterAlignedTopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = PrimalDarkTextColor,
                navigationIconContentColor = PrimalDarkTextColor,
            ),
            title = {
                Text(text = stringResource(id = R.string.login_title))
            },
            navigationIcon = {
                if (!state.loading) {
                    AppBarIcon(
                        icon = PrimalIcons.ArrowBack,
                        onClick = onClose,
                    )
                }
            },
        )

        LoginContent(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 32.dp),
            state = state,
            uiMode = uiMode,
            onLoginInputChanged = { eventPublisher(LoginContract.UiEvent.UpdateLoginInput(newInput = it)) },
            onLoginClick = { eventPublisher(LoginContract.UiEvent.LoginRequestEvent()) },
            onLoginWithAmberClick = {
                try {
                    pubkeyLauncher.launchGetPublicKey()
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.app_error_amber_unavailable),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onLoginWithBunkerClick = { bunkerUrl ->
                eventPublisher(LoginContract.UiEvent.LoginWithBunkerEvent(bunkerUrl = bunkerUrl))
            },
        )
    }
}

private enum class LoginEntryMode { Amber, Nsec, Bunker }

@Composable
fun LoginContent(
    modifier: Modifier = Modifier,
    state: LoginContract.UiState,
    uiMode: UiDensityMode,
    onLoginInputChanged: (String) -> Unit,
    onLoginClick: () -> Unit,
    onLoginWithAmberClick: () -> Unit,
    onLoginWithBunkerClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val keyboardVisible by keyboardVisibilityAsState()
    var loginEntryMode by rememberSaveable { mutableStateOf(LoginEntryMode.Amber) }

    fun pasteFromClipboard() {
        val clipboardText = context.pasteText().trim()
        if (clipboardText.isValidNostrPrivateKey() || clipboardText.isValidNostrPublicKey()) {
            onLoginInputChanged(clipboardText)
            keyboardController?.hide()
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (loginEntryMode) {
                LoginEntryMode.Nsec -> LoginInputFieldContent(
                    state = state,
                    uiMode = uiMode,
                    keyboardVisible = keyboardVisible,
                    onLoginInputChanged = onLoginInputChanged,
                    onLoginClick = onLoginClick,
                )

                LoginEntryMode.Bunker -> BunkerInputFieldContent(
                    loading = state.loading,
                    onConnectClick = onLoginWithBunkerClick,
                )

                LoginEntryMode.Amber -> AmberPrimaryContent(
                    loading = state.loading,
                    onLoginWithAmberClick = onLoginWithAmberClick,
                    onNsecModeClick = { loginEntryMode = LoginEntryMode.Nsec },
                    onBunkerModeClick = { loginEntryMode = LoginEntryMode.Bunker },
                )
            }
        }

        if (loginEntryMode == LoginEntryMode.Nsec) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .wrapContentHeight(align = Alignment.Bottom),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
            ) {
                OnboardingButton(
                    text = when {
                        state.isValidKey -> stringResource(id = R.string.login_button_sign_in)
                        state.loginInput.isEmpty() -> stringResource(id = R.string.login_button_paste_your_key)
                        else -> stringResource(id = R.string.login_button_paste_new_key)
                    },
                    modifier = Modifier
                        .run {
                            if (state.isValidKey) testTag(OnboardingTestTags.LOGIN_SIGN_IN_BUTTON) else this
                        }
                        .fillMaxWidth()
                        .height(56.dp)
                        .align(alignment = Alignment.CenterHorizontally),
                    loading = state.loading,
                    enabled = !state.loading,
                    onClick = {
                        keyboardController?.hide()
                        if (state.isValidKey) onLoginClick() else pasteFromClipboard()
                    },
                )
            }
        }
    }
}

@Composable
private fun AmberPrimaryContent(
    loading: Boolean,
    onLoginWithAmberClick: () -> Unit,
    onNsecModeClick: () -> Unit,
    onBunkerModeClick: () -> Unit,
) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(id = R.string.login_amber_primary_hint),
        style = AppTheme.typography.bodyLarge,
        color = PrimalDarkTextColor.copy(alpha = 0.86f),
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(20.dp))
    OnboardingButton(
        text = stringResource(id = R.string.login_with_amber_button),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        loading = loading,
        enabled = !loading,
        onClick = onLoginWithAmberClick,
    )
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(id = R.string.login_security_notice),
        style = AppTheme.typography.bodySmall,
        color = PrimalDarkTextColor.copy(alpha = 0.78f),
        textAlign = TextAlign.Center,
    )
    TextButton(enabled = !loading, onClick = onNsecModeClick) {
        Text(text = stringResource(id = R.string.login_nsec_unsafe))
    }
    TextButton(enabled = !loading, onClick = onBunkerModeClick) {
        Text(text = stringResource(id = R.string.login_with_bunker))
    }
}

@Composable
private fun BunkerInputFieldContent(loading: Boolean, onConnectClick: (String) -> Unit) {
    var bunkerInput by rememberSaveable { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isValidBunkerUrl = bunkerInput.isBunkerUrl()

    fun submit() {
        keyboardController?.hide()
        if (isValidBunkerUrl) onConnectClick(bunkerInput)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 32.dp),
            text = stringResource(id = R.string.login_enter_bunker_url),
            textAlign = TextAlign.Center,
            style = AppTheme.typography.bodyMedium,
            color = PrimalDarkTextColor,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = Color.White, shape = AppTheme.shapes.extraLarge)
                .padding(all = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                value = bunkerInput,
                onValueChange = { bunkerInput = it.trim() },
                placeholder = {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(id = R.string.login_bunker_url_hint),
                        textAlign = TextAlign.Center,
                        style = AppTheme.typography.bodyLarge,
                        color = AppTheme.extraColorScheme.onSurfaceVariantAlt4,
                    )
                },
                singleLine = true,
                isError = bunkerInput.isNotEmpty() && !isValidBunkerUrl,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                textStyle = AppTheme.typography.titleLarge.copy(fontSize = 16.sp, color = Color.Black),
                colors = loginTextFieldColors(keyboardVisible = true, loginInput = bunkerInput),
                shape = AppTheme.shapes.extraLarge,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        OnboardingButton(
            text = stringResource(id = R.string.login_bunker_button_connect),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            loading = loading,
            enabled = !loading && isValidBunkerUrl,
            onClick = ::submit,
        )
    }
}

@Composable
private fun LoginInputFieldContent(
    state: LoginContract.UiState,
    uiMode: UiDensityMode,
    keyboardVisible: Boolean,
    onLoginInputChanged: (String) -> Unit,
    onLoginClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = state.profileDetails,
            label = "LoginHeader",
        ) { profileDetails ->
            when {
                profileDetails != null && !state.fetchingProfileDetails -> {
                    ProfileDetailsColumn(
                        modifier = Modifier.fillMaxWidth(),
                        uiMode = uiMode,
                        keyboardVisible = keyboardVisible,
                        profileDetails = profileDetails,
                    )
                }

                else -> {
                    EnterYourKeyNotice(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        loginInput = state.loginInput,
                    )
                }
            }
        }

        LoginInputField(
            modifier = Modifier.fillMaxWidth(),
            loginInput = state.loginInput,
            isValidKey = state.isValidKey,
            keyboardVisible = keyboardVisible,
            onLoginInputChanged = onLoginInputChanged,
            onLoginClick = onLoginClick,
        )
    }
}

@Composable
private fun LoginInputField(
    modifier: Modifier = Modifier,
    isValidKey: Boolean,
    loginInput: String,
    keyboardVisible: Boolean,
    onLoginInputChanged: (String) -> Unit,
    onLoginClick: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = if (keyboardVisible) AppTheme.shapes.medium else AppTheme.shapes.extraLarge
    Row(
        modifier = modifier
            .background(color = Color.White, shape = shape)
            .padding(all = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!keyboardVisible) Modifier.height(56.dp) else Modifier),
            value = if (keyboardVisible) loginInput else "",
            onValueChange = { input -> onLoginInputChanged(input.trim()) },
            placeholder = {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = when {
                        loginInput.isEmpty() -> stringResource(id = R.string.nsec_or_npub)
                        else -> "••••••••••••••••••••••••••••••••••••••"
                    },
                    textAlign = TextAlign.Center,
                    style = AppTheme.typography.bodyLarge.copy(
                        fontSize = if (loginInput.isEmpty()) 16.sp else 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (loginInput.isEmpty()) {
                        AppTheme.extraColorScheme.onSurfaceVariantAlt4
                    } else {
                        Color.Black
                    },
                )
            },
            isError = loginInput.isNotEmpty() && !isValidKey,
            keyboardOptions = KeyboardOptions(
                imeAction = if (isValidKey) ImeAction.Go else ImeAction.Default,
                keyboardType = KeyboardType.Password,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    if (isValidKey) {
                        keyboardController?.hide()
                        onLoginClick()
                    }
                },
            ),
            visualTransformation = PasswordVisualTransformation(),
            textStyle = AppTheme.typography.titleLarge.copy(
                fontSize = if (keyboardVisible) 16.sp else 28.sp,
                lineHeight = if (keyboardVisible) 16.sp else 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black,
            ),
            colors = loginTextFieldColors(keyboardVisible, loginInput),
            shape = shape,
        )
    }
}

@Composable
private fun loginTextFieldColors(keyboardVisible: Boolean, loginInput: String) =
    PrimalDefaults.outlinedTextFieldColors(
        cursorColor = if (keyboardVisible) AppTheme.colorScheme.primary else Color.White,
        focusedContainerColor = Color.White,
        focusedBorderColor = when {
            loginInput.isEmpty() -> Color.White
            else -> AppTheme.extraColorScheme.successBright
        },
        unfocusedContainerColor = Color.White,
        unfocusedBorderColor = when {
            loginInput.isEmpty() -> Color.White
            else -> AppTheme.extraColorScheme.successBright
        },
        disabledContainerColor = Color.White,
        disabledBorderColor = when {
            loginInput.isEmpty() -> Color.White
            else -> AppTheme.extraColorScheme.successBright
        },
        errorContainerColor = Color.White,
        errorBorderColor = AppTheme.colorScheme.error,
    )

@Composable
private fun EnterYourKeyNotice(modifier: Modifier = Modifier, loginInput: String) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            modifier = Modifier
                .padding(vertical = 16.dp, horizontal = 32.dp)
                .fillMaxWidth(),
            text = when {
                loginInput.isEmpty() -> stringResource(id = R.string.login_enter_nsec_key)
                else -> stringResource(id = R.string.login_invalid_nsec_key)
            },
            textAlign = TextAlign.Center,
            style = AppTheme.typography.bodyMedium,
            color = PrimalDarkTextColor,
        )
    }
}

@Composable
private fun ProfileDetailsColumn(
    modifier: Modifier = Modifier,
    uiMode: UiDensityMode,
    keyboardVisible: Boolean,
    profileDetails: ProfileDetailsUi,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (!(uiMode.isCompactOrLower() && keyboardVisible)) {
            UniversalAvatarThumbnail(
                avatarCdnImage = profileDetails.avatarCdnImage,
                avatarSize = 100.dp,
                hasBorder = profileDetails.avatarCdnImage != null,
                avatarBlossoms = profileDetails.profileBlossoms,
                fallbackBorderColor = Color.White,
                backgroundColor = defaultOnboardingAvatarBackground,
                defaultAvatar = { DefaultOnboardingAvatar() },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            modifier = Modifier.padding(bottom = 4.dp),
            text = profileDetails.userDisplayName,
            style = AppTheme.typography.bodyLarge.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = PrimalDarkTextColor,
        )

        Text(
            text = profileDetails.internetIdentifier ?: "",
            style = AppTheme.typography.bodyLarge,
            color = PrimalDarkTextColor,
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
@Deprecated("Replace with SnackbarErrorHandler")
fun LaunchedErrorHandler(viewModel: LoginViewModel) {
    val genericMessage = stringResource(id = R.string.app_generic_error)
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.state
            .filter { it.error != null }
            .map { it.error }
            .filterNotNull()
            .collect {
                uiScope.launch {
                    Toast.makeText(
                        context,
                        genericMessage,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
    }
}

@Preview
@Composable
fun PreviewLoginScreen() {
    PrimalPreview(primalTheme = PrimalTheme.Midnight) {
        LoginScreen(
            state = LoginContract.UiState(loading = false),
            eventPublisher = {},
            callbacks = LoginContract.ScreenCallbacks(
                onLoginSuccess = {},
                onClose = {},
            ),
        )
    }
}
