package com.sole.ai

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.*
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.InputStream
import java.util.Properties
import java.util.UUID

val PureBlack = Color(0xFF000000)
val PureWhite = Color(0xFFFFFFFF)
val SurfaceGray = Color(0xFF121212)
val BorderGray = Color(0xFF222222)
val TextSecondary = Color(0xFF8E8E93)
val AccentCyan = Color(0xFF00E5FF)
val AccentPurple = Color(0xFF7000FF)

val PremiumTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
)

@Composable
fun SoleAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PureWhite,
            secondary = AccentCyan,
            background = PureBlack,
            surface = SurfaceGray,
            onPrimary = PureBlack,
            onBackground = PureWhite,
            onSurface = PureWhite
        ),
        typography = PremiumTypography,
        content = content
    )
}

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val title: String,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val sender: String,
    val content: String,
    val timestamp: Long
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats WHERE isArchived = 0 ORDER BY isPinned DESC, timestamp DESC")
    fun getAllActiveChats(): Flow<List<ChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesByChat(chatId: String)
}

@Database(entities = [ChatEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class SoleDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: SoleDatabase? = null
        fun getDatabase(context: Context): SoleDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SoleDatabase::class.java,
                    "sole_ai_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class GeminiService(private val apiKey: String) {
    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.7f
                topK = 40
                topP = 0.95f
            }
        )
    }

    fun streamChatResponse(history: List<MessageEntity>, prompt: String): Flow<String> = flow {
        try {
            val chatSession = generativeModel.startChat(
                history = history.map { msg ->
                    content(role = if (msg.sender == "USER") "user" else "model") {
                        text(msg.content)
                    }
                }
            )
            chatSession.sendMessageStream(prompt).collect { chunk ->
                emit(chunk.text ?: "")
            }
        } catch (e: Exception) {
            emit("Error: Configuration error or network timeout.")
        }
    }
}

class ChatRepository(private val chatDao: ChatDao, private val geminiService: GeminiService) {
    val allActiveChats: Flow<List<ChatEntity>> = chatDao.getAllActiveChats()
    fun getMessages(chatId: String): Flow<List<MessageEntity>> = chatDao.getMessagesForChat(chatId)

    suspend fun createNewChat(title: String): String {
        val chatId = UUID.randomUUID().toString()
        val newChat = ChatEntity(chatId = chatId, title = title, timestamp = System.currentTimeMillis())
        chatDao.insertChat(newChat)
        return chatId
    }

    suspend fun saveMessage(chatId: String, sender: String, content: String) {
        val message = MessageEntity(chatId = chatId, sender = sender, content = content, timestamp = System.currentTimeMillis())
        chatDao.insertMessage(message)
    }

    fun streamAIResponse(chatId: String, history: List<MessageEntity>, prompt: String): Flow<String> {
        return geminiService.streamChatResponse(history, prompt)
    }

    suspend fun deleteChat(chatId: String) {
        chatDao.deleteMessagesByChat(chatId)
        chatDao.deleteChat(chatId)
    }
}

class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    val activeChats: StateFlow<List<ChatEntity>> = repository.allActiveChats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentChatId = MutableStateFlow<String?>(null)
    val currentChatId: StateFlow<String?> = _currentChatId.asStateFlow()

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    fun selectChat(chatId: String) {
        _currentChatId.value = chatId
        viewModelScope.launch {
            repository.getMessages(chatId).collect { _messages.value = it }
        }
    }

    fun startNewChat(initialPrompt: String) {
        viewModelScope.launch {
            val title = if (initialPrompt.length > 20) initialPrompt.take(20) + "..." else initialPrompt
            val newId = repository.createNewChat(title)
            selectChat(newId)
            sendMessage(initialPrompt)
        }
    }

    fun sendMessage(content: String) {
        val chatId = _currentChatId.value ?: return
        if (content.isBlank() || _isGenerating.value) return

        viewModelScope.launch {
            repository.saveMessage(chatId, "USER", content)
            val history = _messages.value
            _isGenerating.value = true
            var streamingText = ""

            repository.streamAIResponse(chatId, history, content)
                .collect { chunk ->
                    streamingText += chunk
                }

            if (streamingText.isNotBlank()) {
                repository.saveMessage(chatId, "AI", streamingText)
            }
            _isGenerating.value = false
        }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            repository.deleteChat(chatId)
            if (_currentChatId.value == chatId) {
                _currentChatId.value = null
                _messages.value = emptyList()
            }
        }
    }
}

@Composable
fun SplashScreen(onAnimationFinished: () -> Unit) {
    val alphaAnim = remember { Animatable(0f) }
    LaunchedEffect(true) {
        alphaAnim.animateTo(1f, animationSpec = tween(1500, easing = LinearEasing))
        delay(1000)
        onAnimationFinished()
    }
    Box(modifier = Modifier.fillMaxSize().background(PureBlack), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOLE AI", fontSize = 42.sp, color = PureWhite, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp, modifier = Modifier.alpha(alphaAnim.value))
            Spacer(modifier = Modifier.height(8.dp))
            Text("Smart. Fast. Reliable.", fontSize = 14.sp, color = TextSecondary, letterSpacing = 2.sp, modifier = Modifier.alpha(alphaAnim.value))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: ChatViewModel, onChatSelected: () -> Unit) {
    val chats by viewModel.activeChats.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("SOLE AI", color = PureWhite, fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = PureBlack)) },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.startNewChat("Greetings, SOLE AI."); onChatSelected() }, containerColor = PureWhite, contentColor = PureBlack, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
            }
        },
        containerColor = PureBlack
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)) {
            Text("Conversations", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            if (chats.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No chats active. Create a new one!", color = BorderGray)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(chats) { chat ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceGray).border(1.dp, BorderGray, RoundedCornerShape(14.dp)).clickable { viewModel.selectChat(chat.chatId); onChatSelected() }.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(chat.title, color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextSecondary, modifier = Modifier.size(20.dp).clickable { viewModel.deleteChat(chat.chatId) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    var textInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(PureBlack)) {
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)) {
            items(messages) { message ->
                val isUser = message.sender == "USER"
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isUser) 16.dp else 2.dp, bottomEnd = if (isUser) 2.dp else 16.dp)).background(if (isUser) SurfaceGray else PureBlack).border(1.dp, if (isUser) BorderGray else AccentPurple, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isUser) 16.dp else 2.dp, bottomEnd = if (isUser) 2.dp else 16.dp)).padding(14.dp)) {
                        Text(message.content, color = PureWhite, fontSize = 15.sp)
                    }
                }
            }
            if (isGenerating) {
                item { Text("Thinking...", color = AccentCyan, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp)) }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(16.dp).background(SurfaceGray, RoundedCornerShape(24.dp)).border(1.dp, BorderGray, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = textInput, onValueChange = { textInput = it }, textStyle = TextStyle(color = PureWhite, fontSize = 16.sp), cursorBrush = SolidColor(AccentCyan), modifier = Modifier.weight(1f), decorationBox = { inner ->
                if (textInput.isEmpty()) Text("Type a message...", color = TextSecondary, fontSize = 16.sp)
                inner()
            })
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { if (textInput.isNotBlank()) { viewModel.sendMessage(textInput); textInput = "" } }, enabled = textInput.isNotBlank() && !isGenerating) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = if (textInput.isNotBlank()) AccentCyan else TextSecondary)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apiKey = getApiKeyFromProperties()
        val database = SoleDatabase.getDatabase(applicationContext)
        val geminiService = GeminiService(apiKey)
        val repository = ChatRepository(database.chatDao(), geminiService)

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(repository) as T
        }

        setContent {
            SoleAITheme {
                val navController = rememberNavController()
                val chatViewModel: ChatViewModel = viewModel(factory = factory)

                NavHost(navController = navController, startDestination = "splash") {
                    composable("splash") { SplashScreen { navController.navigate("home") { popUpTo("splash") { inclusive = true } } } }
                    composable("home") { HomeScreen(chatViewModel) { navController.navigate("chat") } }
                    composable("chat") { ChatScreen(chatViewModel) }
                }
            }
        }
    }

    private fun getApiKeyFromProperties(): String {
        return try {
            val properties = Properties()
            val inputStream: InputStream = assets.open("local.properties")
            properties.load(inputStream)
            properties.getProperty("gemini.api.key") ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
