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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The monorepo — the Android app and the Basecamp modules live together.
// This pointed at xAlisher/node-remote-android, which was never created: the
// GitHub icon in the welcome screen opened a 404.
const val REPO_URL = "https://github.com/xAlisher/node-remote"
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
/**
 * Confirm the pairing before it is used.
 *
 * Its own screen, not a line in a header: the SAS is the ONLY defence against a QR someone
 * photographed off your desktop, and it only works if the user actually compares the digits.
 * Tucked into a status line it is scenery — a decision needs a stop and two buttons.
 *
 * Shown after a scan (or a pasted URI) and BEFORE connecting, because confirming after the
 * link is live confirms nothing.
 */
@Composable
fun ConfirmPairingScreen(
    code: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LucideGlyph(Ic.BLOCKS, LogosColors.white, 44.dp)

            Text("Confirm pairing", fontWeight = FontWeight.Bold, fontSize = 30.sp,
                 color = LogosColors.white)

            Text("Check this code matches the one shown in Basecamp.",
                 style = MaterialTheme.typography.bodyLarge,
                 color = LogosColors.white)

            // The digits, given the room they deserve — this is the thing being compared.
            Surface(color = LogosColors.gray875,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()) {
                Text(code,
                     modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                     textAlign = TextAlign.Center,
                     fontFamily = FontFamily.Monospace,
                     fontWeight = FontWeight.Bold,
                     fontSize = 40.sp,
                     letterSpacing = 6.sp,
                     color = LogosColors.orange300)
            }

            Text("If the digits are different, someone else's code reached this phone — " +
                 "dismiss and start again on the desktop.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(6.dp))

            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LogosColors.orange500,
                    contentColor = LogosColors.white),
            ) { Text("Confirm", fontWeight = FontWeight.Bold, fontSize = 16.sp) }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LogosColors.white),
            ) { Text("Dismiss", fontSize = 15.sp) }
        }
    }
}

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
            // The product mark, above the name — same glyph as the launcher and the
            // Basecamp pane, so the three read as one thing.
            LucideGlyph(Ic.BLOCKS, LogosColors.white, 44.dp)

            Text("Node Remote", fontWeight = FontWeight.Bold, fontSize = 30.sp,
                 color = LogosColors.white)

            Text("Watch and control your Logos blockchain node from your phone.",
                 style = MaterialTheme.typography.bodyLarge,
                 color = LogosColors.white)

            Text(
                // NOT "your node publishes" — the blockchain node knows nothing about Tor
                // and publishes nothing. The Node Remote app in Basecamp runs the onion
                // service and reads the node over loopback. Naming the wrong component
                // sends anyone debugging this to the wrong logs.
                "The Node Remote app in Basecamp publishes a Tor onion service that only " +
                "paired devices can reach. Anyone who learns the address can tell the " +
                "service exists, but still can't connect without the key you scan below.",
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
                    containerColor = LogosColors.orange500,
                    contentColor = LogosColors.white),
            ) {
                QrGlyph(LogosColors.white, 20.dp)
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

            Text("Get the pairing code from the Node Remote app in Logos Basecamp.",
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

        Text("Paste the pairing code from the Node Remote app in Basecamp.",
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
                containerColor = LogosColors.orange500,
                contentColor = LogosColors.white),
        ) { Text("Connect", fontWeight = FontWeight.Bold) }

        if (note.isNotEmpty()) {
            SelectionContainer {
                Text(note, style = MaterialTheme.typography.bodySmall,
                     color = LogosColors.red500)
            }
        }
    }
}

// ── Lucide glyphs (24x24, stroke 2), verbatim from lucide.dev ────────────────────────
//
// NOTE: Lucide ships NO brand icons — github/twitter/facebook/linkedin all 404 in the
// repo; they were dropped in favour of simple-icons. So the footer uses Lucide's generic
// equivalents rather than smuggling in a second icon set: code-xml for the repository and
// at-sign for the handle. If recognisable brand marks are wanted, that is a deliberate
// decision to add simple-icons, not something Lucide can supply.
private object Ic {
    // lucide `scan-qr-code` — the SCAN action, not a static qr-code glyph. Subpaths
    // concatenated; the rect is drawn separately (PathParser has no <rect>).
    const val SCAN_QR = "M17 12v4a1 1 0 0 1-1 1h-4 M17 3h2a2 2 0 0 1 2 2v2 M17 8V7 " +
                        "M21 17v2a2 2 0 0 1-2 2h-2 M3 7V5a2 2 0 0 1 2-2h2 M7 17h.01 " +
                        "M7 21H5a2 2 0 0 1-2-2v-2"
    const val PASTE = "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" +
                      "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z"
    // lucide `x`
    const val CLOSE = "M18 6 6 18M6 6l12 12"

    // lucide `blocks` — the product mark, the same glyph as the launcher icon and the
    // Basecamp module icon. Rendered from the path rather than the mipmap so it scales
    // cleanly and is tintable; the <rect x=14 y=2 w=8 h=8 rx=1> is written out longhand
    // because PathParser has no <rect>.
    const val BLOCKS = "M10 22V7a1 1 0 0 0-1-1H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5a1 1 0 0 0-1-1H2 " +
                       "M15 2h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z"
}

/**
 * The two brand marks. Lucide deliberately ships no brand icons (github/twitter/etc are
 * 404 in its repo), so these are the vendors' OWN artwork as published by simple-icons —
 * 24x24, single filled path, tinted to sit with the rest of the UI instead of appearing
 * in brand colours.
 */
private object Brand {
    const val GITHUB = "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
    const val X = "M14.234 10.162 22.977 0h-2.072l-7.591 8.824L7.251 0H.258l9.168 13.343L.258 24H2.33l8.016-9.318L16.749 24h6.993zm-2.837 3.299-.929-1.329L3.076 1.56h3.182l5.965 8.532.929 1.329 7.754 11.09h-3.182z"
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

@Composable
fun QrGlyph(tint: Color, size: Dp = 22.dp) {
    val p = remember { PathParser().parsePathString(Ic.SCAN_QR).toPath() }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, pivot = Offset.Zero) {
            val st = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(p, tint, style = st)
            // <rect x=7 y=7 w=5 h=5 rx=1>
            drawRoundRect(tint, topLeft = Offset(7f, 7f),
                          size = androidx.compose.ui.geometry.Size(5f, 5f),
                          cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f, 1f),
                          style = st)
        }
    }
}
@Composable fun PasteGlyph(tint: Color, size: Dp = 20.dp) = LucideGlyph(Ic.PASTE, tint, size)
@Composable fun CloseGlyph(tint: Color, size: Dp = 22.dp) = LucideGlyph(Ic.CLOSE, tint, size)
// Brand marks are FILLED, not stroked — that is how the official artwork is drawn.
@Composable fun GithubGlyph(tint: Color, size: Dp = 21.dp) = LucideGlyph(Brand.GITHUB, tint, size, filled = true)
@Composable fun XGlyph(tint: Color, size: Dp = 19.dp) = LucideGlyph(Brand.X, tint, size, filled = true)
