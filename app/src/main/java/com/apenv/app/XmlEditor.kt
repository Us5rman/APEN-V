package com.apenv.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

// A text file opened for editing. save() persists it to wherever it came from.
internal class XmlDoc(val name: String, val text: String, val save: (String) -> Unit)

private fun checkXml(text: String): String? = try {
    val f = DocumentBuilderFactory.newInstance()
    f.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(text)))
    null
} catch (e: org.xml.sax.SAXParseException) {
    "Line ${e.lineNumber}: ${e.message}"
} catch (e: Exception) {
    e.message ?: "Invalid XML"
}

private fun prettyXml(text: String): String {
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(org.xml.sax.InputSource(StringReader(text)))
    val t = TransformerFactory.newInstance().newTransformer()
    t.setOutputProperty(OutputKeys.INDENT, "yes")
    t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    val w = StringWriter()
    t.transform(DOMSource(doc), StreamResult(w))
    return w.toString()
}

@Composable
internal fun XmlEditor(doc: XmlDoc, onClose: () -> Unit, onSaved: () -> Unit) {
    var value by remember(doc) { mutableStateOf(TextFieldValue(doc.text)) }
    var saved by remember(doc) { mutableStateOf(doc.text) }
    var status by remember(doc) { mutableStateOf<String?>(null) }
    var statusOk by remember(doc) { mutableStateOf(true) }
    var confirmExit by remember { mutableStateOf(false) }
    val dirty = value.text != saved
    val isXml = doc.name.substringAfterLast('.', "").lowercase() in setOf("xml", "meta")

    val tryClose = { if (dirty) confirmExit = true else onClose() }
    BackHandler { tryClose() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(Ic.BACK, Color.White, 24.dp, Modifier.clickable { tryClose() }.padding(end = 4.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(if (dirty) "Unsaved changes" else "XML editor", color = if (dirty) Accent else Dim, fontSize = 12.sp)
            }
            AppIcon(Ic.CHECK, if (dirty) Accent else Dim, 24.dp, Modifier.clickable {
                doc.save(value.text)
                saved = value.text
                status = "Saved"
                statusOk = true
                onSaved()
            }.padding(8.dp))
        }
        if (isXml) {
            Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp)) {
                TextButton(onClick = {
                    val err = checkXml(value.text)
                    status = err ?: "XML is valid"
                    statusOk = err == null
                }) { Text("Check syntax", color = Accent, fontSize = 13.sp) }
                TextButton(onClick = {
                    runCatching { prettyXml(value.text) }
                        .onSuccess { value = TextFieldValue(it); status = "Formatted"; statusOk = true }
                        .onFailure { status = checkXml(value.text) ?: "Could not format"; statusOk = false }
                }) { Text("Format", color = Accent, fontSize = 13.sp) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Bar))
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState())) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = TextStyle(color = Color(0xFFE6E6EA), fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        status?.let {
            Box(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(it, color = if (statusOk) Accent else Color(0xFFE57373), fontSize = 12.sp)
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Discard changes?", fontSize = 15.sp) },
            text = { Text("${doc.name} has unsaved changes.", color = Dim) },
            confirmButton = { TextButton(onClick = { confirmExit = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Keep editing") } }
        )
    }
}

