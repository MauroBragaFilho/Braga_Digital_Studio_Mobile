package com.bragastudio.mobile.featuresettings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.bdsmTextFieldColors
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.core.domain.DisplayName

/**
 * Edição do nome de exibição da saudação da Home. Campo de uma linha, limite de [DisplayName.MAX_LENGTH]
 * caracteres, sem quebras de linha. Vazio = volta ao nome do aparelho ([deviceName], mostrado como dica).
 */
@Composable
internal fun DisplayNameDialog(current: String, deviceName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = BdsmTheme.shapes.container,
        title = { Text(stringResource(R.string.settings_display_name), style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = text,
                colors = bdsmTextFieldColors(),
                onValueChange = { text = it.filter { c -> !c.isISOControl() }.take(DisplayName.MAX_LENGTH) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                label = { Text(stringResource(R.string.settings_display_name)) },
                placeholder = { Text(deviceName) },
                supportingText = { Text(stringResource(R.string.settings_display_name_help, DisplayName.MAX_LENGTH)) },
                shape = BdsmTheme.shapes.item,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            BdsmTextButton(onClick = { onConfirm(DisplayName.sanitize(text)) }) { Text(stringResource(R.string.settings_display_name_save)) }
        },
        dismissButton = { BdsmTextButton(onClick = onDismiss) { Text(stringResource(com.bragastudio.mobile.common.R.string.bdsm_cancel)) } },
    )
}
