package com.opencode.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class Editor(root: File) {
    var directory by mutableStateOf(root)
    var file by mutableStateOf<File?>(null)
    var text by mutableStateOf("")
    var original by mutableStateOf("")
}
