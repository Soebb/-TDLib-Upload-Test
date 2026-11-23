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
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private EditText apiId, apiHash, phone, code, channel;
    private TextView status, selected, codeInfoView;
    private Button resendButton;
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

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        log("Ready. Get API ID and API Hash from https://my.telegram.org.");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(24,24,24,24);
        scroll.addView(root);
        TextView title = new TextView(this); title.setText("TDLib Upload Test"); title.setTextSize(24); root.addView(title);
        TextView note = new TextView(this); note.setText("Personal account • Official TDLib • Direct MTProto test"); root.addView(note);
        apiId = field(root,"API ID (from my.telegram.org)");
        apiHash = field(root,"API Hash");
        phone = field(root,"Phone number (international format)");
        button(root,"Connect / Send login code",v -> startLogin());
        code = field(root,"Telegram login code");
        button(root,"Submit code",v -> submitCode());
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
        button(root,"Choose file",v -> chooseFile());
        selected = new TextView(this); selected.setText("No file selected"); root.addView(selected);
        button(root,"Upload file to channel",v -> upload());
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
    private void button(LinearLayout root,String text,View.OnClickListener click) {
        Button b=new Button(this); b.setText(text); b.setOnClickListener(click); root.addView(b);
    }
    private void log(String s) { runOnUiThread(() -> status.append("\n"+s)); }

    private void startLogin() {
        try {
            pendingApiId=Integer.parseInt(apiId.getText().toString().trim());
            pendingApiHash=apiHash.getText().toString().trim();
            String number=phone.getText().toString().trim();
            if(pendingApiId<=0 || pendingApiHash.isEmpty() || number.isEmpty()) {
                log("Fill API ID, API Hash and phone number first."); return;
            }
            if(client!=null) { log("TDLib client already started."); return; }
            log("Starting TDLib...");
            Client.setLogMessageHandler(0,(level,message)->log("TDLib: "+message));
            client=Client.create(this::onUpdate,null,null);
        } catch(Throwable e) { log("TDLib initialization failed: "+e); }
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
                if(!phoneSubmitted) {
                    String number=phone.getText().toString().trim().replace(" ","").replace("-","");
                    if(!number.startsWith("+")) number="+"+number;
                    phoneSubmitted=true;
                    log("Submitting phone number to Telegram...");
                    client.send(new TdApi.SetAuthenticationPhoneNumber(number,null),this::onResult);
                }
            } else if(s instanceof TdApi.AuthorizationStateWaitCode) {
                TdApi.AuthenticationCodeInfo info = ((TdApi.AuthorizationStateWaitCode)s).codeInfo;
                showCodeInfo(info);
                log("TDLib is waiting for the login code. Check the delivery method shown above.");
            } else if(s instanceof TdApi.AuthorizationStateWaitPassword) {
                log("This account has two-step verification enabled. This simplified build omits password entry, so sign-in cannot continue for this account.");
            } else if(s instanceof TdApi.AuthorizationStateReady) {
                log("CONNECTED: account authorized. You can upload.");
            } else if(s instanceof TdApi.AuthorizationStateLoggingOut) log("Logging out...");
            else if(s instanceof TdApi.AuthorizationStateClosing) log("Closing...");
            else if(s instanceof TdApi.AuthorizationStateClosed) log("TDLib closed.");
        } else if(object instanceof TdApi.UpdateFile) {
            TdApi.File f=((TdApi.UpdateFile)object).file;
            if(chosenFile!=null && f.local!=null && chosenFile.getAbsolutePath().equals(f.local.path) && f.remote!=null) {
                long total=Math.max(1L,f.size);
                log("Upload progress: "+(100L*f.remote.uploadedSize/total)+"%");
            }
        } else if(object instanceof TdApi.UpdateMessageSendSucceeded) {
            log("UPLOAD SUCCESS. Telegram message ID: "+((TdApi.UpdateMessageSendSucceeded)object).message.id);
        } else if(object instanceof TdApi.UpdateMessageSendFailed) {
            log("UPLOAD FAILED: "+((TdApi.UpdateMessageSendFailed)object).error.message);
        }
    }
    private void onResult(TdApi.Object o) {
        if(o instanceof TdApi.Error) {
            log("Error "+((TdApi.Error)o).code+": "+((TdApi.Error)o).message);
        } else if(o instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            log("Current authorization state: WaitPhoneNumber");
            submitPhoneNumberIfNeeded();
        } else if(o instanceof TdApi.AuthorizationStateWaitCode) {
            showCodeInfo(((TdApi.AuthorizationStateWaitCode)o).codeInfo);
            log("Current authorization state: WaitCode.");
        } else if(o instanceof TdApi.Ok && resendButton != null) {
            log("TDLib accepted the request to resend/request the next code method. Waiting for updated delivery information...");
        } else {
            log("TDLib response: "+o.getClass().getSimpleName());
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
                + "\\nCurrent delivery method: " + current
                + "\\nNext method: " + (info.nextType == null ? "Not offered by Telegram" : next)
                + "\\nWait before next request: " + info.timeout + " seconds";
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
        log("Requesting the next code delivery method from Telegram...");
        client.send(new TdApi.ResendAuthenticationCode(), this::onResult);
    }

    private void submitPhoneNumberIfNeeded() {
        if(client==null || phoneSubmitted) return;
        String number=phone.getText().toString().trim().replace(" ","").replace("-","");
        if(number.startsWith("00")) number="+"+number.substring(2);
        else if(!number.startsWith("+")) number="+"+number;
        phoneSubmitted=true;
        log("Submitting phone number to Telegram...");
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
        if(requestCode==1001 && resultCode==RESULT_OK && data!=null) {
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
                    chosenFile=out; runOnUiThread(()->selected.setText(out.getName()+" ("+out.length()+" bytes)"));
                } catch(Exception e) { log("File copy failed: "+e); }
            });
        }
    }
    private void upload() {
        if(client==null || chosenFile==null || !chosenFile.exists()) { log("Connect first and choose a file."); return; }
        String username=channel.getText().toString().trim().replace("@","");
        if(username.isEmpty()) { log("Enter a public channel username."); return; }
        log("Resolving @"+username+"...");
        client.send(new TdApi.SearchPublicChat(username),o->{
            if(o instanceof TdApi.Error) { log("Channel lookup failed: "+((TdApi.Error)o).message); return; }
            TdApi.Chat chat=(TdApi.Chat)o;
            log("Found channel: "+chat.title+" (ID "+chat.id+"). Starting upload...");
            TdApi.InputDocument document=new TdApi.InputDocument(
                new TdApi.InputFileLocal(chosenFile.getAbsolutePath()),
                null,
                false);
            TdApi.InputMessageContent content=new TdApi.InputMessageDocument(document,null);
            client.send(new TdApi.SendMessage(chat.id,null,null,null,null,content),this::onResult);
        });
    }
    @Override protected void onDestroy() { super.onDestroy(); io.shutdown(); }
}
