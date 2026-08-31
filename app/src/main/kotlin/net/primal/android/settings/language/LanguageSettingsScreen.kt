package net.primal.android.settings.language

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.theme.AppTheme

@Composable
fun LanguageSettingsScreen() {
    val context = LocalContext.current
    val selected = remember(context) { AppLanguageManager.selectedLanguage(context) }

    // This section is embedded in the settings LazyColumn.  Keeping the
    // language list in a bounded, nested LazyColumn is important: a plain
    // Column was measured at its full height and the parent clipped it after
    // the first few entries (Eesti on a typical phone), making the remaining
    // languages unreachable.
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 600.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item(key = "language_description") {
            Text(
                text = stringResource(R.string.settings_language_description),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        item(key = AppLanguage.SYSTEM_DEFAULT.tag) {
            LanguageOption(
                language = AppLanguage.SYSTEM_DEFAULT,
                selected = selected == AppLanguage.SYSTEM_DEFAULT,
                label = stringResource(R.string.settings_language_system_default),
                onClick = {
                    AppLanguageManager.setLanguage(context, AppLanguage.SYSTEM_DEFAULT)
                    recreateHostActivity(context)
                },
            )
            HorizontalDivider()
        }
        items(
            items = AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM_DEFAULT },
            key = { it.tag },
        ) { language ->
            LanguageOption(
                language = language,
                selected = selected == language,
                onClick = {
                    AppLanguageManager.setLanguage(context, language)
                    recreateHostActivity(context)
                },
            )
        }
    }
}

@Composable
private fun LanguageOption(
    language: AppLanguage,
    selected: Boolean,
    label: String = language.nativeName,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = { Text(label) },
        supportingContent = if (language != AppLanguage.SYSTEM_DEFAULT) {
            { Text(language.tag.uppercase()) }
        } else {
            null
        },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
    )
}

private fun recreateHostActivity(context: android.content.Context) {
    var current: android.content.Context? = context
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) {
            current.recreate()
            return
        }
        current = current.baseContext
    }
}
