package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.scheme.SchemeDeployer
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.WubiEnhanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 五笔增强设置页（四码直接上屏 / Z键拼音反查 / 长句连打）。
 *
 * 开关只是草稿：点右上角「应用」才落 prefs，并对账各五笔方案 custom.yaml、
 * 部署使其生效（同模糊拼音页交互模式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WubiEnhanceSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var autoSelect by remember { mutableStateOf(SettingsPreferences.isWubiAutoSelect(context)) }
    var zReverse by remember { mutableStateOf(SettingsPreferences.isWubiZReverse(context)) }
    var longSentence by remember { mutableStateOf(SettingsPreferences.isWubiLongSentence(context)) }
    var savedAutoSelect by remember { mutableStateOf(autoSelect) }
    var savedZReverse by remember { mutableStateOf(zReverse) }
    var savedLongSentence by remember { mutableStateOf(longSentence) }
    var dirty by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var applicable by remember { mutableStateOf<List<String>>(emptyList()) }

    fun markDirty() {
        dirty = autoSelect != savedAutoSelect || zReverse != savedZReverse || longSentence != savedLongSentence
    }

    suspend fun applyAll() {
        applying = true
        SettingsPreferences.setWubiAutoSelect(context, autoSelect)
        SettingsPreferences.setWubiZReverse(context, zReverse)
        SettingsPreferences.setWubiLongSentence(context, longSentence)
        val (result, deployOk) = withContext(Dispatchers.IO) {
            val reconciled = WubiEnhanceManager.reconcile(context)
            val ok = if (reconciled.changedSchemas.isNotEmpty()) {
                SchemeDeployer.deploy(context).ok
            } else {
                true
            }
            reconciled to ok
        }
        applicable = withContext(Dispatchers.IO) {
            WubiEnhanceManager.applicableSchemas(context)
        }
        applying = false
        savedAutoSelect = autoSelect
        savedZReverse = zReverse
        savedLongSentence = longSentence
        dirty = false
        status = buildStatus(result, deployOk, applicable)
    }

    LaunchedEffect(Unit) {
        applicable = withContext(Dispatchers.IO) {
            WubiEnhanceManager.applicableSchemas(context)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(tr(R.string.s_wubizengqiang)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = tr(R.string.s_fanhui),
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { scope.launch { applyAll() } },
                            enabled = dirty && !applying,
                        ) {
                            Text(tr(R.string.s_fuzzy_apply))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                if (applying) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                Text(
                    text = when {
                        applying -> tr(R.string.s_fuzzy_applying)
                        dirty -> tr(R.string.s_fuzzy_pending_apply)
                        else -> status ?: tr(R.string.s_wubi_scope_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                SettingsSection(title = tr(R.string.s_shuruzengqiang)) {
                    WubiToggleRow(
                        title = tr(R.string.s_sima_zhijie_shangping),
                        subtitle = tr(R.string.s_sima_shangping_shuoming),
                        checked = autoSelect,
                        enabled = !applying,
                        onCheckedChange = { autoSelect = it; markDirty() },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    WubiToggleRow(
                        title = tr(R.string.s_zjian_pinyin_fancha),
                        subtitle = tr(R.string.s_zjian_fancha_shuoming),
                        checked = zReverse,
                        enabled = !applying,
                        onCheckedChange = { zReverse = it; markDirty() },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    WubiToggleRow(
                        title = tr(R.string.s_wubi_changju_lianda),
                        subtitle = tr(R.string.s_wubi_changju_shuoming),
                        checked = longSentence,
                        enabled = !applying,
                        onCheckedChange = { longSentence = it; markDirty() },
                    )
                }
            }

            item(key = "supported") {
                SettingsSection(title = tr(R.string.s_fuzzy_shiyongfangan)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val names = applicable.map { schemaId ->
                            SchemaManager.getSchemaDisplayName(context, schemaId) ?: schemaId
                        }
                        Text(
                            text = if (names.isEmpty()) {
                                tr(R.string.s_wubi_none)
                            } else {
                                names.joinToString("、")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = tr(R.string.s_wubi_scope_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "hint") {
                SettingsSection(title = tr(R.string.s_shuoming)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = tr(R.string.s_wubi_changju_shuoming),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = tr(R.string.s_wubi_zreverse_jiaochangju_shuoming),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WubiToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
}

private fun buildStatus(
    result: WubiEnhanceManager.ReconcileResult,
    deployOk: Boolean,
    applicable: List<String>,
): String? {
    if (!deployOk) return tr(R.string.s_fuzzy_apply_failed)
    val parts = mutableListOf<String>()
    if (result.changedSchemas.isNotEmpty()) {
        parts += tr(R.string.s_fuzzy_applied_to, result.changedSchemas.joinToString("、"))
    }
    if (result.skippedSchemas.isNotEmpty()) {
        parts += tr(R.string.s_wubi_user_patch_conflict, result.skippedSchemas.joinToString("、"))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
