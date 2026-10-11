@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.techwithkaushik.datasetbuilder

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private val brand = Color(0xFF145DDE)
private data class Project(val id: String, val name: String, val description: String, val labels: List<String>, val updated: Long)

@Composable
internal fun DatasetHomeEntry(context: Context, onOpen: (String, String) -> Unit) {
    ProjectHome(context) { project -> onOpen(project.id, project.name) }
}

@Composable
private fun ProjectHome(context: Context, onOpen: (Project) -> Unit) {
    val prefs = remember { context.getSharedPreferences("dataset_projects", Context.MODE_PRIVATE) }
    var projects by remember { mutableStateOf(readProjects(prefs)) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var edit by remember { mutableStateOf<Project?>(null) }
    var create by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Project?>(null) }
    var settings by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    fun persist(list: List<Project>) {
        projects = list
        prefs.edit().putString("items", JSONArray().apply {
            list.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("description", p.description).put("labels", JSONArray(p.labels)).put("updated", p.updated)) }
        }.toString()).apply()
    }
    fun save(name: String, description: String, labels: List<String>, old: Project?) {
        if (name.isBlank() || labels.isEmpty()) return
        val p = Project(old?.id ?: UUID.randomUUID().toString(), name.trim(), description.trim(), labels, System.currentTimeMillis())
        persist(if (old == null) listOf(p) + projects else projects.map { if (it.id == old.id) p else it })
    }
    BackHandler(settings || create || edit != null || delete != null) {
        settings = false; create = false; edit = null; delete = null
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Dataset Builder", fontWeight = FontWeight.Bold); Text("Create, manage and annotate datasets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { Icon(Icons.Default.Hub, null, tint = brand, modifier = Modifier.padding(start = 16.dp).size(26.dp)) },
                actions = { IconButton(onClick = { settings = true; tab = 2 }) { Icon(Icons.Default.Settings, "Settings") }; IconButton(onClick = { settings = true; tab = 2 }) { Icon(Icons.Default.MoreVert, "More") } }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0; settings = false }, icon = { Icon(Icons.Default.FolderOpen, null) }, label = { Text("Datasets") })
                NavigationBarItem(tab == 1, { tab = 1; settings = false }, icon = { Icon(Icons.Default.Insights, null) }, label = { Text("Stats") })
                NavigationBarItem(tab == 2, { tab = 2; settings = true }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        },
        floatingActionButton = { if (tab == 0 && !settings) ExtendedFloatingActionButton(onClick = { create = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Create Dataset") }, containerColor = brand, contentColor = Color.White) }
    ) { pad ->
        when {
            settings || tab == 2 -> SettingsPage(Modifier.padding(pad)) { settings = false; tab = 0 }
            tab == 1 -> Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dataset overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Projects: " + projects.size)
                Text("Annotation classes: " + projects.sumOf { it.labels.size })
            }
            else -> Column(Modifier.fillMaxSize().padding(pad)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search datasets...") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(16.dp))
                    IconButton(onClick = { filter = if (filter == "All") "Recent" else "All" }) { Icon(Icons.Default.FilterList, "Filter") }
                }
                Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("All", "Recent", "A–Z", "Modified").forEach { FilterChip(filter == it, { filter = it }, label = { Text(it) }) }
                }
                val visible = projects.filter { it.name.contains(query, true) || it.description.contains(query, true) }
                    .let { list -> if (filter == "A–Z") list.sortedBy { it.name.lowercase() } else if (filter == "Recent" || filter == "Modified") list.sortedByDescending { it.updated } else list }
                if (visible.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.FolderOpen, null, tint = brand, modifier = Modifier.size(52.dp))
                        Text("No datasets found", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { create = true }) { Icon(Icons.Default.Add, null); Text("Create Dataset") }
                    }
                } else LazyColumn(contentPadding = PaddingValues(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    items(visible, key = { it.id }) { p ->
                        val projectRoot = java.io.File(context.filesDir, "dataset-projects/${p.id}")
                        val imageCount = remember(p.id) { java.io.File(projectRoot, "train/images").listFiles()?.count { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") } ?: 0 }
                        val savedCount = remember(p.id, imageCount) { java.io.File(projectRoot, "train/labels").listFiles()?.count { it.isFile && it.extension.equals("txt", true) } ?: 0 }
                        ElevatedCard(onClick = { onOpen(p) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
                                Surface(color = brand.copy(alpha = .10f), shape = RoundedCornerShape(14.dp), modifier = Modifier.size(56.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Folder, null, tint = brand, modifier = Modifier.size(32.dp)) }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(p.description.ifBlank { "No description added" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        AssistChip(onClick = {}, label = { Text(p.labels.size.toString() + " Classes") }, leadingIcon = { Icon(Icons.Default.Label, null) })
                                        AssistChip(onClick = {}, label = { Text(imageCount.toString() + " Images") }, leadingIcon = { Icon(Icons.Default.Image, null) })
                                        AssistChip(onClick = {}, label = { Text(savedCount.toString() + " Saved") }, leadingIcon = { Icon(Icons.Default.CheckCircle, null) })
                                    }
                                    Text("Updated " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.updated)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Project actions") }
                                    DropdownMenu(expanded, { expanded = false }) {
                                        DropdownMenuItem(text = { Text("Edit Project") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { expanded = false; edit = p })
                                        DropdownMenuItem(text = { Text("Duplicate Dataset") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { expanded = false; save(p.name + " Copy", p.description, p.labels, null) })
                                        DropdownMenuItem(text = { Text("Delete Dataset", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { expanded = false; delete = p })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (create) ProjectDialog(null, { create = false }) { n, d, l -> save(n, d, l, null); create = false }
    edit?.let { old -> ProjectDialog(old, { edit = null }) { n, d, l -> save(n, d, l, old); edit = null } }
    delete?.let { p -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete dataset?") }, text = { Text("Project details for “" + p.name + "” will be removed.") }, confirmButton = { TextButton(onClick = { persist(projects.filterNot { it.id == p.id }); delete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
}

@Composable
private fun ProjectDialog(project: Project?, dismiss: () -> Unit, save: (String, String, List<String>) -> Unit) {
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var description by remember(project?.id) { mutableStateOf(project?.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (project == null) "Create New Dataset" else "Edit Project") },
        text = {
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Text("Annotation classes", fontWeight = FontWeight.SemiBold)
                listOf("Photo" to Color(0xFF1768E8), "Signature" to Color(0xFF168B58)).forEachIndexed { index, pair ->
                    Surface(color = pair.second.copy(alpha = 0.08f), shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(11.dp).background(pair.second, RoundedCornerShape(50)))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(pair.first, fontWeight = FontWeight.Medium)
                                Text("YOLO class ID: $index", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.Lock, contentDescription = "Fixed class", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("Photo and Signature class IDs are fixed to keep existing annotations and YOLO exports compatible.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { save(name, description, listOf("Photo", "Signature")) }) { Text(if (project == null) "Create Dataset" else "Save Changes") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsPage(modifier: Modifier = Modifier, back: () -> Unit) {
    var theme by remember { mutableStateOf("System default") }
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, null) }; Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        Text("Appearance", color = brand, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DarkMode, null); Text("Theme", Modifier.weight(1f).padding(start = 10.dp))
                    Box { TextButton(onClick = { expanded = true }) { Text(theme); Icon(Icons.Default.ArrowDropDown, null) }; DropdownMenu(expanded, { expanded = false }) { listOf("System default", "Light", "Dark").forEach { DropdownMenuItem(text = { Text(it) }, onClick = { theme = it; expanded = false }) } } }
                }
                HorizontalDivider()
                ListItem(headlineContent = { Text("Dynamic color") }, leadingContent = { Icon(Icons.Default.Palette, null) }, trailingContent = { var enabled by remember { mutableStateOf(true) }; Switch(enabled, { enabled = it }) })
            }
        }
        Text("Data & Storage", color = brand, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(16.dp)) { Column { ListItem(headlineContent = { Text("Dataset storage") }, supportingContent = { Text("App-private YOLO workspace") }, leadingContent = { Icon(Icons.Default.Folder, null) }); HorizontalDivider(); ListItem(headlineContent = { Text("Export format") }, supportingContent = { Text("YOLO dataset ZIP") }, leadingContent = { Icon(Icons.Default.Archive, null) }) } }
        Text("About", color = brand, fontWeight = FontWeight.Bold)
        ListItem(headlineContent = { Text("Dataset Builder") }, supportingContent = { Text("Version 1.0.0 • org.techwithkaushik") }, leadingContent = { Icon(Icons.Default.Info, null) })
    }
}

private fun readProjects(prefs: android.content.SharedPreferences): List<Project> {
    val json = prefs.getString("items", null) ?: return listOf(
        Project("starter-student", "Student Form Dataset", "Photo and signature annotation for school forms", listOf("Photo", "Signature", "Name"), System.currentTimeMillis()),
        Project("starter-aadhaar", "Aadhaar Card Dataset", "Front and back annotation for ID cards", listOf("Photo", "Signature", "Name", "Address"), System.currentTimeMillis() - 86400000L),
        Project("starter-form", "Form Snap Dataset", "Generic form field annotations", listOf("Photo", "Signature", "Name", "Date", "Address"), System.currentTimeMillis() - 3 * 86400000L),
        Project("starter-id", "ID Card Dataset", "Identity card photo and signature", listOf("Photo", "Signature"), System.currentTimeMillis() - 6 * 86400000L),
    )
    return runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val labels = o.optJSONArray("labels") ?: JSONArray()
            Project(o.getString("id"), o.getString("name"), o.optString("description"), (0 until labels.length()).map { labels.getString(it) }, o.optLong("updated"))
        }
    }.getOrDefault(emptyList())
}
