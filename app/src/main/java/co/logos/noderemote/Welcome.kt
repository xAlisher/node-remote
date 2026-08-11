package co.logos.noderemote

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val REPO_URL = "https://github.com/xAlisher/node-remote-android"
const val X_URL = "https://x.com/alisher"

/**
 * Welcome / pairing screen.
 *
 * The privacy copy here is deliberately narrow. An onion service hides your node's
 * ADDRESS and stops anyone without the paired key connecting — it does NOT hide from your
 * internet provider that you use Tor, on either end. Earlier drafts said "no third party
 * can see this link exists", which overpromises: per the Tor spec, restricted discovery
 * protects descriptor lookup and decryption, not the fact that Tor is in use.
 */
@Composable
fun WelcomeScreen(
    onScan: () -> Unit,
    onEnterUri: () -> Unit,
    note: String,
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Node Remote", fontWeight = FontWeight.Bold, fontSize = 30.sp,
                 color = LogosColors.white)

            Text("Watch and control your Logos blockchain node from your phone.",
                 style = MaterialTheme.typography.bodyLarge,
                 color = LogosColors.white)

            Text(
                "Your node publishes a Tor onion service and this phone is the only device " +
                "allowed to reach it. Your node's address stays private, and anyone who " +
                "learns it still can't connect without the key you scan below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "It doesn't hide from your internet provider that you use Tor — on either " +
                "end. If that matters to you, that's the limit to know about.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LogosColors.orange300,
                    contentColor = LogosColors.gray900),
            ) {
                QrGlyph(LogosColors.gray900, 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Scan QR", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            OutlinedButton(
                onClick = onEnterUri,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LogosColors.white),
            ) { Text("Enter URI", fontSize = 15.sp) }

            if (note.isNotEmpty()) {
                Text(note, style = MaterialTheme.typography.bodySmall,
                     color = LogosColors.red500)
            }

            Text("Get the pairing code from the Node Remote panel in Logos Basecamp.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Pinned footer.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Not an official Logos app",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                }) { GithubGlyph(LogosColors.gray400) }
                IconButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(X_URL)))
                }) { XGlyph(LogosColors.gray400) }
            }
        }
    }
}

/** Paste-the-URI screen: focused field, paste button inside it. */
@Composable
fun EnterUriScreen(
    initial: String,
    onConnect: (String, String) -> Unit,
    note: String,
) {
    var uri by remember { mutableStateOf(initial) }
    var token by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().padding(24.dp),
           verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Text("Paste the pairing code shown in Basecamp.",
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = uri,
            onValueChange = { uri = it },
            label = { Text("lgnode:// pairing URI") },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            maxLines = 4,
            trailingIcon = {
                IconButton(onClick = {
                    clip.getText()?.text?.let { uri = it.trim() }
                }) { PasteGlyph(LogosColors.orange300) }
            },
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("device token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = {
                    clip.getText()?.text?.let { token = it.trim() }
                }) { PasteGlyph(LogosColors.orange300) }
            },
        )

        Button(
            onClick = { onConnect(uri.trim(), token.trim()) },
            enabled = uri.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = LogosColors.orange300,
                contentColor = LogosColors.gray900),
        ) { Text("Connect", fontWeight = FontWeight.Bold) }

        if (note.isNotEmpty()) {
            SelectionContainer {
                Text(note, style = MaterialTheme.typography.bodySmall,
                     color = LogosColors.red500)
            }
        }
    }
}

// ── Lucide glyphs (24x24, stroke 2) ───────────────────────────────────────────────────
private object Ic {
    const val QR = "M3 3h6v6H3zM15 3h6v6h-6zM3 15h6v6H3zM15 15h2v2h-2zM19 15h2v2h-2z" +
                   "M15 19h2v2h-2zM19 19h2v2h-2z"
    const val PASTE = "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" +
                      "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z"
    // simple-icons github
    const val GITHUB = "M12 .3a12 12 0 0 0-3.8 23.4c.6.1.8-.3.8-.6v-2c-3.3.7-4-1.6-4-1.6-.6-1.4-1.4-1.8-1.4-1.8" +
                       "-1-.7.1-.7.1-.7 1.2 0 1.8 1.2 1.8 1.2 1 1.8 2.8 1.3 3.5 1 0-.8.4-1.3.7-1.6" +
                       "-2.7-.3-5.5-1.3-5.5-6 0-1.2.5-2.3 1.3-3.1-.2-.4-.6-1.6.1-3.2 0 0 1-.3 3.3 1.2a11.5 11.5 0 0 1 6 0" +
                       "c2.3-1.5 3.3-1.2 3.3-1.2.7 1.6.2 2.8.1 3.2.8.8 1.3 1.9 1.3 3.1 0 4.7-2.8 5.7-5.5 6" +
                       ".4.4.8 1.1.8 2.2v3.3c0 .3.2.7.8.6A12 12 0 0 0 12 .3z"
    // simple-icons x
    const val X = "M18.9 1.2h3.7l-8.1 9.2 9.5 12.6h-7.4l-5.9-7.6-6.7 7.6H.3l8.6-9.9L0 1.2h7.6l5.2 7z"
}

@Composable
private fun LucideGlyph(path: String, tint: Color, size: Dp, filled: Boolean = false) {
    val p = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, pivot = Offset.Zero) {
            if (filled) drawPath(p, tint)
            else drawPath(p, tint, style = Stroke(width = 2f, cap = StrokeCap.Round,
                                                  join = StrokeJoin.Round))
        }
    }
}

@Composable fun QrGlyph(tint: Color, size: Dp = 22.dp) = LucideGlyph(Ic.QR, tint, size)
@Composable fun PasteGlyph(tint: Color, size: Dp = 20.dp) = LucideGlyph(Ic.PASTE, tint, size)
@Composable fun GithubGlyph(tint: Color, size: Dp = 22.dp) = LucideGlyph(Ic.GITHUB, tint, size, filled = true)
@Composable fun XGlyph(tint: Color, size: Dp = 20.dp) = LucideGlyph(Ic.X, tint, size, filled = true)
