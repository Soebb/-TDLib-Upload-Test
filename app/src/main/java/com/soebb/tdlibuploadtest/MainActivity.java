package com.soebb.tdlibuploadtest;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.Gravity;
import android.widget.*;
import android.content.SharedPreferences;
import android.text.TextWatcher;
import android.text.Editable;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private EditText apiId, apiHash, phone, emailAddressInput, code, channel, captionInput, linksInput;
    private Button submitEmailAddressButton;
    private TextView status, selected, codeInfoView, accountState, queueSummary;
    private Spinner databaseSpinner;
    private Button deleteDatabaseButton, deleteCompletedDatabasesButton, reviewUncertainButton;
    private final ArrayList<BatchItem> successfulDatabaseItems = new ArrayList<>();
    private final ArrayList<BatchItem> databaseItems = new ArrayList<>();
    private volatile String selectedDatabasePath = "";
    private volatile String bulkDatabasePath = "";
    private volatile boolean bulkFailureHandling = false;
    private Runnable bulkRetryRunnable;
    private Button resendButton, connectButton, submitCodeButton, uploadButton, stopButton;
    private ProgressBar uploadProgress;
    private TextView uploadProgressText;
    private final StringBuilder logBuffer = new StringBuilder();
    private volatile boolean isSignedIn = false;
    private boolean uploadInProgress = false;
    private int uploadFileId = 0;
    private long uploadTotalBytes = 0;
    private int lastLoggedProgressStep = -1;
    // No-progress timeout for a single Telegram file upload. Set to 0 to disable.
    private static final long UPLOAD_STALL_TIMEOUT_MS = 120_000L;
    private final HashMap<Integer, UploadProgressTrack> uploadTracksById = new HashMap<>();
    private final HashMap<String, UploadProgressTrack> uploadTracksByPath = new HashMap<>();
    private final HashMap<Long, UploadProgressTrack> uploadTracksByMessageId = new HashMap<>();
    private final HashSet<Long> completedUploadMessageIds = new HashSet<>();
    private static final class UploadProgressTrack {
        final String name;
        final long totalBytes;
        int lastLoggedStep = 0;
        long lastUploadedBytes = 0L;
        long messageId = 0L;
        long chatId = 0L;
        Runnable timeoutRunnable;
        boolean timeoutTriggered = false;
        boolean finished = false;
        UploadProgressTrack(File file) { name = file.getName(); totalBytes = Math.max(1L, file.length()); }
    }
    private Client client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int resendSecondsRemaining = 0;
    private boolean hasNextCodeMethod = false;
    private Runnable resendCountdown;
    private File chosenFile;
    private String chosenFileMimeType = "";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int pendingApiId;
    private String pendingApiHash = "";
    private boolean phoneSubmitted = false;
    private volatile boolean waitingForPhoneNumber = false;
    private volatile boolean waitingForEmailAddress = false;
    private volatile boolean waitingForEmailCode = false;
    private boolean loginRequested = false;
    private SharedPreferences preferences;
    private PythonBulkEngine bulkEngine;
    private volatile boolean bulkUploadRunning = false;
    private volatile boolean bulkStopRequested = false;
    private volatile boolean bulkPostLoadInProgress = false;
    private volatile boolean bulkDownloadInProgress = false;
    private volatile boolean bulkChunkInFlight = false;
    private long bulkChatId = 0;
    private String bulkCurrentUrl = "";
    private String bulkCurrentCaption = "";
    private JSONArray bulkCurrentFiles;
    private JSONArray bulkCurrentMedia;
    private JSONArray bulkChunkPlan;
    private int bulkChunkIndex = 0;
    private String bulkCaptionOverride = "";
    private int bulkCurrentChunkNumber = -1;
    private String bulkCurrentChunkSignature = "";
    private boolean bulkChunkResponseReceived = false;
    private final HashSet<Long> bulkAwaitingMessageIds = new HashSet<>();
    private final HashSet<Long> bulkSucceededBeforeResponse = new HashSet<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        preferences = getSharedPreferences("tdlib_upload_test", MODE_PRIVATE);
        restoreForm();
        attachFormPersistence();
        initializeBulkEngine();
        setAccountState("Checking saved session...");
        if (hasSavedCredentials()) startTdlib(false);
        else {
            setAccountState("Not signed in");
            log("Enter your Telegram API credentials to get started.");
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(24,24,24,24);
        scroll.addView(root);
        TextView title = new TextView(this); title.setText("TDLib Upload Test"); title.setTextSize(24); root.addView(title);
        TextView note = new TextView(this); note.setText("Personal account • Official TDLib"); root.addView(note);
        accountState = new TextView(this);
        accountState.setText("Not signed in");
        accountState.setTextSize(16);
        accountState.setPadding(0,16,0,16);
        root.addView(accountState);
        apiId = field(root,"API ID (from my.telegram.org)");
        apiHash = field(root,"API Hash");
        phone = field(root,"Phone number (international format)");
        emailAddressInput = field(root,"Email address (only if Telegram asks)");
        emailAddressInput.setVisibility(View.GONE);
        submitEmailAddressButton = button(root,"Submit email address",v -> submitEmailAddress());
        submitEmailAddressButton.setVisibility(View.GONE);
        connectButton = button(root,"Connect / Send login code",v -> startLogin());
        code = field(root,"Telegram login code");
        submitCodeButton = button(root,"Submit code",v -> submitCode());
        codeInfoView = new TextView(this);
        codeInfoView.setText("Code delivery information will appear here.");
        codeInfoView.setPadding(0,12,0,8);
        root.addView(codeInfoView);
        resendButton = new Button(this);
        resendButton.setText("No alternative code method available");
        resendButton.setEnabled(false);
        resendButton.setOnClickListener(v -> requestNextCodeMethod());
        root.addView(resendButton);
        channel = field(root,"Public channel username (e.g. @mychannel)");
        captionInput = field(root,"Caption override (optional)");
        TextView bulkTitle = new TextView(this);
        bulkTitle.setText("Instagram bulk queue");
        bulkTitle.setTextSize(20);
        bulkTitle.setPadding(0,24,0,4);
        root.addView(bulkTitle);
        TextView bulkHint = new TextView(this);
        bulkHint.setText("Paste Instagram links, one URL per line, or import a links.txt file. SQLite stores progress locally.");
        root.addView(bulkHint);
        linksInput = new EditText(this);
        linksInput.setHint("https://www.instagram.com/reel/...");
        linksInput.setMinLines(5);
        linksInput.setMaxLines(10);
        linksInput.setGravity(Gravity.TOP | Gravity.LEFT);
        linksInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        linksInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        root.addView(linksInput);
        LinearLayout databaseRow = new LinearLayout(this);
        databaseRow.setOrientation(LinearLayout.HORIZONTAL);
        databaseSpinner = new Spinner(this);
        databaseRow.addView(databaseSpinner, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        deleteDatabaseButton = new Button(this);
        deleteDatabaseButton.setText("Delete");
        deleteDatabaseButton.setVisibility(View.GONE);
        deleteDatabaseButton.setOnClickListener(v -> deleteSelectedDatabase());
        databaseRow.addView(deleteDatabaseButton);
        deleteCompletedDatabasesButton = button(root, "Delete completed databases (0)", v -> showSuccessfulDatabasesForDeletion());
        reviewUncertainButton = button(root, "Review uncertain uploads (0)", v -> showUncertainPostsForReview());
        reviewUncertainButton.setEnabled(false);
        root.addView(databaseRow);
        queueSummary = new TextView(this);
        queueSummary.setText("No link databases yet. Paste links or import a TXT file.");
        root.addView(queueSummary);
        databaseSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bulkUploadRunning || position < 0 || position >= databaseItems.size()) return;
                BatchItem item = databaseItems.get(position);
                selectedDatabasePath = item.path;
                showSelectedDatabase(item);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        button(root,"Import links.txt file",v -> chooseLinksFile());
        button(root,"Upload a local file",v -> chooseFile());
        selected = new TextView(this); selected.setText("No file selected"); root.addView(selected);
        uploadButton = button(root,"Start uploading",v -> startBulkUpload());
        stopButton = button(root,"Stop",v -> requestBulkStop());
        stopButton.setEnabled(false);
        uploadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        uploadProgress.setMax(100);
        uploadProgress.setVisibility(View.GONE);
        root.addView(uploadProgress);
        uploadProgressText = new TextView(this);
        uploadProgressText.setText("Upload idle");
        root.addView(uploadProgressText);
        status = new TextView(this); status.setTextIsSelectable(true); status.setText("Not connected"); status.setPadding(0,24,0,0); root.addView(status);
        setContentView(scroll);
    }
    private EditText field(LinearLayout root,String hint) {
        EditText e=new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        e.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        e.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        root.addView(e);
        return e;
    }
    private Button button(LinearLayout root,String text,View.OnClickListener click) {
        Button b=new Button(this); b.setText(text); b.setOnClickListener(click); root.addView(b); return b;
    }
    private void log(String message) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + message;
        android.util.Log.i("TDLibUploadTest", message);
        runOnUiThread(() -> {
            logBuffer.append(line).append("\n");
            // Preserve the opening context and recent activity, while bounding memory use.
            String[] lines = logBuffer.toString().split("\n");
            if (lines.length > 300) {
                StringBuilder compact = new StringBuilder();
                for (int i = 0; i < 30; i++) compact.append(lines[i]).append("\n");
                compact.append("... older middle log entries omitted ...\n");
                for (int i = lines.length - 269; i < lines.length; i++) compact.append(lines[i]).append("\n");
                logBuffer.setLength(0);
                logBuffer.append(compact);
            }
            status.setText(logBuffer.toString());
        });
    }

    private void setLoginUiVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        apiId.setVisibility(v); apiHash.setVisibility(v); phone.setVisibility(v);
        emailAddressInput.setVisibility(visible && waitingForEmailAddress ? View.VISIBLE : View.GONE);
        submitEmailAddressButton.setVisibility(visible && waitingForEmailAddress ? View.VISIBLE : View.GONE);
        connectButton.setVisibility(v); code.setVisibility(v); submitCodeButton.setVisibility(v);
        codeInfoView.setVisibility(v); resendButton.setVisibility(v);
    }

    private void setAccountState(String state) {
        runOnUiThread(() -> accountState.setText("Account: " + state));
    }

    private boolean hasSavedCredentials() {
        return !apiId.getText().toString().trim().isEmpty()
            && !apiHash.getText().toString().trim().isEmpty();
    }

    private void restoreForm() {
        apiId.setText(preferences.getString("api_id", ""));
        apiHash.setText(preferences.getString("api_hash", ""));
        phone.setText(preferences.getString("phone", ""));
        emailAddressInput.setText(preferences.getString("email_address", ""));
        code.setText(preferences.getString("code", ""));
        channel.setText(preferences.getString("channel", ""));
        captionInput.setText(preferences.getString("caption", ""));
    }

    private void attachFormPersistence() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { saveForm(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        apiId.addTextChangedListener(watcher);
        apiHash.addTextChangedListener(watcher);
        phone.addTextChangedListener(watcher);
        emailAddressInput.addTextChangedListener(watcher);
        code.addTextChangedListener(watcher);
        channel.addTextChangedListener(watcher);
        captionInput.addTextChangedListener(watcher);
    }

    private void saveForm() {
        if (preferences == null || apiId == null) return;
        preferences.edit()
            .putString("api_id", apiId.getText().toString())
            .putString("api_hash", apiHash.getText().toString())
            .putString("phone", phone.getText().toString())
            .putString("email_address", emailAddressInput.getText().toString())
            .putString("code", code.getText().toString())
            .putString("channel", channel.getText().toString())
            .putString("caption", captionInput.getText().toString())
            .apply();
    }

    private void initializeBulkEngine() {
        io.execute(() -> {
            try {
                bulkEngine = new PythonBulkEngine(this);
                refreshBatchListOnIo("");
                log("Python/Chaquopy initialized. No database is created until links are imported.");
            } catch (Throwable error) { log("Python engine initialization failed: " + error.getMessage()); }
        });
    }

    private static final class BatchItem {
        final String path, filename, name;
        final int total, done, failed, pending, processing, review;
        final boolean processed, complete;
        BatchItem(JSONObject o) {
            path=o.optString("path"); filename=o.optString("filename"); name=o.optString("name", filename);
            total=o.optInt("total"); done=o.optInt("done"); failed=o.optInt("failed");
            pending=o.optInt("pending"); processing=o.optInt("processing"); review=o.optInt("review"); processed=o.optBoolean("processed"); complete=o.optBoolean("complete");
        }
        String label() { return name + "\nTotal: " + total + "  •  Done: " + done + "  •  Failed: " + failed + "  •  Review: " + review; }
    }

    private void refreshBatchListOnIo(String preferredPath) {
        try {
            if (bulkEngine == null) bulkEngine = new PythonBulkEngine(this);
            JSONArray array = new JSONArray(bulkEngine.listBatches());
            ArrayList<BatchItem> loaded = new ArrayList<>();
            ArrayList<BatchItem> successful = new ArrayList<>();
            for (int i=0;i<array.length();i++) {
                BatchItem item = new BatchItem(array.getJSONObject(i));
                if (item.complete) successful.add(item); else loaded.add(item);
            }
            runOnUiThread(() -> {
                databaseItems.clear(); databaseItems.addAll(loaded);
                successfulDatabaseItems.clear(); successfulDatabaseItems.addAll(successful);
                deleteCompletedDatabasesButton.setText("Delete completed databases (" + successfulDatabaseItems.size() + ")");
                deleteCompletedDatabasesButton.setEnabled(!bulkUploadRunning && !successfulDatabaseItems.isEmpty());
                ArrayList<String> labels = new ArrayList<>();
                int selectedIndex = -1;
                for (int i=0;i<databaseItems.size();i++) {
                    BatchItem item=databaseItems.get(i); labels.add(item.label());
                    if (item.path.equals(preferredPath) || item.path.equals(selectedDatabasePath)) selectedIndex=i;
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
                    @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                        TextView view=(TextView)super.getView(position,convertView,parent);
                        view.setSingleLine(false); view.setTextSize(13); view.setPadding(8,8,8,8); return view;
                    }
                    @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                        TextView view=(TextView)super.getDropDownView(position,convertView,parent);
                        view.setSingleLine(false); view.setTextSize(14); view.setPadding(16,12,16,12); return view;
                    }
                };
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                databaseSpinner.setAdapter(adapter);
                if (selectedIndex >= 0) {
                    selectedDatabasePath = databaseItems.get(selectedIndex).path;
                    databaseSpinner.setSelection(selectedIndex);
                    showSelectedDatabase(databaseItems.get(selectedIndex));
                } else if (!databaseItems.isEmpty()) {
                    selectedDatabasePath = databaseItems.get(0).path;
                    databaseSpinner.setSelection(0);
                    showSelectedDatabase(databaseItems.get(0));
                } else {
                    selectedDatabasePath=""; queueSummary.setText("No link databases yet. Paste links or import a TXT file.");
                    deleteDatabaseButton.setVisibility(View.GONE);
                }
            });
        } catch (Throwable error) { log("Could not list link databases: " + error.getMessage()); }
    }

    private void showSelectedDatabase(BatchItem item) {
        queueSummary.setText("Database: " + item.name + "\nTotal links: " + item.total +
            "  •  Completed: " + item.done + "  •  Failed: " + item.failed +
            "  •  Pending: " + item.pending + "  •  Processing: " + item.processing +
            "  •  Needs review: " + item.review);
        deleteDatabaseButton.setVisibility(item.processed ? View.VISIBLE : View.GONE);
        reviewUncertainButton.setText("Review uncertain uploads (" + item.review + ")");
        reviewUncertainButton.setEnabled(!bulkUploadRunning && item.review > 0);
        uploadButton.setEnabled(!bulkUploadRunning);
    }

    private boolean isSelectedDatabaseComplete() {
        for (BatchItem item : databaseItems) if (item.path.equals(selectedDatabasePath)) return item.complete;
        return false;
    }

    private void showUncertainPostsForReview() {
        if (bulkUploadRunning || selectedDatabasePath.isEmpty()) return;
        final String path = selectedDatabasePath;
        io.execute(() -> {
            try {
                JSONArray posts = new JSONArray(bulkEngine.reviewPosts(path));
                if (posts.length() == 0) {
                    log("This database has no uploads awaiting review.");
                    refreshBatchListOnIo(path);
                    return;
                }
                String[] labels = new String[posts.length()];
                for (int i = 0; i < posts.length(); i++) {
                    JSONObject post = posts.getJSONObject(i);
                    labels[i] = post.optString("url") + "\n" + post.optString("error");
                }
                runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                    .setTitle("Uploads needing review")
                    .setItems(labels, (dialog, which) -> {
                        try {
                            JSONObject post = posts.getJSONObject(which);
                            String url = post.optString("url");
                            new android.app.AlertDialog.Builder(this)
                                .setTitle("Check this Instagram post")
                                .setMessage("The app cannot confirm whether Telegram accepted every media item. Open your Telegram channel and inspect this post before choosing.\\n\\n" + url)
                                .setPositiveButton("Mark as uploaded", (d, w) -> resolveUncertainPost(path, url, true))
                                .setNeutralButton("Retry upload", (d, w) -> new android.app.AlertDialog.Builder(this)
                                    .setTitle("Retry may create duplicates")
                                    .setMessage("Only retry if you checked the channel and confirmed that none of this post's media was uploaded. Any media that already reached Telegram may be sent again.")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Retry anyway", (d2, w2) -> resolveUncertainPost(path, url, false))
                                    .show())
                                .setNegativeButton("Later", null)
                                .show();
                        } catch (Exception error) { log("Could not open uncertain post: " + error.getMessage()); }
                    })
                    .setNegativeButton("Close", null)
                    .show());
            } catch (Throwable error) { log("Could not load uploads needing review: " + error.getMessage()); }
        });
    }

    private void resolveUncertainPost(String path, String url, boolean uploaded) {
        io.execute(() -> {
            try {
                bulkEngine.resolveReview(path, url, uploaded);
                log(uploaded ? "Uncertain post marked as uploaded: " + url : "Uncertain post returned to pending for a user-approved retry: " + url);
                refreshBatchListOnIo(path);
            } catch (Throwable error) { log("Could not resolve uncertain post: " + error.getMessage()); }
        });
    }

    private void showSuccessfulDatabasesForDeletion() {
        if (bulkUploadRunning || successfulDatabaseItems.isEmpty()) return;
        String[] labels = new String[successfulDatabaseItems.size()];
        for (int i=0;i<labels.length;i++) {
            BatchItem item = successfulDatabaseItems.get(i);
            labels[i] = item.name + " (" + item.done + "/" + item.total + " done)";
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Delete successful database")
            .setItems(labels, (dialog, which) -> {
                BatchItem item = successfulDatabaseItems.get(which);
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Delete database?")
                    .setMessage("This database is fully successful and locked. Delete it permanently?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (d, w) -> deleteDatabasePath(item.path))
                    .show();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void deleteDatabasePath(String path) {
        if (bulkUploadRunning || path == null || path.isEmpty()) return;
        io.execute(() -> {
            try {
                if (bulkEngine.deleteBatch(path)) {
                    log("Database deleted.");
                    if (path.equals(selectedDatabasePath)) selectedDatabasePath="";
                    refreshBatchListOnIo("");
                } else log("Database cannot be deleted until processing has finished.");
            } catch (Throwable error) { log("Database deletion failed: " + error.getMessage()); }
        });
    }

    private void deleteSelectedDatabase() {
        if (bulkUploadRunning || selectedDatabasePath.isEmpty()) return;
        final String path=selectedDatabasePath;
        deleteDatabasePath(path);
    }

    private void createBatchFromInput(String text, String name, boolean startAfterCreate) {
        if (text == null || text.trim().isEmpty()) { log("No Instagram links supplied; no database was created."); return; }
        if (bulkUploadRunning) { log("Cannot import a new batch while uploading is active."); return; }
        io.execute(() -> {
            try {
                if (bulkEngine == null) bulkEngine = new PythonBulkEngine(this);
                JSONObject created = new JSONObject(bulkEngine.createBatch(text, name));
                if (!created.optBoolean("created")) { log("No valid Instagram links found; no database was created."); return; }
                String path=created.getString("database"); selectedDatabasePath=path;
                log("Created a new SQLite database with " + created.optInt("received") + " unique link(s): " + created.optString("filename"));
                refreshBatchListOnIo(path);
                runOnUiThread(() -> {
                    linksInput.setText("");
                    if (startAfterCreate) beginBulkUpload(path);
                });
            } catch (Throwable error) { log("Could not create link database: " + error.getMessage()); }
        });
    }

    private void chooseLinksFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("text/plain");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 1002);
    }

    private void startLogin() { startTdlib(true); }

    private void startTdlib(boolean requestLogin) {
        try {
            pendingApiId = Integer.parseInt(apiId.getText().toString().trim());
            pendingApiHash = apiHash.getText().toString().trim();
            if (pendingApiId <= 0 || pendingApiHash.isEmpty()) {
                log("Enter API ID and API Hash first.");
                setAccountState("Not signed in");
                return;
            }
            if (requestLogin && phone.getText().toString().trim().isEmpty()) {
                log("Enter your phone number first.");
                return;
            }
            if (client != null) {
                if (requestLogin) {
                    loginRequested = true;
                    if (waitingForPhoneNumber) {
                        phoneSubmitted = false;
                        submitPhoneNumberIfNeeded();
                    } else {
                    }
                }
                return;
            }
            loginRequested = requestLogin;
            phoneSubmitted = false;
            waitingForPhoneNumber = false;
            waitingForEmailAddress = false;
            waitingForEmailCode = false;
            log(requestLogin ? "Connecting to Telegram..." : "Restoring saved Telegram session...");
            client = Client.create(this::onUpdate, null, null);
        } catch (Throwable e) {
            log("TDLib initialization failed: " + e.getMessage());
            setAccountState("Connection failed");
        }
    }

    private void onUpdate(TdApi.Object object) {
        if(object instanceof TdApi.UpdateAuthorizationState) {
            TdApi.AuthorizationState s=((TdApi.UpdateAuthorizationState)object).authorizationState;
            if(s instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
                TdApi.SetTdlibParameters p=new TdApi.SetTdlibParameters();
                p.databaseDirectory=new File(getFilesDir(),"tdlib").getAbsolutePath();
                p.filesDirectory=new File(getFilesDir(),"tdlib-files").getAbsolutePath();
                p.useFileDatabase=true; p.useChatInfoDatabase=true; p.useMessageDatabase=true;
                p.useSecretChats=false; p.apiId=pendingApiId; p.apiHash=pendingApiHash;
                p.systemLanguageCode="en"; p.deviceModel=android.os.Build.MODEL;
                p.systemVersion=android.os.Build.VERSION.RELEASE; p.applicationVersion="1.0";
                client.send(p,o->{
                    onResult(o);
                    if(o instanceof TdApi.Ok) {
                        client.send(new TdApi.GetAuthorizationState(),this::onResult);
                    }
                });
            } else if(s instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
                waitingForPhoneNumber = true;
                setAccountState("Not signed in");
                if(loginRequested && !phoneSubmitted) {
                    String number=normalizePhoneNumber(phone.getText().toString());
                    phoneSubmitted=true;
                    log("Requesting login code...");
                    client.send(new TdApi.SetAuthenticationPhoneNumber(number,null),this::onResult);
                }
            } else if(s instanceof TdApi.AuthorizationStateWaitCode) {
                waitingForPhoneNumber = false;
                waitingForEmailAddress = false;
                waitingForEmailCode = false;
                runOnUiThread(() -> {
                    emailAddressInput.setVisibility(View.GONE);
                    submitEmailAddressButton.setVisibility(View.GONE);
                    code.setHint("Telegram login code");
                    submitCodeButton.setText("Submit code");
                    code.setVisibility(View.VISIBLE);
                    submitCodeButton.setVisibility(View.VISIBLE);
                });
                TdApi.AuthenticationCodeInfo info = ((TdApi.AuthorizationStateWaitCode)s).codeInfo;
                showCodeInfo(info);
                codeInfoView.setVisibility(View.VISIBLE);
                resendButton.setVisibility(View.VISIBLE);
                setAccountState("Waiting for login code");
                log("Enter the code sent by Telegram.");
            } else if(s instanceof TdApi.AuthorizationStateWaitEmailAddress) {
                waitingForPhoneNumber = false;
                waitingForEmailAddress = true;
                waitingForEmailCode = false;
                setAccountState("Email address required");
                runOnUiThread(() -> {
                    emailAddressInput.setVisibility(View.VISIBLE);
                    submitEmailAddressButton.setVisibility(View.VISIBLE);
                    code.setVisibility(View.GONE);
                    submitCodeButton.setVisibility(View.GONE);
                    codeInfoView.setVisibility(View.VISIBLE);
                    codeInfoView.setText("Telegram requires an email address to continue. Enter the email address requested by Telegram.");
                });
                log("Telegram requested an email address. No email is sent until it is submitted.");
            } else if(s instanceof TdApi.AuthorizationStateWaitEmailCode) {
                waitingForPhoneNumber = false;
                waitingForEmailAddress = false;
                waitingForEmailCode = true;
                TdApi.EmailAddressAuthenticationCodeInfo info = ((TdApi.AuthorizationStateWaitEmailCode)s).codeInfo;
                setAccountState("Waiting for email code");
                runOnUiThread(() -> {
                    emailAddressInput.setVisibility(View.GONE);
                    submitEmailAddressButton.setVisibility(View.GONE);
                    code.setHint("Email verification code");
                    code.setVisibility(View.VISIBLE);
                    submitCodeButton.setText("Submit email code");
                    submitCodeButton.setVisibility(View.VISIBLE);
                    codeInfoView.setVisibility(View.VISIBLE);
                    codeInfoView.setText(info == null ? "Telegram is waiting for the email code." :
                        "Email code sent to: " + info.emailAddressPattern +
                        (info.length > 0 ? "\\nCode length: " + info.length : ""));
                });
                log("Telegram is waiting for the email authentication code" +
                    (info == null ? "." : " sent to " + info.emailAddressPattern + "."));
            } else if(s instanceof TdApi.AuthorizationStateWaitPassword) {
                setAccountState("Password required");
                log("This build does not yet include two-step password entry.");
            } else if(s instanceof TdApi.AuthorizationStateReady) {
                loginRequested = false;
                phoneSubmitted = true;
                isSignedIn = true;
                setAccountState("Signed in");
                runOnUiThread(() -> setLoginUiVisible(false));
                log("Authorization complete: account is signed in and ready to upload.");
            } else if(s instanceof TdApi.AuthorizationStateLoggingOut) {
                setAccountState("Signing out...");
            } else if(s instanceof TdApi.AuthorizationStateClosing) {
                setAccountState("Closing session...");
            } else if(s instanceof TdApi.AuthorizationStateClosed) {
                setAccountState("Session closed");
            } else {
                setAccountState(s.getClass().getSimpleName());
            }
        } else if(object instanceof TdApi.UpdateFile) {
            handleUploadFileUpdate(((TdApi.UpdateFile)object).file);
        } else if(object instanceof TdApi.UpdateMessageSendSucceeded) {
            TdApi.UpdateMessageSendSucceeded succeeded = (TdApi.UpdateMessageSendSucceeded)object;
            TdApi.Message message = succeeded.message;
            uploadInProgress = false;
            if (!bulkUploadRunning) stopUploadForegroundService();
            runOnUiThread(() -> {
                uploadProgress.setProgress(100);
                uploadProgressText.setText("Upload complete: 100%");
                uploadButton.setEnabled(!bulkUploadRunning);
            });
            log("UPLOAD SUCCESS. Telegram message ID: " + message.id);
            // TDLib replaces the temporary send-message ID with a final ID on success.
            // Upload tracking and the bulk awaiting set are keyed by the temporary ID.
            long completedTemporaryMessageId = succeeded.oldMessageId;
            markUploadMessageComplete(completedTemporaryMessageId);
            if (bulkUploadRunning) {
                synchronized (bulkAwaitingMessageIds) {
                    if (!bulkAwaitingMessageIds.remove(completedTemporaryMessageId))
                        bulkSucceededBeforeResponse.add(completedTemporaryMessageId);
                }
                completeBulkChunkIfReady();
            }
        } else if(object instanceof TdApi.UpdateMessageSendFailed) {
            TdApi.UpdateMessageSendFailed failed = (TdApi.UpdateMessageSendFailed)object;
            uploadInProgress = false;
            if (!bulkUploadRunning) clearUploadTracking();
            if (!bulkUploadRunning) stopUploadForegroundService();
            runOnUiThread(() -> {
                uploadProgress.setIndeterminate(false);
                uploadProgressText.setText("Upload failed: " + failed.error.message);
                uploadButton.setEnabled(!bulkUploadRunning);
            });
            log("UPLOAD FAILED. Error " + failed.error.code + ": " + failed.error.message);
            if (bulkUploadRunning) failCurrentBulkPost("TDLib upload failed: " + failed.error.message);
        }
    }
    private void trackUploadFile(File file) {
        if (file == null) return;
        UploadProgressTrack track = new UploadProgressTrack(file);
        try {
            synchronized (uploadTracksByPath) { uploadTracksByPath.put(file.getCanonicalPath(), track); }
        } catch (IOException ignored) {
            synchronized (uploadTracksByPath) { uploadTracksByPath.put(file.getAbsolutePath(), track); }
        }
    }

    private void armUploadStallTimeout(UploadProgressTrack track) {
        if (track == null || UPLOAD_STALL_TIMEOUT_MS <= 0L) return;
        synchronized (track) {
            if (track.finished || track.timeoutTriggered) return;
            if (track.timeoutRunnable != null) mainHandler.removeCallbacks(track.timeoutRunnable);
            track.timeoutRunnable = () -> handleUploadStallTimeout(track);
            mainHandler.postDelayed(track.timeoutRunnable, UPLOAD_STALL_TIMEOUT_MS);
        }
    }

    private void refreshUploadStallTimeouts(UploadProgressTrack progressedTrack) {
        ArrayList<UploadProgressTrack> tracks = new ArrayList<>();
        if (progressedTrack != null) tracks.add(progressedTrack);
        synchronized (uploadTracksByMessageId) {
            for (UploadProgressTrack track : uploadTracksByMessageId.values())
                if (!tracks.contains(track)) tracks.add(track);
        }
        for (UploadProgressTrack track : tracks) armUploadStallTimeout(track);
    }

    private void removeUploadTrack(UploadProgressTrack track) {
        if (track == null) return;
        synchronized (track) {
            track.finished = true;
            if (track.timeoutRunnable != null) mainHandler.removeCallbacks(track.timeoutRunnable);
            track.timeoutRunnable = null;
        }
        synchronized (uploadTracksById) { uploadTracksById.values().remove(track); }
        synchronized (uploadTracksByPath) { uploadTracksByPath.values().remove(track); }
        synchronized (uploadTracksByMessageId) { uploadTracksByMessageId.values().remove(track); }
    }

    private void clearUploadTracking() {
        ArrayList<UploadProgressTrack> tracks = new ArrayList<>();
        synchronized (uploadTracksByPath) {
            for (UploadProgressTrack track : uploadTracksByPath.values()) {
                if (!tracks.contains(track)) tracks.add(track);
            }
        }
        for (UploadProgressTrack track : tracks) removeUploadTrack(track);
        synchronized (uploadTracksById) { uploadTracksById.clear(); }
        synchronized (uploadTracksByPath) { uploadTracksByPath.clear(); }
        synchronized (uploadTracksByMessageId) { uploadTracksByMessageId.clear(); }
        synchronized (completedUploadMessageIds) { completedUploadMessageIds.clear(); }
    }

    private void handleUploadStallTimeout(UploadProgressTrack track) {
        synchronized (track) {
            if (track.finished || track.timeoutTriggered) return;
            track.timeoutTriggered = true;
            track.timeoutRunnable = null;
        }
        String reason = "No upload progress for " + (UPLOAD_STALL_TIMEOUT_MS / 1000L)
            + " seconds; file upload stalled: " + track.name;
        log("UPLOAD TIMEOUT. " + reason);
        if (bulkUploadRunning) {
            final String uncertainUrl = bulkCurrentUrl;
            final String uncertainDatabase = bulkDatabasePath;
            clearUploadTracking();
            synchronized (bulkAwaitingMessageIds) {
                bulkAwaitingMessageIds.clear();
                bulkSucceededBeforeResponse.clear();
                bulkChunkResponseReceived = false;
            }
            bulkChunkInFlight = false;
            io.execute(() -> {
                try {
                    bulkEngine.markReview(uncertainDatabase, uncertainUrl, reason + " Telegram may already have accepted some or all media. Check the channel before retrying.");
                    refreshBatchListOnIo(uncertainDatabase);
                    finishBulkUpload("Upload result is uncertain. No Telegram message was deleted and no automatic retry was started. Check the channel, then use Review uncertain uploads.");
                } catch (Throwable error) {
                    log("Could not save uncertain upload state: " + error.getMessage() + ". Automatic retry was intentionally skipped.");
                    finishBulkUpload("Upload result is uncertain. No message was deleted and no retry was started. Database status could not be saved; do not retry until you inspect the channel.");
                }
            });
        } else {
            clearUploadTracking();
            uploadInProgress = false;
            stopUploadForegroundService();
            runOnUiThread(() -> {
                uploadProgress.setIndeterminate(false);
                uploadProgressText.setText("Upload status uncertain: no progress for "
                    + (UPLOAD_STALL_TIMEOUT_MS / 1000L) + " seconds. Check Telegram before retrying.");
                uploadButton.setEnabled(true);
            });
        }
    }

    private int uploadFileIdForMessage(TdApi.Message message) {
        if (message == null || message.content == null) return 0;
        if (message.content instanceof TdApi.MessageVideo)
            return ((TdApi.MessageVideo) message.content).video.video.id;
        if (message.content instanceof TdApi.MessageDocument)
            return ((TdApi.MessageDocument) message.content).document.document.id;
        if (message.content instanceof TdApi.MessagePhoto) {
            TdApi.PhotoSize[] sizes = ((TdApi.MessagePhoto) message.content).photo.sizes;
            TdApi.File largest = null;
            for (TdApi.PhotoSize size : sizes)
                if (size.photo != null && (largest == null || size.photo.size > largest.size)) largest = size.photo;
            return largest == null ? 0 : largest.id;
        }
        return 0;
    }

    private void trackMessageUpload(TdApi.Message message, File localFile) {
        if (message == null || localFile == null) return;
        int fileId = uploadFileIdForMessage(message);
        if (fileId == 0) return;
        UploadProgressTrack track;
        try {
            synchronized (uploadTracksByPath) { track = uploadTracksByPath.get(localFile.getCanonicalPath()); }
        } catch (IOException ignored) {
            synchronized (uploadTracksByPath) { track = uploadTracksByPath.get(localFile.getAbsolutePath()); }
        }
        if (track != null) {
            track.messageId = message.id;
            track.chatId = message.chatId;
            synchronized (uploadTracksById) { uploadTracksById.put(fileId, track); }
            synchronized (uploadTracksByMessageId) { uploadTracksByMessageId.put(message.id, track); }
            boolean completed;
            synchronized (completedUploadMessageIds) { completed = completedUploadMessageIds.remove(message.id); }
            if (completed) finishUploadTrack(track);
            else armUploadStallTimeout(track);
        }
    }

    private void handleUploadFileUpdate(TdApi.File file) {
        if (file == null || file.remote == null) return;
        UploadProgressTrack track;
        synchronized (uploadTracksById) { track = uploadTracksById.get(file.id); }
        if (track == null && file.local != null && file.local.path != null) {
            synchronized (uploadTracksByPath) { track = uploadTracksByPath.get(file.local.path); }
        }
        if (track == null) return;
        long total = track.totalBytes > 1 ? track.totalBytes : Math.max(1L, file.size);
        long sent = Math.max(0L, file.remote.uploadedSize);
        boolean madeProgress;
        synchronized (track) {
            if (track.finished || track.timeoutTriggered) return;
            madeProgress = sent > track.lastUploadedBytes;
            if (madeProgress) track.lastUploadedBytes = sent;
        }
        if (madeProgress) refreshUploadStallTimeouts(track);
        int percent = (int) Math.min(100L, sent * 100L / total);
        int step = percent / 10;
        if (step > track.lastLoggedStep) {
            for (int completedStep = track.lastLoggedStep + 1; completedStep <= step; completedStep++)
                log("Upload [" + track.name + "]: " + (completedStep * 10) + "%");
            track.lastLoggedStep = step;
        }
        final String trackedFileName = track.name;
        runOnUiThread(() -> {
            uploadProgress.setVisibility(View.VISIBLE);
            uploadProgress.setIndeterminate(false);
            uploadProgress.setProgress(percent);
            uploadProgressText.setText("Uploading " + trackedFileName + ": " + percent + "%");
        });
    }

    private void markUploadMessageComplete(long messageId) {
        if (messageId == 0L) return;
        UploadProgressTrack track;
        synchronized (uploadTracksByMessageId) { track = uploadTracksByMessageId.remove(messageId); }
        if (track != null) finishUploadTrack(track);
        else synchronized (completedUploadMessageIds) { completedUploadMessageIds.add(messageId); }
    }

    private void finishUploadTrack(UploadProgressTrack track) {
        if (track == null) return;
        removeUploadTrack(track);
        if (track.lastLoggedStep < 10) {
            for (int completedStep = track.lastLoggedStep + 1; completedStep <= 10; completedStep++)
                log("Upload [" + track.name + "]: " + (completedStep * 10) + "%");
            track.lastLoggedStep = 10;
        }
    }

    private void onResult(TdApi.Object o) {
        if (o == null) return;
        if(o instanceof TdApi.Error) {
            phoneSubmitted=false;
            loginRequested=false;
            TdApi.Error error = (TdApi.Error)o;
            log("TDLib error: " + error.message);
        } else if(o instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            waitingForPhoneNumber = true;
            setAccountState("Not signed in");
            if(loginRequested) submitPhoneNumberIfNeeded();
        } else if(o instanceof TdApi.AuthorizationStateWaitCode) {
            waitingForPhoneNumber = false;
            waitingForEmailAddress = false;
            waitingForEmailCode = false;
            showCodeInfo(((TdApi.AuthorizationStateWaitCode)o).codeInfo);
            setAccountState("Waiting for login code");
            runOnUiThread(() -> {
                code.setVisibility(View.VISIBLE);
                submitCodeButton.setVisibility(View.VISIBLE);
                codeInfoView.setVisibility(View.VISIBLE);
                resendButton.setVisibility(View.VISIBLE);
            });
        } else if(o instanceof TdApi.AuthorizationStateWaitEmailAddress) {
            waitingForPhoneNumber = false;
            waitingForEmailAddress = true;
            waitingForEmailCode = false;
            setAccountState("Email address required");
            runOnUiThread(() -> {
                emailAddressInput.setVisibility(View.VISIBLE);
                submitEmailAddressButton.setVisibility(View.VISIBLE);
                codeInfoView.setVisibility(View.VISIBLE);
                codeInfoView.setText("Telegram requires an email address to continue.");
            });
            log("TDLib now requires an email address.");
        } else if(o instanceof TdApi.AuthorizationStateWaitEmailCode) {
            waitingForPhoneNumber = false;
            waitingForEmailAddress = false;
            waitingForEmailCode = true;
            TdApi.EmailAddressAuthenticationCodeInfo info = ((TdApi.AuthorizationStateWaitEmailCode)o).codeInfo;
            setAccountState("Waiting for email code");
            runOnUiThread(() -> {
                emailAddressInput.setVisibility(View.GONE);
                submitEmailAddressButton.setVisibility(View.GONE);
                code.setHint("Email verification code");
                code.setVisibility(View.VISIBLE);
                submitCodeButton.setText("Submit email code");
                submitCodeButton.setVisibility(View.VISIBLE);
                codeInfoView.setVisibility(View.VISIBLE);
                codeInfoView.setText(info == null ? "Telegram is waiting for the email code." :
                    "Email code sent to: " + info.emailAddressPattern +
                    (info.length > 0 ? "\\nCode length: " + info.length : ""));
            });
            log("TDLib is waiting for the email code" + (info == null ? "." : " sent to " + info.emailAddressPattern + "."));
        } else if(o instanceof TdApi.AuthorizationStateReady) {
            isSignedIn = true;
            loginRequested = false;
            phoneSubmitted = true;
            setAccountState("Signed in");
            runOnUiThread(() -> setLoginUiVisible(false));
            log("Authorization complete: account is signed in and ready to upload.");
        } else if(o instanceof TdApi.AuthorizationStateWaitPassword) {
            setAccountState("Password required");
            log("Two-step verification password is required, but this build has no password field yet.");
        } else if(o instanceof TdApi.Ok && resendButton != null) {
            log("Request accepted. Waiting for Telegram...");
        }
    }

    private String describeCodeType(TdApi.AuthenticationCodeType type) {
        if(type == null) return "Not available";
        String name = type.getClass().getSimpleName();
        if(name.contains("TelegramMessage")) return "Telegram message (official Telegram chat on another active session)";
        if(name.contains("SmsPhrase")) return "SMS phrase";
        if(name.contains("SmsWord")) return "SMS word";
        if(name.contains("Sms")) return "SMS";
        if(name.contains("FlashCall")) return "Flash call (missed call)";
        if(name.contains("MissedCall")) return "Missed call";
        if(name.contains("Call")) return "Voice call";
        if(name.contains("Fragment")) return "Fragment";
        if(name.contains("Firebase")) return "Firebase / device verification";
        return name;
    }

    private void showCodeInfo(TdApi.AuthenticationCodeInfo info) {
        if(info == null) {
            runOnUiThread(() -> codeInfoView.setText("TDLib did not provide code delivery details."));
            return;
        }
        resendSecondsRemaining = Math.max(0, info.timeout);
        hasNextCodeMethod = info.nextType != null;
        String current = describeCodeType(info.type);
        String next = describeCodeType(info.nextType);
        runOnUiThread(() -> {
            String details = "Phone: " + info.phoneNumber
                + "\nCurrent delivery method: " + current
                + "\nNext method: " + (info.nextType == null ? "Not offered by Telegram" : next)
                + "\nWait before next request: " + info.timeout + " seconds";
            codeInfoView.setText(details);
            updateResendButton();
        });
        startResendCountdown();
    }

    private void startResendCountdown() {
        if(resendCountdown != null) mainHandler.removeCallbacks(resendCountdown);
        resendCountdown = new Runnable() {
            @Override public void run() {
                updateResendButton();
                if(resendSecondsRemaining > 0) {
                    resendSecondsRemaining--;
                    mainHandler.postDelayed(this, 1000);
                }
            }
        };
        mainHandler.post(resendCountdown);
    }

    private void updateResendButton() {
        if(resendButton == null) return;
        // The button is enabled only when Telegram explicitly offers a next method
        // and its server-provided timeout has elapsed.
        resendButton.setEnabled(hasNextCodeMethod && resendSecondsRemaining <= 0);
        if(!hasNextCodeMethod) resendButton.setText("Telegram did not offer another code method");
        else if(resendSecondsRemaining > 0) resendButton.setText("Request next method in " + resendSecondsRemaining + "s");
        else resendButton.setText("Request next code method");
    }

    private void requestNextCodeMethod() {
        if(client == null || resendSecondsRemaining > 0) return;
        if(!resendButton.isEnabled()) {
            log("Telegram has not offered another code delivery method.");
            return;
        }
        resendButton.setEnabled(false);
        log("Requesting another login code...");
        client.send(new TdApi.ResendAuthenticationCode(), this::onResult);
    }

    private String normalizePhoneNumber(String raw) {
        String number = raw == null ? "" : raw.trim().replaceAll("[\\s\\-()]", "");
        if (number.startsWith("00")) number = "+" + number.substring(2);
        else if (!number.startsWith("+")) number = "+" + number;
        return number;
    }

    private void submitPhoneNumberIfNeeded() {
        if(client==null || phoneSubmitted) return;
        String number=normalizePhoneNumber(phone.getText().toString());
        phoneSubmitted=true;
        log("Requesting login code...");
        client.send(new TdApi.SetAuthenticationPhoneNumber(number,null),this::onResult);
    }
    private void submitEmailAddress() {
        String address = emailAddressInput.getText().toString().trim();
        if (client == null || !waitingForEmailAddress) {
            log("Email address cannot be submitted because TDLib is not waiting for one.");
            return;
        }
        if (address.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
            log("Enter a valid email address.");
            return;
        }
        log("Submitting email address to TDLib...");
        client.send(new TdApi.SetAuthenticationEmailAddress(address), this::onResult);
    }

    private void submitCode() {
        String c=code.getText().toString().trim();
        if (client == null || c.isEmpty()) {
            log("Enter the authentication code first.");
            return;
        }
        if (waitingForEmailCode) {
            log("Submitting email authentication code to TDLib...");
            client.send(new TdApi.CheckAuthenticationEmailCode(new TdApi.EmailAddressAuthenticationCode(c)), this::onResult);
        } else {
            log("Submitting Telegram authentication code to TDLib...");
            client.send(new TdApi.CheckAuthenticationCode(c),this::onResult);
        }
    }
    private void chooseFile() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,1001);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==1002 && resultCode==RESULT_OK && data!=null) {
            Uri uri = data.getData();
            io.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    String text = out.toString("UTF-8");
                    String name = "TXT import " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date());
                    log("Received links TXT file (" + text.split("\\r?\\n").length + " lines). Creating a separate database.");
                    createBatchFromInput(text, name, false);
                } catch (Exception error) { log("Could not read links file: " + error.getMessage()); }
            });
        } else if(requestCode==1001 && resultCode==RESULT_OK && data!=null) {
            Uri uri=data.getData();
            io.execute(()->{
                try {
                    String name="upload_"+System.currentTimeMillis();
                    try(Cursor c=getContentResolver().query(uri,null,null,null,null)) {
                        if(c!=null && c.moveToFirst()) { int ix=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(ix>=0) name=c.getString(ix); }
                    }
                    File out=new File(getCacheDir(),name);
                    try(InputStream in=getContentResolver().openInputStream(uri); OutputStream os=new FileOutputStream(out)) {
                        byte[] b=new byte[64*1024]; int n; while((n=in.read(b))>0) os.write(b,0,n);
                    }
                    chosenFile=out;
                    chosenFileMimeType = getContentResolver().getType(uri);
                    runOnUiThread(()->{
                        selected.setText(out.getName()+" ("+formatBytes(out.length())+")");
                        log("Local file selected. Starting upload automatically.");
                        upload();
                    });
                } catch(Exception e) { log("File copy failed: "+e); }
            });
        }
    }
    private void upload() {
        log("Upload button pressed.");
        if(client == null) { log("UPLOAD BLOCKED: TDLib client is not initialized."); return; }
        if(!isSignedIn) { log("UPLOAD BLOCKED: account is not signed in yet."); return; }
        if(chosenFile == null || !chosenFile.exists()) { log("UPLOAD BLOCKED: choose a file first."); return; }
        if(uploadInProgress) { log("An upload is already in progress."); return; }
        String username = channel.getText().toString().trim().replace("@", "");
        if(username.isEmpty()) { log("UPLOAD BLOCKED: enter a public channel username."); return; }

        uploadInProgress = true;
        startUploadForegroundService();
        uploadFileId = 0;
        lastLoggedProgressStep = -1;
        uploadTotalBytes = chosenFile.length();
        runOnUiThread(() -> {
            uploadButton.setEnabled(false);
            uploadProgress.setVisibility(View.VISIBLE);
            uploadProgress.setIndeterminate(true);
            uploadProgress.setProgress(0);
            uploadProgressText.setText("Preparing upload...");
        });
        trackUploadFile(chosenFile);
        log("Starting upload workflow. File: " + chosenFile.getName() + ", size: " + formatBytes(uploadTotalBytes));
        log("Resolving public channel @" + username + "...");
        client.send(new TdApi.SearchPublicChat(username), result -> {
            if(result instanceof TdApi.Error) {
                TdApi.Error error = (TdApi.Error)result;
                uploadInProgress = false;
                stopUploadForegroundService();
                runOnUiThread(() -> {
                    uploadButton.setEnabled(true);
                    uploadProgress.setIndeterminate(false);
                    uploadProgressText.setText("Channel lookup failed.");
                });
                log("CHANNEL LOOKUP FAILED. Error " + error.code + ": " + error.message);
                return;
            }
            if(!(result instanceof TdApi.Chat)) {
                uploadInProgress = false;
                stopUploadForegroundService();
                runOnUiThread(() -> uploadButton.setEnabled(true));
                log("CHANNEL LOOKUP FAILED: unexpected TDLib response " + result.getClass().getSimpleName());
                return;
            }
            TdApi.Chat chat = (TdApi.Chat)result;
            log("Channel resolved: " + chat.title + " (ID " + chat.id + ").");
            log("Preparing Telegram media message...");
            TdApi.InputMessageContent content;
            if (isVideoFile(chosenFile.getName(), chosenFileMimeType)) {
                int duration = 0, width = 0, height = 0;
                android.media.MediaMetadataRetriever metadata = new android.media.MediaMetadataRetriever();
                try {
                    metadata.setDataSource(chosenFile.getAbsolutePath());
                    String d = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                    String w = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if (d != null) duration = Math.max(0, (int)(Long.parseLong(d) / 1000L));
                    if (w != null) width = Math.max(0, Integer.parseInt(w));
                    if (h != null) height = Math.max(0, Integer.parseInt(h));
                } catch (Exception metadataError) {
                    log("Video metadata unavailable; Telegram will inspect the file.");
                } finally {
                    try { metadata.release(); } catch (Exception ignored) {}
                }
                TdApi.InputVideo inputVideo = new TdApi.InputVideo();
                inputVideo.video = new TdApi.InputFileLocal(chosenFile.getAbsolutePath());
                inputVideo.thumbnail = null;
                inputVideo.cover = null;
                inputVideo.startTimestamp = 0;
                inputVideo.addedStickerFileIds = new int[0];
                inputVideo.duration = duration;
                inputVideo.width = width;
                inputVideo.height = height;
                inputVideo.supportsStreaming = true;

                TdApi.InputMessageVideo videoMessage = new TdApi.InputMessageVideo();
                videoMessage.video = inputVideo;
                String captionText = captionInput.getText().toString().trim();
                videoMessage.caption = captionText.isEmpty() ? null : new TdApi.FormattedText(captionText, new TdApi.TextEntity[0]);
                content = videoMessage;
                log("Sending as Telegram video (" + duration + "s, " + width + "x" + height + "). Caption: " + (captionText.isEmpty() ? "none" : "custom text"));
            } else if (isImageFile(chosenFile.getName(), chosenFileMimeType)) {
                TdApi.InputPhoto inputPhoto = new TdApi.InputPhoto(
                    new TdApi.InputFileLocal(chosenFile.getAbsolutePath()), null, null, new int[0], 0, 0);
                TdApi.InputMessagePhoto photoMessage = new TdApi.InputMessagePhoto();
                photoMessage.photo = inputPhoto;
                String captionText = captionInput.getText().toString().trim();
                photoMessage.caption = captionText.isEmpty() ? null : new TdApi.FormattedText(captionText, new TdApi.TextEntity[0]);
                content = photoMessage;
                log("Sending selected image as Telegram photo. Caption: " + (captionText.isEmpty() ? "none" : "custom text"));
            } else {
                TdApi.InputDocument document = new TdApi.InputDocument(
                    new TdApi.InputFileLocal(chosenFile.getAbsolutePath()), null, false);
                content = new TdApi.InputMessageDocument(document, null);
                log("Selected file is neither a recognized video nor image; sending as document.");
            }
            client.send(new TdApi.SendMessage(chat.id, null, null, null, null, content), response -> {
                if(response instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error)response;
                    uploadInProgress = false;
                    stopUploadForegroundService();
                    runOnUiThread(() -> {
                        uploadButton.setEnabled(true);
                        uploadProgress.setIndeterminate(false);
                        uploadProgressText.setText("Send request failed.");
                    });
                    clearUploadTracking();
                    log("SEND MESSAGE FAILED. Error " + error.code + ": " + error.message);
                    return;
                }
                if(response instanceof TdApi.Message) {
                    TdApi.Message message = (TdApi.Message)response;
                    trackMessageUpload(message, chosenFile);
                    if(message.content instanceof TdApi.MessageVideo) {
                        uploadFileId = ((TdApi.MessageVideo)message.content).video.video.id;
                        log("Telegram accepted the video. Tracking upload file ID " + uploadFileId + ".");
                    } else if(message.content instanceof TdApi.MessagePhoto) {
                        uploadFileId = uploadFileIdForMessage(message);
                        log("Telegram accepted the photo. Tracking upload file ID " + uploadFileId + ".");
                    } else if(message.content instanceof TdApi.MessageDocument) {
                        uploadFileId = ((TdApi.MessageDocument)message.content).document.document.id;
                        log("Telegram accepted the document. Tracking upload file ID " + uploadFileId + ".");
                    } else {
                        log("Telegram accepted the send request; response content: " + message.content.getClass().getSimpleName());
                    }
                } else {
                    log("Send request response: " + response.getClass().getSimpleName());
                }
            });
        });
    }


    private void startBulkUpload() {
        if (bulkUploadRunning || uploadInProgress) { log("An upload is already in progress."); return; }
        String pasted = linksInput.getText().toString();
        if (!pasted.trim().isEmpty()) {
            boolean canStartNow = client != null && isSignedIn;
            createBatchFromInput(pasted, "Pasted links " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date()), canStartNow);
            if (!canStartNow) log("The links were queued in a new database. Sign in to Telegram, then select this database and press Start uploading.");
            return;
        }
        if (client == null || !isSignedIn) { log("START BLOCKED: sign in to Telegram first. The selected database has been kept."); return; }
        String username = channel.getText().toString().trim().replace("@", "");
        if (username.isEmpty()) { log("START BLOCKED: enter a public channel username."); return; }
        if (selectedDatabasePath.isEmpty()) { log("START BLOCKED: import a TXT file or paste links to create/select a database."); return; }
        BatchItem item=null;
        for (BatchItem candidate:databaseItems) if(candidate.path.equals(selectedDatabasePath)) item=candidate;
        if (item == null) { log("Selected database is no longer available."); return; }
        if (item.complete) { log("This database is fully successful and locked."); return; }
        beginBulkUpload(selectedDatabasePath);
    }

    private void beginBulkUpload(String databasePath) {
        if (bulkUploadRunning || uploadInProgress) return;
        if (client == null || !isSignedIn) { log("START BLOCKED: sign in to Telegram first."); return; }
        String username = channel.getText().toString().trim().replace("@", "");
        if (username.isEmpty()) { log("START BLOCKED: enter a public channel username."); return; }
        bulkDatabasePath = databasePath; bulkCaptionOverride = captionInput.getText().toString().trim();
        bulkUploadRunning = true; bulkStopRequested = false; bulkFailureHandling=false;
        bulkPostLoadInProgress=false; bulkDownloadInProgress=false; bulkChunkInFlight=false;
        bulkCurrentUrl=""; bulkCurrentFiles=null; bulkCurrentMedia=null; bulkChunkPlan=null; bulkChunkIndex=0;
        startUploadForegroundService();
        runOnUiThread(() -> {
            uploadButton.setEnabled(false); stopButton.setEnabled(true); stopButton.setText("Stop");
            databaseSpinner.setEnabled(false); deleteDatabaseButton.setEnabled(false);
            uploadProgress.setVisibility(View.VISIBLE); uploadProgress.setIndeterminate(true);
            uploadProgressText.setText("Preparing selected database...");
        });
        io.execute(() -> {
            try {
                bulkEngine.initialize(bulkDatabasePath);
                refreshBatchListOnIo(bulkDatabasePath);
                client.send(new TdApi.SearchPublicChat(username), response -> {
                    if (!bulkUploadRunning) return;
                    if (response instanceof TdApi.Error) { finishBulkUpload("Channel lookup failed: " + ((TdApi.Error) response).message); return; }
                    if (!(response instanceof TdApi.Chat)) { finishBulkUpload("Unexpected response while resolving the channel."); return; }
                    TdApi.Chat chat=(TdApi.Chat)response; bulkChatId=chat.id;
                    log("Bulk destination: " + chat.title + " (ID " + chat.id + ")."); loadNextBulkPost();
                });
            } catch(Throwable error) { finishBulkUpload("Could not initialize selected database: " + error.getMessage()); }
        });
    }

    private void loadNextBulkPost() {
        if (!bulkUploadRunning) return;
        if (bulkStopRequested) { finishBulkUpload("Stopped by user. Select the same database and press Start uploading to resume."); return; }
        if (bulkPostLoadInProgress) return;
        bulkPostLoadInProgress = true;
        io.execute(() -> {
            try {
                String url = bulkEngine.nextPending(bulkDatabasePath);
                if (bulkStopRequested) {
                    bulkPostLoadInProgress = false;
                    finishBulkUpload("Stopped by user. Select the same database and press Start uploading to resume.");
                    return;
                }
                if (url == null) {
                    bulkPostLoadInProgress = false;
                    String counts = bulkEngine.counts(bulkDatabasePath);
                    refreshBatchListOnIo(bulkDatabasePath);
                    finishBulkUpload("Selected database processing finished. " + counts);
                    return;
                }
                bulkCurrentUrl = url;
                bulkEngine.markProcessing(bulkDatabasePath, url);
                refreshBatchListOnIo(bulkDatabasePath);
                log("Processing Instagram post: " + url);
                bulkDownloadInProgress = true;
                bulkPostLoadInProgress = false;
                JSONObject data = new JSONObject(bulkEngine.downloadPost(url, (fileName, percent) ->
                    log("Download [" + fileName + "]: " + percent + "%")));
                bulkDownloadInProgress = false;
                if (bulkStopRequested) { stopCurrentPostAndFinish(); return; }
                bulkCurrentFiles = data.getJSONArray("files");
                bulkCurrentMedia = data.getJSONArray("media");
                if (bulkCurrentFiles.length() == 0 || bulkCurrentFiles.length() != bulkCurrentMedia.length())
                    throw new IllegalStateException("Downloaded file list does not match extracted media metadata.");
                String postDescription = bulkCaptionOverride.isEmpty()
                    ? data.optString("caption", "").trim()
                    : bulkCaptionOverride;
                bulkCurrentCaption = buildInstagramCaption(url, postDescription);
                bulkChunkPlan = new JSONArray(bulkEngine.planChunks(bulkCurrentMedia.toString(), bulkEngine.sentChunks(bulkDatabasePath, url)));
                bulkChunkIndex = 0;
                log("Downloaded " + bulkCurrentFiles.length() + " media item(s). Caption: " +
                    (bulkCurrentCaption.isEmpty() ? "none" : "extracted/custom") + ".");
                sendNextBulkChunk();
            } catch (Throwable error) {
                bulkPostLoadInProgress = false;
                bulkDownloadInProgress = false;
                failCurrentBulkPost("Download/extraction failed: " + error.getMessage());
            }
        });
    }

    private void sendNextBulkChunk() {
        if (!bulkUploadRunning) return;
        if (bulkStopRequested && bulkChunkIndex < bulkChunkPlan.length()) {
            io.execute(() -> stopCurrentPostAndFinish());
            return;
        }
        if (bulkChunkIndex >= bulkChunkPlan.length()) {
            final String completedUrl = bulkCurrentUrl;
            io.execute(() -> {
                try {
                    log("All media for this link uploaded. Marking done after a short delay: " + completedUrl);
                    mainHandler.postDelayed(() -> io.execute(() -> {
                        try {
                            if (!bulkUploadRunning) return;
                            bulkEngine.markDone(bulkDatabasePath, completedUrl);
                            bulkEngine.cleanup(completedUrl);
                            refreshBatchListOnIo(bulkDatabasePath);
                            log("Post status changed to done: " + completedUrl);
                            loadNextBulkPost();
                        } catch (Throwable error) { failCurrentBulkPost("Could not finalize post: " + error.getMessage()); }
                    }), 3000);
                } catch (Throwable error) { failCurrentBulkPost("Could not finalize post: " + error.getMessage()); }
            });
            return;
        }
        try {
            JSONObject chunk = bulkChunkPlan.getJSONObject(bulkChunkIndex);
            int chunkNumber = chunk.getInt("number");
            String signature = chunk.getString("signature");
            JSONArray items = chunk.getJSONArray("media");
            TdApi.InputMessageContent[] contents = new TdApi.InputMessageContent[items.length()];
            long totalBytes = 0;
            for (int i = 0; i < items.length(); i++) {
                int fileIndex = chunkNumber * 10 + i;
                if (fileIndex >= bulkCurrentFiles.length()) throw new IllegalStateException("Chunk references a missing downloaded file.");
                File mediaFile = new File(bulkCurrentFiles.optString(fileIndex));
                if (!mediaFile.isFile()) throw new FileNotFoundException(mediaFile.getAbsolutePath());
                totalBytes += mediaFile.length();
                trackUploadFile(mediaFile);
                String caption = (chunkNumber == 0 && i == 0) ? bulkCurrentCaption : "";
                contents[i] = buildBulkContent(mediaFile, items.getJSONObject(i), caption);
            }
            uploadTotalBytes = Math.max(1L, totalBytes);
            uploadFileId = 0;
            lastLoggedProgressStep = -1;
            runOnUiThread(() -> {
                uploadProgress.setIndeterminate(true);
                uploadProgress.setProgress(0);
                uploadProgressText.setText("Uploading post media " + (bulkChunkIndex + 1) + "/" + bulkChunkPlan.length() + "...");
            });
            log("Sending media batch " + (chunkNumber + 1) + " (" + contents.length + " item(s)) via TDLib.");
            bulkCurrentChunkNumber = chunkNumber;
            bulkCurrentChunkSignature = signature;
            bulkChunkResponseReceived = false;
            bulkAwaitingMessageIds.clear();
            bulkSucceededBeforeResponse.clear();
            TdApi.Function<?> request = contents.length == 1
                ? new TdApi.SendMessage(bulkChatId, null, null, null, null, contents[0])
                : new TdApi.SendMessageAlbum(bulkChatId, null, null, null, contents);
            bulkChunkInFlight = true;
            client.send(request, response -> {
                if (response instanceof TdApi.Error) {
                    bulkChunkInFlight = false;
                    failCurrentBulkPost("TDLib rejected media batch: " + ((TdApi.Error) response).message);
                    return;
                }
                synchronized (bulkAwaitingMessageIds) {
                    if (response instanceof TdApi.Message) {
                        TdApi.Message sent = (TdApi.Message) response;
                        long id = sent.id;
                        trackMessageUpload(sent, new File(bulkCurrentFiles.optString(chunkNumber * 10)));
                        if (!bulkSucceededBeforeResponse.remove(id)) bulkAwaitingMessageIds.add(id);
                    } else if (response instanceof TdApi.Messages) {
                        TdApi.Message[] sentMessages = ((TdApi.Messages) response).messages;
                        for (int messageIndex = 0; messageIndex < sentMessages.length; messageIndex++) {
                            TdApi.Message sent = sentMessages[messageIndex];
                            int fileIndex = chunkNumber * 10 + messageIndex;
                            if (fileIndex < bulkCurrentFiles.length()) trackMessageUpload(sent, new File(bulkCurrentFiles.optString(fileIndex)));
                            if (!bulkSucceededBeforeResponse.remove(sent.id)) bulkAwaitingMessageIds.add(sent.id);
                        }
                    } else {
                        failCurrentBulkPost("Unexpected TDLib send response: " + response.getClass().getSimpleName());
                        return;
                    }
                    bulkChunkResponseReceived = true;
                }
                completeBulkChunkIfReady();
            });
        } catch (Throwable error) { failCurrentBulkPost("Could not prepare media batch: " + error.getMessage()); }
    }

    private void completeBulkChunkIfReady() {
        boolean ready;
        synchronized (bulkAwaitingMessageIds) {
            ready = bulkChunkResponseReceived && bulkAwaitingMessageIds.isEmpty();
        }
        if (!ready || !bulkUploadRunning) return;
        final String url = bulkCurrentUrl;
        final int number = bulkCurrentChunkNumber;
        final String signature = bulkCurrentChunkSignature;
        io.execute(() -> {
            try {
                bulkEngine.markChunkSent(bulkDatabasePath, url, number, signature);
                bulkChunkInFlight = false;
                bulkChunkIndex++;
                if (bulkStopRequested && bulkChunkIndex < bulkChunkPlan.length()) {
                    stopCurrentPostAndFinish();
                } else {
                    sendNextBulkChunk();
                }
            } catch (Throwable error) {
                failCurrentBulkPost("Could not save completed media batch: " + error.getMessage());
            }
        });
    }

    private String buildInstagramCaption(String postUrl, String description) {
        String link = postUrl == null ? "" : postUrl.trim();
        String text = description == null ? "" : description.trim();
        if (link.isEmpty()) return text;
        return text.isEmpty() ? link : link + "\n" + text;
    }

    private TdApi.InputMessageContent buildBulkContent(File file, JSONObject meta, String captionText) throws Exception {
        TdApi.FormattedText caption = captionText == null || captionText.isEmpty()
            ? null : new TdApi.FormattedText(captionText, new TdApi.TextEntity[0]);
        int width = meta.optInt("width", 0);
        int height = meta.optInt("height", 0);
        if (meta.optBoolean("is_video", false) || isVideoFile(file.getName(), null)) {
            TdApi.InputVideo video = new TdApi.InputVideo();
            video.video = new TdApi.InputFileLocal(file.getAbsolutePath());
            video.thumbnail = null;
            video.cover = null;
            video.startTimestamp = 0;
            video.addedStickerFileIds = new int[0];
            video.duration = meta.optInt("duration", 0);
            video.width = width;
            video.height = height;
            video.supportsStreaming = true;
            TdApi.InputMessageVideo message = new TdApi.InputMessageVideo();
            message.video = video;
            message.caption = caption;
            return message;
        }
        // Instagram extraction only yields photos and videos. Every non-video item
        // must therefore be sent as a Telegram photo, never downgraded to document
        // merely because its extension is unfamiliar (for example AVIF).
        TdApi.InputMessagePhoto message = new TdApi.InputMessagePhoto();
        message.photo = new TdApi.InputPhoto(
            new TdApi.InputFileLocal(file.getAbsolutePath()),
            null,
            null,
            new int[0],
            width,
            height
        );
        message.caption = caption;
        return message;
    }

    private void failCurrentBulkPost(String reason) {
        if (!bulkUploadRunning || bulkFailureHandling) return;
        bulkFailureHandling = true; bulkChunkInFlight = false; bulkDownloadInProgress = false;
        final String failedUrl = bulkCurrentUrl;
        synchronized (bulkAwaitingMessageIds) {
            bulkAwaitingMessageIds.clear(); bulkSucceededBeforeResponse.clear(); bulkChunkResponseReceived = false;
        }
        clearUploadTracking();
        io.execute(() -> {
            try {
                JSONObject retry = new JSONObject(bulkEngine.retryOrFail(bulkDatabasePath, failedUrl, reason));
                int attempts = retry.optInt("attempts", 3);
                String state = retry.optString("status", "failed");
                refreshBatchListOnIo(bulkDatabasePath);
                log(reason + " URL: " + failedUrl + " (attempt " + attempts + "/4).");
                if ("pending".equals(state)) {
                    if (bulkStopRequested) {
                        bulkFailureHandling = false;
                        finishBulkUpload("Stopped by user. Select the same database and press Start uploading to resume.");
                        return;
                    }
                    long delay = attempts == 1 ? 5000L : (attempts == 2 ? 15000L : 30000L);
                    log("Retry scheduled in " + (delay/1000) + " seconds.");
                    bulkRetryRunnable = () -> {
                        bulkRetryRunnable = null;
                        bulkFailureHandling = false;
                        if (bulkUploadRunning) loadNextBulkPost();
                    };
                    mainHandler.postDelayed(bulkRetryRunnable, delay);
                } else {
                    log("Link marked failed after the initial attempt and 3 retries: " + failedUrl);
                    bulkFailureHandling = false;
                    if (bulkStopRequested) finishBulkUpload("Stopped by user. Select the same database and press Start uploading to resume.");
                    else loadNextBulkPost();
                }
            } catch (Throwable error) {
                bulkFailureHandling = false; log("Could not save retry status: " + error.getMessage());
                if (bulkStopRequested) finishBulkUpload("Stopped by user. Select the same database and press Start uploading to resume.");
                else loadNextBulkPost();
            }
        });
    }


    private void requestBulkStop() {
        if (!bulkUploadRunning || bulkStopRequested) return;
        bulkStopRequested = true;
        stopButton.setEnabled(false);
        stopButton.setText("Stopping...");
        log("Manual stop requested. Waiting for the current safe checkpoint...");
        if (bulkRetryRunnable != null) {
            mainHandler.removeCallbacks(bulkRetryRunnable);
            bulkRetryRunnable = null;
            bulkFailureHandling = false;
            finishBulkUpload("Stopped by user. Select the same database and press Start uploading to resume.");
            return;
        }
        if (!bulkDownloadInProgress && !bulkChunkInFlight && !bulkPostLoadInProgress && !bulkFailureHandling) {
            io.execute(() -> stopCurrentPostAndFinish());
        }
    }

    /** Called on the IO executor after the current download or confirmed media chunk is safe. */
    private void stopCurrentPostAndFinish() {
        try {
            if (bulkEngine != null && bulkCurrentUrl != null && !bulkCurrentUrl.isEmpty()) {
                bulkEngine.releaseProcessing(bulkDatabasePath, bulkCurrentUrl);
                log("Current link returned to pending without consuming a retry: " + bulkCurrentUrl);
            }
            refreshBatchListOnIo(bulkDatabasePath);
        } catch (Throwable error) {
            log("Could not checkpoint the stopped link: " + error.getMessage());
        }
        finishBulkUpload("Stopped by user. Select the same database and press Start uploading to resume.");
    }

    private void startUploadForegroundService() {
        try {
            Intent intent = new Intent(this, UploadForegroundService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Throwable error) {
            log("Could not start background upload service: " + error.getMessage());
        }
    }

    private void stopUploadForegroundService() {
        try { stopService(new Intent(this, UploadForegroundService.class)); }
        catch (Throwable ignored) {}
    }

    private void finishBulkUpload(String message) {
        bulkUploadRunning = false;
        bulkPostLoadInProgress = false;
        bulkDownloadInProgress = false;
        bulkChunkInFlight = false;
        stopUploadForegroundService();
        if (bulkRetryRunnable != null) { mainHandler.removeCallbacks(bulkRetryRunnable); bulkRetryRunnable=null; }
        refreshBatchListOnIo(bulkDatabasePath);
        runOnUiThread(() -> {
            uploadButton.setEnabled(!isSelectedDatabaseComplete());
            stopButton.setEnabled(false); stopButton.setText("Stop");
            databaseSpinner.setEnabled(true); deleteDatabaseButton.setEnabled(true); deleteCompletedDatabasesButton.setEnabled(!successfulDatabaseItems.isEmpty());
            uploadProgress.setIndeterminate(false); uploadProgressText.setText(message);
        });
        log(message);
    }

    private boolean isVideoFile(String name) {
        return isVideoFile(name, null);
    }

    private boolean isVideoFile(String name, String mimeType) {
        if (mimeType != null && mimeType.toLowerCase(java.util.Locale.US).startsWith("video/")) return true;
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")
            || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".avi")
            || lower.endsWith(".3gp") || lower.endsWith(".mpeg") || lower.endsWith(".mpg")
            || lower.endsWith(".ts") || lower.endsWith(".mts") || lower.endsWith(".m2ts")
            || lower.endsWith(".m4v") || lower.endsWith(".ogv") || lower.endsWith(".vob")
            || lower.endsWith(".flv") || lower.endsWith(".wmv");
    }

    private boolean isImageFile(String name, String mimeType) {
        if (mimeType != null && mimeType.toLowerCase(java.util.Locale.US).startsWith("image/")) return true;
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".jpe")
            || lower.endsWith(".jfif") || lower.endsWith(".png") || lower.endsWith(".webp")
            || lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".avif")
            || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".tif")
            || lower.endsWith(".tiff");
    }

    private String formatBytes(long bytes) {
        if(bytes < 1024) return bytes + " B";
        if(bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if(bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    @Override protected void onPause() {
        saveForm();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (resendCountdown != null) mainHandler.removeCallbacks(resendCountdown);
        saveForm();
        super.onDestroy();
        if (bulkRetryRunnable != null) mainHandler.removeCallbacks(bulkRetryRunnable);
        if (!bulkUploadRunning && !uploadInProgress) io.shutdown();
    }
}
