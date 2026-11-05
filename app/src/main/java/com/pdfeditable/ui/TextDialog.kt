package com.pdfeditable.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.pdfeditable.R
import com.pdfeditable.model.TextAnnotation
import kotlin.math.roundToInt

/** Add or edit a text annotation: the text itself plus a font size slider. */
@Composable
fun TextDialog(
    initialText: String,
    initialFontSize: Float,
    isEditing: Boolean,
    onConfirm: (text: String, fontSize: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    var fontSize by rememberSaveable { mutableStateOf(initialFontSize) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (isEditing) R.string.dialog_text_edit_title else R.string.dialog_text_title))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.dialog_text_label)) },
                    minLines = 2,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Text(
                    text = stringResource(R.string.dialog_text_size, fontSize.roundToInt()),
                    modifier = Modifier.padding(top = 16.dp),
                )
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it.roundToInt().toFloat() },
                    valueRange = TextAnnotation.MIN_FONT_SIZE..TextAnnotation.MAX_FONT_SIZE,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim(), fontSize) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
