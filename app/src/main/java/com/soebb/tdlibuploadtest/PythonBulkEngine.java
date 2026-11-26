package com.soebb.tdlibuploadtest;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;

/** Synchronous bridge to Python/SQLite. Call methods from the IO executor. */
public final class PythonBulkEngine {
    private final PyObject module;
    private final String databaseDirectory;
    private final String downloadPath;

    public PythonBulkEngine(Context context) {
        Context app = context.getApplicationContext();
        if (!Python.isStarted()) Python.start(new AndroidPlatform(app));
        module = Python.getInstance().getModule("bulk_downloader");
        File root = new File(app.getFilesDir(), "bulk-downloader");
        File downloads = new File(root, "downloads");
        databaseDirectory = new File(root, "databases").getAbsolutePath();
        downloadPath = downloads.getAbsolutePath();
        // Deliberately do not create a database here. A DB is created only
        // when the user supplies a non-empty link batch.
    }

    public String createBatch(String text, String name) {
        return module.callAttr("create_batch_json", databaseDirectory, text, name).toString();
    }
    public String listBatches() {
        return module.callAttr("list_batches", databaseDirectory).toString();
    }
    public boolean deleteBatch(String path) {
        return module.callAttr("delete_batch", path).toBoolean();
    }
    public void initialize(String databasePath) { module.callAttr("initialize", databasePath); }
    public String counts(String databasePath) {
        return module.callAttr("counts_json", databasePath).toString();
    }
    public String nextPending(String databasePath) {
        PyObject value = module.callAttr("next_pending", databasePath);
        return value == null || "None".equals(value.toString()) ? null : value.toString();
    }
    public interface DownloadProgressListener {
        void onProgress(String fileName, int percent);
    }

    public String downloadPost(String url, DownloadProgressListener listener) {
        return module.callAttr("download_post", url, downloadPath, listener).toString();
    }
    public String sentChunks(String databasePath, String url) {
        return module.callAttr("sent_chunks_json", databasePath, url).toString();
    }
    public String planChunks(String mediaJson, String sentChunksJson) {
        return module.callAttr("chunk_plan_json", mediaJson, sentChunksJson).toString();
    }
    public int markProcessing(String databasePath, String url) {
        return module.callAttr("mark_processing", databasePath, url).toInt();
    }
    public int attemptsFor(String databasePath, String url) {
        return module.callAttr("attempts_for", databasePath, url).toInt();
    }
    public void releaseProcessing(String databasePath, String url) {
        module.callAttr("release_processing", databasePath, url);
    }
    public String retryOrFail(String databasePath, String url, String error) {
        return module.callAttr("retry_or_fail", databasePath, url, error, 4).toString();
    }
    public void markDone(String databasePath, String url) {
        module.callAttr("mark_done", databasePath, url);
    }
    public void markReview(String databasePath, String url, String error) {
        module.callAttr("mark_review", databasePath, url, error);
    }
    public String reviewPosts(String databasePath) {
        return module.callAttr("review_posts_json", databasePath).toString();
    }
    public void resolveReview(String databasePath, String url, boolean uploaded) {
        module.callAttr("resolve_review", databasePath, url, uploaded);
    }
    public void markChunkSent(String databasePath, String url, int number, String signature) {
        module.callAttr("mark_chunk_sent", databasePath, url, number, signature);
    }
    public void cleanup(String url) {
        module.callAttr("cleanup_post", url, downloadPath);
    }
}
