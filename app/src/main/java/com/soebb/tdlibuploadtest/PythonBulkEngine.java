package com.soebb.tdlibuploadtest;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;

/** Small synchronous bridge. Call methods from the app's IO executor, never the UI thread. */
public final class PythonBulkEngine {
    private final PyObject module;
    private final String databasePath;
    private final String downloadPath;

    public PythonBulkEngine(Context context) {
        Context app = context.getApplicationContext();
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(app));
        }
        module = Python.getInstance().getModule("bulk_downloader");
        File root = new File(app.getFilesDir(), "bulk-downloader");
        File downloads = new File(root, "downloads");
        if (!downloads.exists() && !downloads.mkdirs()) {
            throw new IllegalStateException("Could not create downloader directory: " + downloads);
        }
        databasePath = new File(root, "progress.sqlite3").getAbsolutePath();
        downloadPath = downloads.getAbsolutePath();
        module.callAttr("initialize", databasePath);
    }

    public String importLinks(String text) {
        return module.callAttr("import_links_json", databasePath, text).toString();
    }

    public String counts() {
        return module.callAttr("counts_json", databasePath).toString();
    }

    public String nextPending() {
        PyObject value = module.callAttr("next_pending", databasePath);
        return value == null || "None".equals(value.toString()) ? null : value.toString();
    }

    public String inspectPost(String url) {
        return module.callAttr("inspect_post_json", url).toString();
    }

    public String downloadPost(String url) {
        return module.callAttr("download_post", url, downloadPath).toString();
    }

    public String sentChunks(String url) {
        return module.callAttr("get_sent_chunks", databasePath, url).toString();
    }

    public String planChunks(String mediaJson, String sentChunksJson) {
        return module.callAttr("chunk_plan_json", mediaJson, sentChunksJson).toString();
    }

    public void markProcessing(String url) {
        module.callAttr("mark_processing", databasePath, url);
    }

    public void markDone(String url) {
        module.callAttr("mark_done", databasePath, url);
    }

    public void markFailed(String url, String error) {
        module.callAttr("mark_failed", databasePath, url, error);
    }

    public void markChunkSent(String url, int number, String signature) {
        module.callAttr("mark_chunk_sent", databasePath, url, number, signature);
    }

    public void cleanup(String url) {
        module.callAttr("cleanup_post", url, downloadPath);
    }
}
