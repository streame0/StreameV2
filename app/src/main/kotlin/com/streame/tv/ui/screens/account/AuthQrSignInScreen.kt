package com.streame.tv.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streame.tv.data.repository.SupabaseAuthState
import com.streame.tv.ui.components.QrCodeImage

@Composable
fun AuthQrSignInScreen(
    onBackPress: () -> Unit = {},
    onContinue: (() -> Unit)? = null,
    onEmailSignIn: (() -> Unit)? = null,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val fullAccount = uiState.authState as? SupabaseAuthState.FullAccount
    val isSignedIn = fullAccount != null
    val isOnboardingMode = onContinue != null
    val isApproved = remember(uiState.qrLoginStatus) {
        uiState.qrLoginStatus?.contains("approved", ignoreCase = true) == true
    }

    // Auto-start QR login when not signed in and no code yet
    LaunchedEffect(uiState.authState, isSignedIn, uiState.qrLoginCode, uiState.isLoading) {
        if (uiState.authState !is SupabaseAuthState.Loading && !isSignedIn &&
            uiState.qrLoginCode.isNullOrBlank() && !uiState.isLoading) {
            viewModel.startQrLogin()
        }
    }

    LaunchedEffect(isSignedIn) {
        if (isSignedIn && !uiState.qrLoginCode.isNullOrBlank()) {
            viewModel.clearQrLoginSession()
        }
    }

    LaunchedEffect(isApproved, uiState.isLoading) {
        if (isApproved && !uiState.isLoading) {
            viewModel.exchangeQrLogin()
        }
    }

    LaunchedEffect(isOnboardingMode, isSignedIn) {
        if (isOnboardingMode && isSignedIn) {
            viewModel.clearQrLoginSession()
            onContinue?.invoke()
        }
    }

    // Countdown timer
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(uiState.qrLoginCode) {
        while (uiState.qrLoginCode != null) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val remainingMillis = uiState.qrLoginExpiresAtMillis?.let { (it - nowMillis).coerceAtLeast(0L) } ?: 0L
    val remainingSeconds = (remainingMillis / 1000).toInt()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Sign in with QR Code",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (isSignedIn) {
                Text(
                    text = "Connected!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF7CFF9B)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = fullAccount.email,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            } else {
                Text(
                    text = "Scan with your phone to sign in",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(24.dp))

                if (!uiState.qrLoginCode.isNullOrBlank() && !uiState.qrLoginWebUrl.isNullOrBlank()) {
                    val qrData = uiState.qrLoginWebUrl!!
                    QrCodeImage(
                        data = qrData,
                        sizePx = 400,
                        modifier = Modifier
                            .size(200.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Code: ${uiState.qrLoginCode}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color.White
                    )
                    if (remainingSeconds > 0) {
                        Text(
                            text = "Expires in ${remainingSeconds}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                } else if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Generating QR code...", color = Color.White.copy(alpha = 0.5f))
                }

                uiState.qrLoginStatus?.let { status ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = error,
                    color = Color(0xFFFF6E6E),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (isSignedIn) viewModel.signOut()
                        else viewModel.startQrLogin()
                    },
                    enabled = !uiState.isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        when {
                            isSignedIn -> "Sign Out"
                            uiState.isLoading -> "Please wait..."
                            else -> "Refresh QR"
                        }
                    )
                }
                Button(
                    onClick = {
                        viewModel.clearQrLoginSession()
                        if (onContinue != null) onContinue()
                        else onBackPress()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White
                    )
                ) {
                    Text(if (onContinue != null && !isSignedIn) "Skip" else "Back")
                }
            }

            if (!isSignedIn && onEmailSignIn != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onEmailSignIn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White.copy(alpha = 0.6f)
                    )
                ) {
                    Text("Sign in with email instead")
                }
            }
        }
    }
}
