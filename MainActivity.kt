package com.sawtiyat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.sawtiyat.app.audio.SpeechService
import com.sawtiyat.app.data.AppDatabase
import com.sawtiyat.app.data.Book
import com.sawtiyat.app.importer.DocumentImporter
import kotlinx.coroutines.launch

private val Ink = Color(0xFF171020)
private val Panel = Color(0xFF241A30)
private val Gold = Color(0xFFD6B36A)
private val Cream = Color(0xFFF4EBDD)

class MainActivity : ComponentActivity() {
    private val db by lazy { AppDatabase.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DocumentImporter.initPdfBox(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Gold, secondary = Gold, background = Ink,
                    surface = Panel, onPrimary = Ink, onBackground = Cream, onSurface = Cream
                )
            ) {
                SawtiyatScreen(
                    dao = db.bookDao(),
                    onPlay = { book ->
                        val intent = Intent(this, SpeechService::class.java).apply {
                            action = SpeechService.ACTION_PLAY
                            putExtra(SpeechService.EXTRA_BOOK_ID, book.id)
                            putExtra(SpeechService.EXTRA_TEXT, book.content)
                            putExtra(SpeechService.EXTRA_START, book.progressChars)
                        }
                        androidx.core.content.ContextCompat.startForegroundService(this, intent)
                    }
                )
            }
        }
    }
}

@Composable
private fun SawtiyatScreen(
    dao: com.sawtiyat.app.data.BookDao,
    onPlay: (Book) -> Unit
) {
    val context = LocalContext.current
    val books by dao.observeBooks().collectAsStateWithLifecycle(initialValue = emptyList())
    var showTextDialog by remember { mutableStateOf(false) }
    var textTitle by remember { mutableStateOf("") }
    var textBody by remember { mutableStateOf("") }
    var selectedBook by remember { mutableStateOf<Book?>(null) }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val name = DocumentImporter.displayName(context.contentResolver, uri)
                    val text = DocumentImporter.read(context.contentResolver, uri, name)
                    val title = name.substringBeforeLast('.', name).ifBlank { "كتاب جديد" }
                    dao.insert(Book(title = title, content = text))
                }.onFailure {
                    android.widget.Toast.makeText(context, "تعذر استيراد الملف: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        containerColor = Ink,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showTextDialog = true },
                containerColor = Gold, contentColor = Ink
            ) { Icon(Icons.Default.Add, contentDescription = "إضافة نص") }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Headphones, null, tint = Gold, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("صوتيات", color = Gold, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("مكتبتك التي تقرأ لك", color = Cream.copy(alpha = .72f), fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { importLauncher.launch(arrayOf("text/plain", "application/pdf", "application/epub+zip", "*/*")) }) {
                    Text("استيراد ملف", color = Gold)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("مكتبتي", color = Cream, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${books.size} كتاب", color = Gold, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))

            if (books.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(bottom = 70.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MenuBook, null, tint = Gold, modifier = Modifier.size(76.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("ابدأ مكتبتك الصوتية", color = Cream, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("استورد كتاباً أو أضف نصاً لتبدأ الاستماع", color = Cream.copy(alpha = .65f))
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { importLauncher.launch(arrayOf("text/plain", "application/pdf", "application/epub+zip", "*/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                        ) { Text("إضافة أول كتاب") }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                    items(books, key = { it.id }) { book ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(54.dp).background(Color(0xFF49334E), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Default.MenuBook, null, tint = Gold, modifier = Modifier.size(30.dp)) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(book.title, color = Cream, fontWeight = FontWeight.Bold, maxLines = 2)
                                    Text("${book.content.length} حرف · ${book.author.ifBlank { "مؤلف غير محدد" }}", color = Cream.copy(alpha = .6f), fontSize = 12.sp)
                                    val progress = if (book.content.isNotEmpty()) book.progressChars.toFloat() / book.content.length else 0f
                                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = Gold, trackColor = Ink, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                }
                                IconButton(onClick = { onPlay(book) }) {
                                    Icon(Icons.Default.PlayArrow, "استماع", tint = Gold, modifier = Modifier.size(30.dp))
                                }
                                IconButton(onClick = { selectedBook = book }) {
                                    Icon(Icons.Default.Delete, "حذف", tint = Cream.copy(alpha = .55f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("إضافة نص") },
            text = {
                Column {
                    OutlinedTextField(value = textTitle, onValueChange = { textTitle = it }, label = { Text("عنوان الكتاب") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = textBody, onValueChange = { textBody = it }, label = { Text("النص") }, minLines = 5)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (textBody.isNotBlank()) {
                        scope.launch { dao.insert(Book(title = textTitle.ifBlank { "نص جديد" }, content = textBody)) }
                        textTitle = ""; textBody = ""; showTextDialog = false
                    }
                }) { Text("حفظ", color = Gold) }
            },
            dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("إلغاء") } },
            containerColor = Panel, titleContentColor = Cream, textContentColor = Cream
        )
    }

    selectedBook?.let { book ->
        AlertDialog(
            onDismissRequest = { selectedBook = null },
            title = { Text("حذف الكتاب؟") },
            text = { Text("سيتم حذف «${book.title}» من مكتبتك المحلية.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.delete(book.id) }
                    selectedBook = null
                }) { Text("حذف", color = Gold) }
            },
            dismissButton = { TextButton(onClick = { selectedBook = null }) { Text("إلغاء") } },
            containerColor = Panel, titleContentColor = Cream, textContentColor = Cream
        )
    }
}
