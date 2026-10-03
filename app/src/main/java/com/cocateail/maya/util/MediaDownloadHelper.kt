package com.cocateail.maya.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast

object MediaDownloadHelper {
    fun downloadToGallery(context: Context, mediaUrl: String, isVideo: Boolean, customTitle: String? = null) {
        try {
            val fileName = "MAYA_${if (isVideo) "Video" else "Image"}_${System.currentTimeMillis()}." + (if (isVideo) "mp4" else "jpg")
            val subDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

            val request = DownloadManager.Request(Uri.parse(mediaUrl))
                .setTitle(customTitle?.take(30) ?: if (isVideo) "AI Generated Video" else "AI Generated Image")
                .setDescription("Saving media to Gallery...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(subDir, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(
                context,
                "📥 Downloading ${if (isVideo) "Video" else "Picture"} to Gallery...",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Download failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
