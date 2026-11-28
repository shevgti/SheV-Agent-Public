package com.shev.agent.device

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream

/** SAF-only workspace access. All operations stay inside the tree explicitly selected by the owner. */
object MegaAgentWorkspace {
    data class Entry(val name: String, val uri: String, val isDirectory: Boolean, val mimeType: String)

    private const val PREFS = "megaagent_device_bridge"
    private const val TREE_KEY = "workspace_tree_uri"
    private const val MAX_READ_BYTES = 512 * 1024

    private fun selectedTree(context: Context): Uri =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TREE_KEY, null)
            ?.let(Uri::parse)
            ?: throw IllegalStateException("Сначала выбери рабочую папку кнопкой 📁 в чате MegaAgent.")

    fun list(context: Context): List<Entry> {
        val tree = selectedTree(context)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val result = ArrayList<Entry>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext() && result.size < 200) {
                val id = cursor.getString(idColumn) ?: continue
                val name = cursor.getString(nameColumn).orEmpty()
                val mime = cursor.getString(mimeColumn).orEmpty()
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                result += Entry(name, uri.toString(), mime == DocumentsContract.Document.MIME_TYPE_DIR, mime)
            }
        }
        return result
    }

    fun read(context: Context, documentUri: String): String {
        require(documentUri.startsWith("content://")) { "Ожидается URI документа из выбранной рабочей папки." }
        val tree = selectedTree(context)
        val uri = Uri.parse(documentUri)
        require(DocumentsContract.isDocumentUri(context, uri)) { "URI не является документом SAF." }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val documentId = DocumentsContract.getDocumentId(uri)
        require(documentId == rootId || documentId.startsWith(rootId.trimEnd('/') + "/")) {
            "Отказано: файл находится за пределами выбранной рабочей папки."
        }
        val mime = context.contentResolver.getType(uri).orEmpty()
        require(mime != DocumentsContract.Document.MIME_TYPE_DIR) { "Нельзя читать каталог как файл." }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Не удалось открыть выбранный файл.")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_READ_BYTES) { "Файл превышает лимит чтения 512 КиБ." }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}
