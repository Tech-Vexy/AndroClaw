package ai.androclaw.feature.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.androclaw.feature.auth.SignInState

/**
 * Google Sign-In button for the Onboarding Google step.
 * Shows current sign-in state and provides sign-in / sign-out actions.
 */
@Composable
fun GoogleSignInButton(
    state: SignInState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is SignInState.SignedIn -> {
            // Signed-in state — show email and disconnect option
            Surface(
                color  = Color(0xFF238636).copy(alpha = 0.15f),
                shape  = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF238636).copy(alpha = 0.4f)),
                modifier = modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Check, null,
                            tint     = Color(0xFF3FB950),
                            modifier = Modifier.size(16.dp))
                        Column {
                            Text("Connected", color = Color(0xFF3FB950),
                                fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(state.email, color = Color(0xFF8B949E), fontSize = 11.sp)
                        }
                    }
                    TextButton(onClick = onSignOut) {
                        Text("Disconnect", color = Color(0xFF8B949E), fontSize = 12.sp)
                    }
                }
            }
        }

        is SignInState.TokenExpired -> {
            Column(
                modifier            = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color  = Color(0xFFD29922).copy(alpha = 0.12f),
                    shape  = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFD29922).copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Google token expired — please sign in again.",
                        color    = Color(0xFFD29922),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                GoogleSignInSurface(onClick = onSignIn, modifier = Modifier.fillMaxWidth())
            }
        }

        is SignInState.Error -> {
            Column(
                modifier            = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(state.message, color = Color(0xFFFF5555), fontSize = 12.sp)
                GoogleSignInSurface(onClick = onSignIn, modifier = Modifier.fillMaxWidth())
            }
        }

        else -> GoogleSignInSurface(onClick = onSignIn, modifier = modifier.fillMaxWidth())
    }
}

@Composable
private fun GoogleSignInSurface(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick   = onClick,
        color     = Color(0xFF1A1F2E),
        shape     = RoundedCornerShape(10.dp),
        border    = BorderStroke(1.dp, Color(0xFF8B949E).copy(alpha = 0.25f)),
        modifier  = modifier,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.AccountCircle, null,
                tint     = Color(0xFF6C9EFF),
                modifier = Modifier.size(20.dp))
            Text(
                "Sign in with Google",
                color      = Color(0xFFE6EDF3),
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

