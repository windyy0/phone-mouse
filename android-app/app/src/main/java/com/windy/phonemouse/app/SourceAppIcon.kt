package com.windy.phonemouse.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource

@Composable internal fun SourceAppIcon(encoded: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(encoded) {
        runCatching {
            if (encoded.isNullOrEmpty() || encoded.length > MAX_SOURCE_ICON_BASE64) return@runCatching null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            // Check size before allocating decoded pixels. A bad icon must not
            // discard a valid text snapshot or expand the source row.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outMimeType != "image/png" || bounds.outWidth !in 1..64 || bounds.outHeight !in 1..64) return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier)
    } else {
        Icon(painterResource(R.drawable.ic_document), contentDescription = null,
            tint = LocalAppColors.current.muted, modifier = modifier)
    }
}
