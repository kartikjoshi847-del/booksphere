package com.example.booksphere

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.abs

data class Book(
    val title: String,
    val author: String,
    val thumbnail: String,
    val rating: Double,
    val description: String,
    val workKey: String
)

data class GoogleBookReview(
    val source: String,
    val description: String
)

enum class Screen {
    HOME, SEARCH, LIKED, COMMENTS, PROFILE
}

class MainActivity : ComponentActivity() {

    private val client = OkHttpClient()
    private val db = FirebaseFirestore.getInstance()

    private fun purple() = androidx.compose.ui.graphics.Color(0xFF6C4AB6)

    private fun bookKey(title: String, author: String): String {
        return "$title|$author"
            .replace("/", "_")
            .replace("\\", "_")
            .replace("#", "_")
            .replace("?", "_")
    }

    private fun prefs() =
        getSharedPreferences("BookSpherePrefs", Context.MODE_PRIVATE)

    private fun saveStringSet(key: String, value: Set<String>) {
        prefs().edit().putStringSet(key, value).apply()
    }

    private fun getStringSet(key: String): MutableSet<String> {
        return prefs().getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveUserRating(key: String, rating: Int) {
        prefs().edit().putInt("rating_$key", rating).apply()
    }

    private fun getUserRating(key: String): Int {
        return prefs().getInt("rating_$key", 0)
    }

    fun openBuyLink(site: String, title: String, author: String) {
        val query = URLEncoder.encode("$title $author", "UTF-8")

        val url = when (site) {
            "amazon" -> "https://www.amazon.in/s?k=$query"
            "flipkart" -> "https://www.flipkart.com/search?q=$query"
            "bookswagon" -> "https://www.bookswagon.com/search-books/$query"
            "sapna" -> "https://www.sapnaonline.com/search?keyword=$query"
            "crossword" -> "https://www.crossword.in/search?q=$query"
            "googlebooks" -> "https://books.google.com/books?q=$query"
            else -> "https://www.google.com/search?q=$query"
        }

        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun addComment(bookKey: String, comment: String) {
        val data = hashMapOf(
            "text" to comment,
            "timestamp" to System.currentTimeMillis()
        )

        db.collection("comments")
            .document(bookKey)
            .collection("messages")
            .add(data)
    }

    fun loadComments(bookKey: String, onLoaded: (MutableList<String>) -> Unit) {
        db.collection("comments")
            .document(bookKey)
            .collection("messages")
            .orderBy("timestamp")
            .get()
            .addOnSuccessListener { result ->
                val list = mutableListOf<String>()
                for (doc in result) {
                    val text = doc.getString("text")
                    if (!text.isNullOrBlank()) list.add(text)
                }
                onLoaded(list)
            }
            .addOnFailureListener {
                onLoaded(mutableListOf())
            }
    }

    fun fetchGoogleBookReview(
        title: String,
        author: String,
        onResult: (GoogleBookReview?) -> Unit
    ) {
        val query = URLEncoder.encode("intitle:$title+inauthor:$author", "UTF-8")
        val url =
            "https://www.googleapis.com/books/v1/volumes?q=$query&langRestrict=en&printType=books&maxResults=10&key=AIzaSyDFJri3ng-uiAY4bJQ_14gj1YeSXH8RYyo"

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { onResult(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string()
                    Log.d("GOOGLE_BOOKS", body ?: "EMPTY")

                    val json = JSONObject(body ?: "{}")

                    if (!json.has("items") || json.getJSONArray("items").length() == 0) {
                        runOnUiThread { onResult(null) }
                        return
                    }

                    val volumeInfo = json
                        .getJSONArray("items")
                        .getJSONObject(0)
                        .getJSONObject("volumeInfo")

                    val desc =
                        if (volumeInfo.has("description")) {
                            volumeInfo.optString("description")
                                .replace(Regex("[^\\x00-\\x7F]"), "")
                        } else {
                            "English description unavailable."
                        }

                    runOnUiThread {
                        onResult(
                            GoogleBookReview(
                                source = "Google Books",
                                description = desc
                            )
                        )
                    }

                } catch (e: Exception) {
                    runOnUiThread { onResult(null) }
                }
            }
        })
    }

    fun fetchBooks(query: String, onResult: (List<Book>, String) -> Unit) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://openlibrary.org/search.json?q=$encodedQuery&limit=20"

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    onResult(emptyList(), "Internet failed: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val books = mutableListOf<Book>()

                try {
                    val body = response.body?.string()
                    val json = JSONObject(body ?: "{}")

                    if (!response.isSuccessful) {
                        runOnUiThread {
                            onResult(emptyList(), "API Error: ${response.code}")
                        }
                        return
                    }

                    val docs = json.getJSONArray("docs")

                    for (i in 0 until docs.length()) {
                        val item = docs.getJSONObject(i)

                        val title = item.optString("title", "No Title")

                        val author =
                            if (item.has("author_name"))
                                item.getJSONArray("author_name").getString(0)
                            else
                                "Unknown"

                        val coverId = item.optInt("cover_i", 0)

                        val thumbnail =
                            if (coverId != 0)
                                "https://covers.openlibrary.org/b/id/$coverId-L.jpg"
                            else
                                ""

                        val year = item.optString("first_publish_year", "Unknown")
                        val editionCount = item.optString("edition_count", "Unknown")
                        val workKey = item.optString("key", "")

                        val subjects =
                            if (item.has("subject")) {
                                val arr = item.getJSONArray("subject")
                                buildString {
                                    for (j in 0 until minOf(8, arr.length())) {
                                        append(arr.getString(j))
                                        if (j != minOf(8, arr.length()) - 1) append(", ")
                                    }
                                }
                            } else {
                                "No category available"
                            }

                        val description =
                            """
                            Title: $title
                            
                            Author: $author
                            
                            First Published: $year
                            
                            Editions Available: $editionCount
                            
                            Genres / Subjects: $subjects
                            
                            Brief Description:
                            This book is listed from Open Library. Open the book to load more details.
                            """.trimIndent()

                        val generatedRating =
                            (abs((title + author).hashCode() % 5) + 1).toDouble()

                        books.add(
                            Book(
                                title = title,
                                author = author,
                                thumbnail = thumbnail,
                                rating = generatedRating,
                                description = description,
                                workKey = workKey
                            )
                        )
                    }

                    books.sortByDescending { it.rating }

                    runOnUiThread {
                        if (books.isEmpty()) {
                            onResult(emptyList(), "No books found. Try another search.")
                        } else {
                            onResult(books, "")
                        }
                    }

                } catch (e: Exception) {
                    runOnUiThread {
                        onResult(emptyList(), "Parsing error: ${e.message}")
                    }
                }
            }
        })
    }

    fun fetchLongDescription(book: Book, onResult: (String) -> Unit) {
        if (book.workKey.isBlank()) {
            onResult(book.description)
            return
        }

        val url = "https://openlibrary.org${book.workKey}.json"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { onResult(book.description) }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string()
                    val json = JSONObject(body ?: "{}")

                    val realDescription =
                        if (json.has("description")) {
                            val desc = json.get("description")
                            if (desc is JSONObject) desc.optString("value", "")
                            else desc.toString()
                        } else {
                            ""
                        }

                    val subjects =
                        if (json.has("subjects")) {
                            val arr = json.getJSONArray("subjects")
                            buildString {
                                for (i in 0 until minOf(12, arr.length())) {
                                    append(arr.getString(i))
                                    if (i != minOf(12, arr.length()) - 1) append(", ")
                                }
                            }
                        } else {
                            "No extra subjects available"
                        }

                    val finalText =
                        if (realDescription.isNotBlank()) {
                            """
                            Title: ${book.title}
                            
                            Author: ${book.author}
                            
                            Book Rating: ${book.rating.toInt()} / 5
                            
                            Subjects: $subjects
                            
                            Main Description:
                            $realDescription
                            """.trimIndent()
                        } else {
                            """
                            ${book.description}
                            
                            Main Description:
                            A detailed description is not available for this book in Open Library.
                            """.trimIndent()
                        }

                    runOnUiThread { onResult(finalText) }

                } catch (e: Exception) {
                    runOnUiThread { onResult(book.description) }
                }
            }
        })
    }

    @Composable
    fun AppButton(text: String, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = purple())
        ) {
            Text(text)
        }
    }

    @Composable
    fun SectionTitle(text: String) {
        Text(text, fontSize = 24.sp)
        Spacer(modifier = Modifier.height(10.dp))
    }

    @Composable
    fun BookImage(thumbnail: String, big: Boolean) {
        if (thumbnail.isNotBlank()) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                modifier = if (big)
                    Modifier.fillMaxWidth().height(300.dp)
                else
                    Modifier.size(110.dp)
            )
        } else {
            Card(
                modifier = if (big)
                    Modifier.fillMaxWidth().height(300.dp)
                else
                    Modifier.size(110.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Box(Modifier.fillMaxSize().padding(8.dp)) {
                    Text("No Cover", fontSize = if (big) 24.sp else 14.sp)
                }
            }
        }
    }

    @Composable
    fun DisplayRating(rating: Double) {
        Row {
            val stars = rating.toInt().coerceIn(1, 5)
            for (i in 1..5) {
                Text(if (i <= stars) "⭐" else "☆", fontSize = 16.sp)
            }
        }
    }

    @Composable
    fun RatingBar(current: Int, onRate: (Int) -> Unit) {
        Row {
            for (i in 1..5) {
                Text(
                    text = if (i <= current) "⭐" else "☆",
                    fontSize = 22.sp,
                    modifier = Modifier.clickable {
                        if (current == i) {
                            onRate(0)
                        } else {
                            onRate(i)
                        }
                    }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var loggedIn by remember { mutableStateOf(false) }
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            if (!loggedIn) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("BookSphere", fontSize = 36.sp)
                    Text("Login to continue")

                    Spacer(Modifier.height(20.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = {
                            if (email.isNotBlank() && password.isNotBlank()) {
                                loggedIn = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = purple())
                    ) {
                        Text("Login")
                    }
                }
            } else {
                // paste your Scaffold app code here
            }


            var currentScreen by remember { mutableStateOf(Screen.HOME) }
            var selectedBook by remember { mutableStateOf<Book?>(null) }

            var searchText by remember { mutableStateOf("") }
            var books by remember { mutableStateOf(listOf<Book>()) }
            var isLoading by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf("") }

            val likedBooks = remember {
                mutableStateListOf<String>().apply {
                    addAll(getStringSet("liked_books"))
                }
            }

            val commentedBooks = remember {
                mutableStateListOf<String>().apply {
                    addAll(getStringSet("commented_books"))
                }
            }

            val userRatings = remember {
                mutableStateMapOf<String, Int>()
            }

            fun searchBooks(query: String) {
                if (query.isBlank()) return

                isLoading = true
                errorMessage = ""

                fetchBooks(query) { result, error ->
                    books = result
                    errorMessage = error
                    isLoading = false
                    currentScreen = Screen.SEARCH
                }
            }

            fun openSavedBook(savedKey: String) {
                val title = savedKey.substringBefore("|")
                searchText = title
                isLoading = true
                errorMessage = ""

                fetchBooks(title) { result, error ->
                    books = result
                    errorMessage = error
                    isLoading = false
                    currentScreen = Screen.SEARCH

                    selectedBook =
                        result.firstOrNull {
                            bookKey(it.title, it.author) == savedKey
                        } ?: result.firstOrNull {
                            it.title.equals(title, ignoreCase = true)
                        }
                }
            }

            BackHandler {
                when {
                    selectedBook != null -> selectedBook = null
                    currentScreen != Screen.HOME -> currentScreen = Screen.HOME
                    else -> finish()
                }
            }

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentScreen == Screen.HOME,
                            onClick = {
                                selectedBook = null
                                currentScreen = Screen.HOME
                            },
                            icon = { Text("🏠") },
                            label = { Text("Home") }
                        )

                        NavigationBarItem(
                            selected = currentScreen == Screen.SEARCH,
                            onClick = {
                                selectedBook = null
                                currentScreen = Screen.SEARCH
                            },
                            icon = { Text("🔍") },
                            label = { Text("Search") }
                        )

                        NavigationBarItem(
                            selected = currentScreen == Screen.LIKED,
                            onClick = {
                                selectedBook = null
                                currentScreen = Screen.LIKED
                            },
                            icon = { Text("❤️") },
                            label = { Text("Liked") }
                        )

                        NavigationBarItem(
                            selected = currentScreen == Screen.COMMENTS,
                            onClick = {
                                selectedBook = null
                                currentScreen = Screen.COMMENTS
                            },
                            icon = { Text("💬") },
                            label = { Text("Comments") }
                        )

                        NavigationBarItem(
                            selected = currentScreen == Screen.PROFILE,
                            onClick = {
                                selectedBook = null
                                currentScreen = Screen.PROFILE
                            },
                            icon = { Text("👤") },
                            label = { Text("Profile") }
                        )
                    }
                }
            ) { padding ->

                if (selectedBook != null) {

                    val book = selectedBook!!
                    val key = bookKey(book.title, book.author)

                    var commentText by remember { mutableStateOf("") }
                    var fullDescription by remember(book.workKey) { mutableStateOf(book.description) }
                    var descriptionLoading by remember(book.workKey) { mutableStateOf(true) }
                    var googleReview by remember { mutableStateOf<GoogleBookReview?>(null) }

                    val comments = remember { mutableStateListOf<String>() }

                    LaunchedEffect(book.workKey) {
                        fetchLongDescription(book) {
                            fullDescription = it
                            descriptionLoading = false
                        }

                        fetchGoogleBookReview(book.title, book.author) {
                            googleReview = it
                        }
                    }

                    LaunchedEffect(key) {
                        loadComments(key) {
                            comments.clear()
                            comments.addAll(it)
                        }
                    }

                    val savedRating = userRatings[key] ?: getUserRating(key)

                    LazyColumn(
                        modifier = Modifier
                            .padding(padding)
                            .padding(16.dp)
                    ) {
                        item {
                            BookImage(book.thumbnail, big = true)

                            Spacer(Modifier.height(12.dp))

                            Text(book.title, fontSize = 26.sp)
                            Text("by ${book.author}")

                            Spacer(Modifier.height(8.dp))

                            Text("Book Rating")
                            DisplayRating(book.rating)

                            Spacer(Modifier.height(10.dp))

                            Text("Your Rating")
                            RatingBar(savedRating) {
                                userRatings[key] = it
                                saveUserRating(key, it)
                            }

                            Spacer(Modifier.height(20.dp))

                            SectionTitle("Description")

                            if (descriptionLoading) {
                                Text("Loading detailed description...")
                            } else {
                                Text(fullDescription, fontSize = 16.sp)
                            }

                            Spacer(Modifier.height(20.dp))

                            SectionTitle("Google Books Description")

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Source: Google Books")
                                    Spacer(Modifier.height(8.dp))
                                    Text(googleReview?.description ?: "No Google Books description found.")
                                }
                            }

                            Spacer(Modifier.height(20.dp))

                            SectionTitle("Compare Prices / Buy Book")

                            AppButton("Buy on Amazon") {
                                openBuyLink("amazon", book.title, book.author)
                            }

                            AppButton("Buy on Flipkart") {
                                openBuyLink("flipkart", book.title, book.author)
                            }

                            AppButton("Buy on Bookswagon") {
                                openBuyLink("bookswagon", book.title, book.author)
                            }

                            AppButton("Buy on SapnaOnline") {
                                openBuyLink("sapna", book.title, book.author)
                            }

                            AppButton("Buy on Crossword") {
                                openBuyLink("crossword", book.title, book.author)
                            }

                            AppButton("View on Google Books") {
                                openBuyLink("googlebooks", book.title, book.author)
                            }

                            Spacer(Modifier.height(20.dp))

                            AppButton(
                                if (likedBooks.contains(key)) "❤️ Liked" else "🤍 Like"
                            ) {
                                if (likedBooks.contains(key)) {
                                    likedBooks.remove(key)
                                } else {
                                    likedBooks.add(key)
                                }

                                saveStringSet("liked_books", likedBooks.toSet())
                            }

                            Spacer(Modifier.height(20.dp))

                            SectionTitle("Community Comments")

                            if (comments.isEmpty()) {
                                Text("No comments yet.")
                            } else {
                                comments.forEach {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "• $it",
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            OutlinedTextField(
                                value = commentText,
                                onValueChange = { commentText = it },
                                label = { Text("Write Comment") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(8.dp))

                            AppButton("Post Comment") {
                                if (commentText.isNotBlank()) {
                                    addComment(key, commentText)
                                    comments.add(commentText)

                                    if (!commentedBooks.contains(key)) {
                                        commentedBooks.add(key)
                                        saveStringSet("commented_books", commentedBooks.toSet())
                                    }

                                    commentText = ""
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            AppButton("Back") {
                                selectedBook = null
                            }

                            Spacer(Modifier.height(80.dp))
                        }
                    }
                } else {

                    when (currentScreen) {

                        Screen.HOME -> {
                            val moods = listOf(
                                "Happy" to "feel good books",
                                "Sad" to "emotional novels",
                                "Motivated" to "self improvement books",
                                "Relaxed" to "calm books",
                                "Romantic" to "romance novels",
                                "Thriller" to "thriller novels",
                                "Fantasy" to "fantasy novels",
                                "Horror" to "horror novels"
                            )

                            LazyColumn(
                                modifier = Modifier
                                    .padding(padding)
                                    .padding(16.dp)
                            ) {
                                item {
                                    Text("BookSphere", fontSize = 34.sp)
                                    Text("Discover. Review. Compare.", fontSize = 16.sp)

                                    Spacer(Modifier.height(16.dp))

                                    OutlinedTextField(
                                        value = searchText,
                                        onValueChange = { searchText = it },
                                        label = { Text("Search books...") },
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    AppButton("Search") {
                                        searchBooks(searchText)
                                    }

                                    Spacer(Modifier.height(20.dp))

                                    SectionTitle("Mood Based Discovery")
                                }

                                items(moods) { mood ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                            .clickable {
                                                searchText = mood.second
                                                searchBooks(mood.second)
                                            },
                                        shape = MaterialTheme.shapes.large
                                    ) {
                                        Row(modifier = Modifier.padding(16.dp)) {
                                            Text("📚 ${mood.first}", fontSize = 20.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Screen.SEARCH -> {
                            LazyColumn(
                                modifier = Modifier
                                    .padding(padding)
                                    .padding(16.dp)
                            ) {
                                item {
                                    SectionTitle("Search Books")

                                    OutlinedTextField(
                                        value = searchText,
                                        onValueChange = { searchText = it },
                                        label = { Text("Search books...") },
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    AppButton("Search") {
                                        searchBooks(searchText)
                                    }

                                    if (isLoading) {
                                        Spacer(Modifier.height(10.dp))
                                        Text("Loading books...")
                                    }

                                    if (errorMessage.isNotEmpty()) {
                                        Spacer(Modifier.height(10.dp))
                                        Text(errorMessage)
                                    }

                                    Spacer(Modifier.height(10.dp))
                                }

                                items(books) { book ->
                                    val key = bookKey(book.title, book.author)
                                    val savedRating = userRatings[key] ?: getUserRating(key)

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        shape = MaterialTheme.shapes.large
                                    ) {
                                        Row(Modifier.padding(12.dp)) {
                                            BookImage(book.thumbnail, big = false)

                                            Spacer(Modifier.width(12.dp))

                                            Column {
                                                Text(book.title, fontSize = 18.sp)
                                                Text("by ${book.author}")

                                                Spacer(Modifier.height(4.dp))

                                                DisplayRating(book.rating)

                                                Spacer(Modifier.height(6.dp))

                                                Text("Your Rating")
                                                RatingBar(savedRating) {
                                                    userRatings[key] = it
                                                    saveUserRating(key, it)
                                                }

                                                Spacer(Modifier.height(8.dp))

                                                Row {
                                                    Button(
                                                        onClick = {
                                                            if (likedBooks.contains(key)) {
                                                                likedBooks.remove(key)
                                                            } else {
                                                                likedBooks.add(key)
                                                            }

                                                            saveStringSet("liked_books", likedBooks.toSet())
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = purple())
                                                    ) {
                                                        Text(
                                                            if (likedBooks.contains(key)) "❤️ Liked"
                                                            else "🤍 Like"
                                                        )
                                                    }

                                                    Spacer(Modifier.width(8.dp))

                                                    Button(
                                                        onClick = { selectedBook = book },
                                                        colors = ButtonDefaults.buttonColors(containerColor = purple())
                                                    ) {
                                                        Text("Open")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                item {
                                    Spacer(Modifier.height(80.dp))
                                }
                            }
                        }

                        Screen.LIKED -> {
                            LazyColumn(
                                modifier = Modifier
                                    .padding(padding)
                                    .padding(16.dp)
                            ) {
                                item {
                                    SectionTitle("Liked Books")
                                }

                                if (likedBooks.isEmpty()) {
                                    item {
                                        Text("No liked books yet.")
                                    }
                                } else {
                                    items(likedBooks) { item ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp)
                                                .clickable {
                                                    openSavedBook(item)
                                                },
                                            shape = MaterialTheme.shapes.large
                                        ) {
                                            Text(
                                                text = "❤️ ${item.replace("|", " by ")}",
                                                modifier = Modifier.padding(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Screen.COMMENTS -> {
                            LazyColumn(
                                modifier = Modifier
                                    .padding(padding)
                                    .padding(16.dp)
                            ) {
                                item {
                                    SectionTitle("Commented Books")
                                }

                                if (commentedBooks.isEmpty()) {
                                    item {
                                        Text("No commented books yet.")
                                    }
                                } else {
                                    items(commentedBooks) { item ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp)
                                                .clickable {
                                                    openSavedBook(item)
                                                },
                                            shape = MaterialTheme.shapes.large
                                        ) {
                                            Text(
                                                text = "💬 ${item.replace("|", " by ")}",
                                                modifier = Modifier.padding(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Screen.PROFILE -> {
                            Column(
                                modifier = Modifier
                                    .padding(padding)
                                    .padding(16.dp)
                            ) {
                                SectionTitle("Profile")

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("Welcome to BookSphere", fontSize = 20.sp)
                                        Spacer(Modifier.height(8.dp))
                                        Text("Liked Books: ${likedBooks.size}")
                                        Text("Commented Books: ${commentedBooks.size}")
                                        Text("App Features: Mood discovery, comments, ratings, review, and buying comparison.")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}