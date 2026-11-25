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
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private EditText apiId, apiHash, phone, code, channel, captionInput, linksInput;
    private TextView status, selected, codeInfoView, accountState, queueSummary;
    private Button resendButton, connectButton, submitCodeButton, uploadButton;
    private ProgressBar uploadProgress;
    private TextView uploadProgressText;
    private final StringBuilder logBuffer = new StringBuilder();
    private boolean isSignedIn = false;
    private boolean uploadInProgress = false;
    private int uploadFileId = 0;
    private long uploadTotalBytes = 0;
    private int lastLoggedProgressStep = -1;
    private Client client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int resendSecondsRemaining = 0;
    private boolean hasNextCodeMethod = false;
    private Runnable resendCountdown;
    private File chosenFile;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int pendingApiId;
    private String pendingApiHash = "";
    private boolean phoneSubmitted = false;
    private boolean loginRequested = false;
    private SharedPreferences preferences;
    private PythonBulkEngine bulkEngine;
    private volatile boolean bulkUploadRunning = false;
    private long bulkChatId = 0;
    private String bulkCurrentUrl = "";
    private String bulkCurrentCaption = "";
    private JSONArray bulkCurrentFiles;
    private JSONArray bulkCurrentMedia;
    private JSONArray bulkChunkPlan;
    private int bulkChunkIndex = 0;

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
        button(root,"Import links into SQLite",v -> importLinks());
        queueSummary = new TextView(this);
        queueSummary.setText("Queue database is initializing...");
        root.addView(queueSummary);
        button(root,"Refresh queue status",v -> refreshQueueStatus());
        button(root,"Import links.txt file",v -> chooseLinksFile());
        button(root,"Upload a local file",v -> chooseFile());
        selected = new TextView(this); selected.setText("No file selected"); root.addView(selected);
        uploadButton = button(root,"Start uploading",v -> startBulkUpload());
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
            .putString("code", code.getText().toString())
            .putString("channel", channel.getText().toString())
            .putString("caption", captionInput.getText().toString())
            .apply();
    }

    private void initializeBulkEngine() {
        io.execute(() -> {
            try {
                bulkEngine = new PythonBulkEngine(this);
                String counts = bulkEngine.counts();
                runOnUiThread(() -> queueSummary.setText("SQLite queue: " + counts));
                log("Python/Chaquopy initialized. yt-dlp and sqlite3 are ready.");
            } catch (Throwable error) {
                log("Python engine initialization failed: " + error.getMessage());
            }
        });
    }

    private void importLinks() {
        String text = linksInput.getText().toString();
        if (text.trim().isEmpty()) {
            log("No links entered.");
            return;
        }
        io.execute(() -> {
            try {
                if (bulkEngine == null) bulkEngine = new PythonBulkEngine(this);
                String result = bulkEngine.importLinks(text);
                String counts = bulkEngine.counts();
                runOnUiThread(() -> queueSummary.setText("Import: " + result + "\nQueue: " + counts));
                log("Links imported into Python SQLite database: " + result);
            } catch (Throwable error) {
                log("Link import failed: " + error.getMessage());
            }
        });
    }

    private void refreshQueueStatus() {
        io.execute(() -> {
            try {
                if (bulkEngine == null) bulkEngine = new PythonBulkEngine(this);
                String counts = bulkEngine.counts();
                runOnUiThread(() -> queueSummary.setText("SQLite queue: " + counts));
            } catch (Throwable error) {
                log("Could not read queue database: " + error.getMessage());
            }
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
                    submitPhoneNumberIfNeeded();
                }
                return;
            }
            loginRequested = requestLogin;
            phoneSubmitted = false;
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
                setAccountState("Not signed in");
                if(loginRequested && !phoneSubmitted) {
                    String number=normalizePhoneNumber(phone.getText().toString());
                    phoneSubmitted=true;
                    log("Requesting login code...");
                    client.send(new TdApi.SetAuthenticationPhoneNumber(number,null),this::onResult);
                }
            } else if(s instanceof TdApi.AuthorizationStateWaitCode) {
                TdApi.AuthenticationCodeInfo info = ((TdApi.AuthorizationStateWaitCode)s).codeInfo;
                showCodeInfo(info);
                codeInfoView.setVisibility(View.VISIBLE);
                resendButton.setVisibility(View.VISIBLE);
                setAccountState("Waiting for login code");
                log("Enter the code sent by Telegram.");
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
            TdApi.File file = ((TdApi.UpdateFile)object).file;
            if(uploadInProgress && uploadFileId != 0 && file.id == uploadFileId && file.remote != null) {
                long total = uploadTotalBytes > 0 ? uploadTotalBytes : Math.max(1L, file.size);
                long sent = Math.max(0L, file.remote.uploadedSize);
                int percent = (int)Math.min(100L, sent * 100L / Math.max(1L, total));
                runOnUiThread(() -> {
                    uploadProgress.setVisibility(View.VISIBLE);
                    uploadProgress.setIndeterminate(false);
                    uploadProgress.setProgress(percent);
                    uploadProgressText.setText("Uploading " + percent + "%");
                });
                int progressStep = percent / 10;
                if (progressStep != lastLoggedProgressStep && progressStep > 0) {
                    lastLoggedProgressStep = progressStep;
                    log("Upload: " + (progressStep * 10) + "%");
                }
            }
        } else if(object instanceof TdApi.UpdateMessageSendSucceeded) {
            TdApi.Message message = ((TdApi.UpdateMessageSendSucceeded)object).message;
            uploadInProgress = false;
            runOnUiThread(() -> {
                uploadProgress.setProgress(100);
                uploadProgressText.setText("Upload complete: 100%");
                uploadButton.setEnabled(true);
            });
            log("UPLOAD SUCCESS. Telegram message ID: " + message.id);
        } else if(object instanceof TdApi.UpdateMessageSendFailed) {
            TdApi.UpdateMessageSendFailed failed = (TdApi.UpdateMessageSendFailed)object;
            uploadInProgress = false;
            runOnUiThread(() -> {
                uploadProgress.setIndeterminate(false);
                uploadProgressText.setText("Upload failed: " + failed.error.message);
                uploadButton.setEnabled(true);
            });
            log("UPLOAD FAILED. Error " + failed.error.code + ": " + failed.error.message);
        }
    }
    private void onResult(TdApi.Object o) {
        if(o instanceof TdApi.Error) {
            phoneSubmitted=false;
            loginRequested=false;
            log("Telegram error: "+((TdApi.Error)o).message);
        } else if(o instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            setAccountState("Not signed in");
            if(loginRequested) submitPhoneNumberIfNeeded();
        } else if(o instanceof TdApi.AuthorizationStateWaitCode) {
            showCodeInfo(((TdApi.AuthorizationStateWaitCode)o).codeInfo);
            setAccountState("Waiting for login code");
            runOnUiThread(() -> {
                code.setVisibility(View.VISIBLE);
                submitCodeButton.setVisibility(View.VISIBLE);
                codeInfoView.setVisibility(View.VISIBLE);
                resendButton.setVisibility(View.VISIBLE);
            });
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
    private void submitCode() {
        String c=code.getText().toString().trim();
        if(client!=null && !c.isEmpty()) client.send(new TdApi.CheckAuthenticationCode(c),this::onResult);
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
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    String text = out.toString("UTF-8");
                    runOnUiThread(() -> linksInput.setText(text));
                    log("Loaded links text file (" + text.split("\\r?\\n").length + " lines). Tap Import links into SQLite.");
                } catch (Exception error) {
                    log("Could not read links file: " + error.getMessage());
                }
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
        log("Starting upload workflow. File: " + chosenFile.getName() + ", size: " + formatBytes(uploadTotalBytes));
        log("Resolving public channel @" + username + "...");
        client.send(new TdApi.SearchPublicChat(username), result -> {
            if(result instanceof TdApi.Error) {
                TdApi.Error error = (TdApi.Error)result;
                uploadInProgress = false;
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
                runOnUiThread(() -> uploadButton.setEnabled(true));
                log("CHANNEL LOOKUP FAILED: unexpected TDLib response " + result.getClass().getSimpleName());
                return;
            }
            TdApi.Chat chat = (TdApi.Chat)result;
            log("Channel resolved: " + chat.title + " (ID " + chat.id + ").");
            log("Sending video/document to Telegram...");
            TdApi.InputMessageContent content;
            if (isVideoFile(chosenFile.getName())) {
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
            } else {
                TdApi.InputDocument document = new TdApi.InputDocument(
                    new TdApi.InputFileLocal(chosenFile.getAbsolutePath()), null, false);
                content = new TdApi.InputMessageDocument(document, null);
                log("Selected file is not a recognized video extension; sending as document.");
            }
            client.send(new TdApi.SendMessage(chat.id, null, null, null, null, content), response -> {
                if(response instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error)response;
                    uploadInProgress = false;
                    runOnUiThread(() -> {
                        uploadButton.setEnabled(true);
                        uploadProgress.setIndeterminate(false);
                        uploadProgressText.setText("Send request failed.");
                    });
                    log("SEND MESSAGE FAILED. Error " + error.code + ": " + error.message);
                    return;
                }
                if(response instanceof TdApi.Message) {
                    TdApi.Message message = (TdApi.Message)response;
                    if(message.content instanceof TdApi.MessageVideo) {
                        uploadFileId = ((TdApi.MessageVideo)message.content).video.video.id;
                        log("Telegram accepted the video. Tracking upload file ID " + uploadFileId + ".");
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

    private boolean isVideoFile(String name) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")
            || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".avi")
            || lower.endsWith(".3gp") || lower.endsWith(".mpeg") || lower.endsWith(".mpg")
            || lower.endsWith(".ts") || lower.endsWith(".mts") || lower.endsWith(".m2ts");
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
        io.shutdown();
    }
}
