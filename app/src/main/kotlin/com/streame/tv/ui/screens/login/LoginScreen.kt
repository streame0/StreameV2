package com.streame.tv.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streame.tv.data.repository.AuthState
import com.streame.tv.data.repository.AuthState.*
import com.streame.tv.ui.theme.StreameTypography

/**
 * Login Screen - Trakt auth only (no email/password)
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.loginReady) {
        if (uiState.loginReady) {
            onLoginSuccess()
            viewModel.onLoginNavigationHandled()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Streame TV",
                style = StreameTypography.heroTitle.copy(
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Trakt authentication is required",
                style = StreameTypography.bodyLarge.copy(
                    fontSize = 18.sp
                ),
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            when (uiState.authState) {
                is Loading -> {
                    Text(
                        text = "Checking authentication...",
                        style = StreameTypography.bodyLarge,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
                is Authenticated -> {
                    Text(
                        text = "Authenticated",
                        style = StreameTypography.bodyLarge,
                        color = Color.Green
                    )
                }
                is NotAuthenticated -> {
                    Text(
                        text = "Not authenticated with Trakt",
                        style = StreameTypography.bodyLarge,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
                is Error -> {
                    Text(
                        text = "Authentication error",
                        style = StreameTypography.bodyLarge,
                        color = Color.Red
                    )
                }
            }
        }
    }
}

