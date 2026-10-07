package com.fahim.myide;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;

public final class NdkPicker {

    public static final int REQ_PICK_NDK = 3001;

    private NdkPicker() {}

    public static void launch(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        a.startActivityForResult(i, REQ_PICK_NDK);
    }

    public static String resolveToPath(Context ctx, Uri treeUri) {
        if (treeUri == null) return null;
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            int colon = docId.indexOf(':');
            if (colon < 0) return null;
            String type = docId.substring(0, colon);
            String rel  = docId.substring(colon + 1);

            File base;
            if ("primary".equalsIgnoreCase(type)) {
                base = Environment.getExternalStorageDirectory();
            } else {
                base = new File("/storage/" + type);
                if (!base.isDirectory()) return null;
            }
            File out = rel.isEmpty() ? base : new File(base, rel);
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }
}
