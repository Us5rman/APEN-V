package com.apenv.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dark tint used behind floating panels. No blur.
@Composable
internal fun BlurScrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .appear(0, 0f)
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }
    )
}

@Composable
internal fun FabAction(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .background(Panel, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
internal fun ActionSheet(
    title: String,
    extract: Boolean,
    onImport: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .appear(0, 56f)
            .fillMaxWidth()
            .background(Color(0xFF0A0E0E), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
            .padding(bottom = 12.dp)
    ) {
        Text(
            title, color = Color.White, fontSize = 18.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Bar))
        if (extract) SheetRow(Ic.BACK, "Extract", onImport)
        else SheetRow(Ic.CHEVRON, "Import", onImport)
        onRename?.let { SheetRow(Ic.RENAME, "Rename", it) }
        SheetRow(Ic.TRASH, "Delete", onDelete)
    }
}

@Composable
internal fun SheetRow(icon: Ic, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressScale(0.98f).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(icon, Color.White, 22.dp)
        Spacer(Modifier.width(22.dp))
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
internal fun PaneTab(label: String, count: Int, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tabBg by animateColorAsState(if (active) Color(0xFF17302D) else Panel, tween(220), label = "tabBg")
    val tabLine by animateColorAsState(if (active) Accent else Color.Transparent, tween(220), label = "tabLine")
    val tabText by animateColorAsState(if (active) Accent else Dim, tween(220), label = "tabText")
    Column(modifier.background(tabBg).clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label, color = tabText, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(count.toString(), color = Dim, fontSize = 11.sp)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(tabLine))
    }
}

@Composable
internal fun BarButton(icon: Ic, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        AppIcon(icon, if (enabled) Accent else Dim, 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

// One search control per screen. Collapsed it is a small square button that sits flush
// between the tabs and the path row. Tapping it widens into a bar, grows a little taller,
// and pushes the path row and lists down with an animation. It filters the active pane.
@Composable
internal fun SearchBar(
    open: Boolean,
    query: String,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onQuery: (String) -> Unit,
    loading: String? = null
) {
    var lastLabel by remember { mutableStateOf("") }
    if (loading != null) lastLabel = loading
    val loadAlpha by animateFloatAsState(if (loading != null) 1f else 0f, tween(220), label = "searchLoad")
    val barH by animateDpAsState(if (open) 50.dp else 40.dp, tween(300), label = "searchH")
    val ring by animateFloatAsState(if (open) 1f else 0f, tween(300), label = "searchRing")
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val fm = LocalFocusManager.current
    val shape = androidx.compose.ui.graphics.RectangleShape
    val view = androidx.compose.ui.platform.LocalView.current
    val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
        as android.view.inputmethod.InputMethodManager

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxWidth().background(Bg)
    ) {
        val w by animateDpAsState(if (open) maxWidth else 40.dp, tween(300), label = "searchW")
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .width(w)
                .height(barH)
                .background(Bar, shape)
                .border(1.dp, Accent.copy(alpha = ring * 0.6f), shape)
                .clickable(enabled = !open, onClick = onOpen)
                .padding(horizontal = if (open) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (open) Arrangement.Start else Arrangement.Center
        ) {
            AppIcon(Ic.SEARCH, if (open) Accent else Dim, 18.dp)
            if (open) {
                Spacer(Modifier.width(10.dp))
                // Native EditText: Compose's own text field wasn't taking input on this device,
                // and the classic view reliably gets focus and the keyboard.
                val currentOnQuery by androidx.compose.runtime.rememberUpdatedState(onQuery)
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    factory = { ctx ->
                        android.widget.EditText(ctx).apply {
                            background = null
                            setPadding(0, 0, 0, 0)
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 14f
                            setSingleLine(true)
                            inputType = android.text.InputType.TYPE_CLASS_TEXT
                            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                            setText(query)
                            setSelection(text.length)
                            addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                                override fun afterTextChanged(s: android.text.Editable?) {
                                    currentOnQuery(s?.toString() ?: "")
                                }
                            })
                            setOnEditorActionListener { v, actionId, _ ->
                                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                                    true
                                } else false
                            }
                            // Ask twice: once right away and once after the widening animation.
                            postDelayed({ requestFocus(); imm.showSoftInput(this, 0) }, 120)
                            postDelayed({ requestFocus(); imm.showSoftInput(this, 0) }, 380)
                        }
                    },
                    update = { et ->
                        if (et.text.toString() != query) {
                            et.setText(query)
                            et.setSelection(et.text.length)
                        }
                    }
                )
                AppIcon(
                    Ic.CLOSE, Dim, 18.dp,
                    Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable { fm.clearFocus(); keyboard?.hide(); imm.hideSoftInputFromWindow(view.windowToken, 0); onClose() }
                        .padding(4.dp)
                )
            }
        }
        if (loadAlpha > 0.01f) {
            if (open) {
                LinearProgressIndicator(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                        .graphicsLayer { alpha = loadAlpha },
                    color = Accent, trackColor = Color.Transparent
                )
            } else {
                Column(
                    Modifier.align(Alignment.CenterStart).width(maxWidth - 52.dp).padding(start = 12.dp)
                        .graphicsLayer { alpha = loadAlpha }
                ) {
                    Text(lastLabel + "…", color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Accent, trackColor = Bar)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Pane(
    modifier: Modifier,
    title: String,
    onUp: () -> Unit,
    items: List<Item>,
    query: String,
    selected: Set<String>,
    onTap: (Item) -> Unit,
    onToggle: (Item) -> Unit,
    onSheet: () -> Unit = {},
    empty: @Composable () -> Unit
) {
    val filtered = remember(items, query) {
        if (query.isBlank()) items else items.filter { it.name.contains(query, ignoreCase = true) }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onUp),
                contentAlignment = Alignment.Center
            ) { AppIcon(Ic.UP, Dim, 18.dp) }
            Spacer(Modifier.width(4.dp))
            Text(
                title, color = Color.White, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                if (items.isEmpty()) empty() else Text("No matches for \"$query\"", color = Dim, fontSize = 12.sp)
            }
        } else {
            // A new list for every folder, so the scroll position of the last one is never carried over.
            androidx.compose.runtime.key(title) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.key }) { item ->
                        val rowBg by animateColorAsState(
                            if (item.key in selected) Picked else Color.Transparent, tween(160), label = "row"
                        )
                        Row(
                            Modifier.fillMaxWidth()
                                .background(rowBg)
                                .combinedClickable(
                                    // Once anything is selected in this pane, a plain tap adds/removes instead of opening.
                                    onClick = { if (selected.isNotEmpty()) onToggle(item) else onTap(item) },
                                    onLongClick = { if (item.key in selected) onSheet() else onToggle(item) }
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileIcon(item.name, item.dir)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(item.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.info, color = Dim, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
