package com.devoid.keysync.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.devoid.keysync.MainActivityViewModel
import com.devoid.keysync.R
import com.devoid.keysync.data.local.MumuProfileLibrary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameProfilesScreen(
    packageName: String,
    library: MumuProfileLibrary,
    viewModel: MainActivityViewModel,
    onNavigateBack: () -> Unit
) {
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            name = viewModel.suggestMumuProfileName(uri, packageName)
            importError = null
            pendingUri = uri.toString()
        }
    }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text(viewModel.getPackageLabel(packageName)) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.back))
                }
            }
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp)
                    .fillMaxWidth().selectableGroup().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.game_profiles), style = MaterialTheme.typography.headlineSmall)
                        Text(packageName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.mumu_import_description))
                        Button(onClick = {
                            launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        }) { Text(stringResource(R.string.import_mumu)) }
                    }
                }
                item {
                    Card {
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = library.selectedId == null, role = Role.RadioButton,
                                onClick = { viewModel.selectMumuProfile(packageName, null) }
                            ).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = library.selectedId == null, onClick = null)
                            Text(stringResource(R.string.use_existing_mapping), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                items(library.profiles, key = { it.id }) { profile ->
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.weight(1f).selectable(
                                    selected = library.selectedId == profile.id, role = Role.RadioButton,
                                    onClick = { viewModel.selectMumuProfile(packageName, profile.id) }
                                ).padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = library.selectedId == profile.id, onClick = null)
                                Text(profile.name, Modifier.padding(start = 8.dp))
                            }
                            IconButton(onClick = { name = profile.name; renameId = profile.id }) {
                                Icon(Icons.Rounded.Edit, stringResource(R.string.rename_profile))
                            }
                            IconButton(onClick = { deleteId = profile.id }) {
                                Icon(Icons.Rounded.Delete, stringResource(R.string.delete_profile))
                            }
                        }
                    }
                }
                if (library.profiles.isEmpty()) {
                    item { Text(stringResource(R.string.no_mumu_profiles), Modifier.padding(vertical = 16.dp)) }
                }
            }
        }
    }

    if (pendingUri != null || renameId != null) {
        val isImport = pendingUri != null
        val validName = name.isNotBlank() && name.trim().length <= MumuProfileLibrary.MAX_NAME_LENGTH
        AlertDialog(
            onDismissRequest = { if (!importing) { pendingUri = null; renameId = null } },
            title = { Text(stringResource(if (isImport) R.string.import_mumu else R.string.rename_profile)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isImport) Text(stringResource(R.string.mumu_import_warning))
                    OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !importing,
                        singleLine = true, label = { Text(stringResource(R.string.profile_name)) }, isError = !validName)
                    if (isImport && importError != null) Text(importError!!, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = validName && !importing, onClick = {
                    if (isImport) {
                        importing = true
                        viewModel.importMumuProfile(Uri.parse(pendingUri!!), packageName, name) { error ->
                            importing = false
                            importError = error
                            if (error == null) pendingUri = null
                        }
                    } else {
                        viewModel.renameMumuProfile(packageName, renameId!!, name)
                        renameId = null
                    }
                }) { Text(stringResource(if (isImport) R.string.import_mumu else R.string.save)) }
            },
            dismissButton = {
                TextButton(enabled = !importing, onClick = { pendingUri = null; renameId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.delete_profile)) },
            text = { Text(stringResource(R.string.delete_profile_warning)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteMumuProfile(packageName, deleteId!!); deleteId = null }) {
                    Text(stringResource(R.string.delete_profile))
                }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}
