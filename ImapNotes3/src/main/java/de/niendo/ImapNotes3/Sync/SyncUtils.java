package de.niendo.ImapNotes3.Sync;

import android.accounts.Account;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.net.TrafficStats;
import android.util.Log;

import de.niendo.ImapNotes3.Data.NotesDb;
import de.niendo.ImapNotes3.Data.OneNote;
import de.niendo.ImapNotes3.Data.Security;
import de.niendo.ImapNotes3.Miscs.HtmlNote;
import de.niendo.ImapNotes3.Miscs.ImapNotesResult;
import de.niendo.ImapNotes3.Miscs.Imaper;
import de.niendo.ImapNotes3.Miscs.StickyNote;

import com.sun.mail.imap.AppendUID;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.util.MailSSLSocketFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Properties;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.MimeMessage;

import de.niendo.ImapNotes3.Miscs.Utilities;


public class SyncUtils {

    private static final String TAG = "IN_SyncUtils";
    private static Store store;
    // TODO: Why do we have two folder fields and why are they both nullable?
    @NonNull
    private static String sfolder = "Notes";
    //private final static int NEW = 1;
    //private final static int DELETED = 2;
    //private final static int ROOT_AND_NEW = 3;
    @Nullable
    private static Folder notesFolder = null;
    private static Long UIDValidity;

    @NonNull
    static ImapNotesResult ConnectToRemote(@NonNull String username,
                                           @NonNull String password,
                                           @NonNull String server,
                                           String portnum,
                                           @NonNull Security security,
                                           @NonNull String folderOverride,
                                           int threadID
    ) {
        Log.d(TAG, "ConnectToRemote: " + username);

        TrafficStats.setThreadStatsTag(threadID);

        //final ImapNotesResult res = new ImapNotesResult();
        if (IsConnected()) {
            try {
                store.close();
            } catch (MessagingException e) {
                // Log the error but do not propagate the exception because the connection is now
                // closed even if an exception was thrown.
                Log.d(TAG, e.getMessage());
            }
        }

        //boolean acceptcrt = security.acceptcrt;

        MailSSLSocketFactory sf;
        try {
            sf = new MailSSLSocketFactory();
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
            return new ImapNotesResult(Imaper.ResultCodeCantConnect,
                    "Can't connect to server: " + e.getMessage(),
                    -1,
                    null);
            //res.errorMessage = "Can't connect to server: " + e.getMessage();
            //res.returnCode = Imaper.ResultCodeCantConnect;
            //return res;
        }

        Properties props = new Properties();

        String proto = security.proto;
        props.setProperty(String.format("mail.%s.host", proto), server);
        props.setProperty(String.format("mail.%s.port", proto), portnum);
        props.setProperty("mail.store.protocol", proto);

        if (security.acceptcrt) {
            sf.setTrustedHosts(new String[]{server});
            if (proto.equals("imap")) {
                props.put("mail.imap.ssl.socketFactory", sf);
                props.put("mail.imap.starttls.enable", "true");
            }
        } else if (security != Security.None) {
            props.put(String.format("mail.%s.ssl.checkserveridentity", proto), "true");
            if (proto.equals("imap")) {
                props.put("mail.imap.starttls.enable", "true");
            }
        }

        if (proto.equals("imaps")) {
            props.put("mail.imaps.socketFactory", sf);
        }

        props.setProperty("mail.imap.connectiontimeout", "1000");
        // TODO: use user defined proxy.
        Boolean useProxy = false;
        //noinspection ConstantConditions
        /*
        if (useProxy) {
            props.put("mail.imap.socks.host", "10.0.2.2");
            props.put("mail.imap.socks.port", "1080");
        }
         */
        try {
            Session session = Session.getInstance(props, null);
//this.session.setDebug(true);
            store = session.getStore(proto);
            store.connect(server, username, password);
            //res.hasUIDPLUS = ((IMAPStore) store).hasCapability("UIDPLUS");
//Log.d(TAG, "has UIDPLUS="+res.hasUIDPLUS);

            Folder[] folders = store.getPersonalNamespaces();
            Folder rootFolder = folders[0];
            Log.d(TAG, "Personal Namespaces=" + rootFolder.getFullName());
            // TODO: this the wrong place to make decisions about the name of the notes folder, that
            // should be done where it is created.
            if (folderOverride.length() > 0)
                sfolder = folderOverride;
            else
                sfolder = "Notes";
            if (rootFolder.getFullName().length() > 0) {
                char separator = rootFolder.getSeparator();
                sfolder = rootFolder.getFullName() + separator + sfolder;
            }
            // Get UIDValidity
            notesFolder = store.getFolder(sfolder);
            if (!notesFolder.exists()) {
                if (notesFolder.create(Folder.HOLDS_MESSAGES)) {
                    notesFolder.setSubscribed(true);
                    Log.d(TAG, "Folder was created successfully");
                }
            }
//          store.close();
            return new ImapNotesResult(Imaper.ResultCodeSuccess,
                    "",
                    ((IMAPFolder) notesFolder).getUIDValidity(),
                    notesFolder);
            //res.UIDValidity = ((IMAPFolder) notesFolder).getUIDValidity();
            //res.errorMessage = "";
            //res.returnCode = Imaper.ResultCodeSuccess;
            //res.notesFolder = notesFolder;
            //return res;
        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
            return new ImapNotesResult(Imaper.ResultCodeException,
                    e.getMessage(),
                    -1,
                    null);
            //res.errorMessage = e.getMessage();
            //res.returnCode = Imaper.ResultCodeException;
            //return res;
        }

    }

    /* Copy all notes from the IMAP server to the local directory using the UID as the file name.

     */
    static void GetNotes(@NonNull Account account,
                         @NonNull Folder imapNotesFolder,
                         @NonNull Context applicationContext,
                         @NonNull NotesDb storedNotes,
                         @NonNull boolean useSticky) throws MessagingException, IOException {
        Log.d(TAG, "GetNotes: " + account.name);
        //Long UIDM;
        //Message notesMessage;
        File directory = new File(applicationContext.getFilesDir(), account.name);
        if (imapNotesFolder.isOpen()) {
            if ((imapNotesFolder.getMode() & Folder.READ_ONLY) != 0)
                imapNotesFolder.open(Folder.READ_ONLY);
        } else {
            imapNotesFolder.open(Folder.READ_ONLY);
        }
        UIDValidity = GetUIDValidity(account, applicationContext);
        SetUIDValidity(account, UIDValidity, applicationContext);
        // From the docs: "Folder implementations are expected to provide light-weight Message
        // objects, which get filled on demand. "
        // This means that at this point we can ask for the subject without getting the rest of the
        // message.
        Message[] notesMessages = imapNotesFolder.getMessages();
        //Log.d(TAG,"number of messages in folder="+(notesMessages.length));
        // TODO: explain why we enumerate the messages in descending order of index.
        for (int index = notesMessages.length - 1; index >= 0; index--) {
            Message notesMessage = notesMessages[index];
            // write every message in files/{accountname} directory
            // filename is the original message uid
            Long UIDM = ((IMAPFolder) imapNotesFolder).getUID(notesMessage);
            String suid = UIDM.toString();
            String bgColor;
            if (useSticky) {
                bgColor = StickyNote.GetStickyFromMessage(notesMessage).color;
            } else {
                bgColor = HtmlNote.GetNoteFromMessage(notesMessage).color;
            }

            File outfile = new File(directory, suid);
            SaveNoteAndUpdateDatabase(outfile, notesMessage, storedNotes, account.name, suid, bgColor);
        }
    }


    private static boolean IsConnected() {
        return store != null && store.isConnected();
    }

    static void DeleteNote(int numMessage) throws MessagingException {
        Log.d(TAG, "DeleteNote: " + numMessage);
        Folder notesFolder = store.getFolder(sfolder);
        if (notesFolder.isOpen()) {
            if ((notesFolder.getMode() & Folder.READ_WRITE) != 0) {
                notesFolder.open(Folder.READ_WRITE);
            }
        } else {
            notesFolder.open(Folder.READ_WRITE);
        }

        //Log.d(TAG,"UID to remove:"+numMessage);
        Message[] msgs = {((IMAPFolder) notesFolder).getMessageByUID(numMessage)};
        notesFolder.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
        ((IMAPFolder) notesFolder).expunge(msgs);
    }

    // Put values in shared preferences
    static void SetUIDValidity(@NonNull Account account,
                               Long UIDValidity,
                               @NonNull Context ctx) {
        Log.d(TAG, "SetUIDValidity: " + account.name);
        SharedPreferences preferences = ctx.getSharedPreferences(account.name, Context.MODE_MULTI_PROCESS);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("Name", "valid_data");
        //Log.d(TAG, "UIDValidity set to in shared_prefs:"+UIDValidity);
        editor.putLong("UIDValidity", UIDValidity);
        editor.apply();
    }

    // Retrieve values from shared preferences:
    static Long GetUIDValidity(@NonNull Account account,
                               @NonNull Context ctx) {
        Log.d(TAG, "GetUIDValidity: " + account.name);
        UIDValidity = (long) -1;
        SharedPreferences preferences = ctx.getSharedPreferences(account.name, Context.MODE_MULTI_PROCESS);
        String name = preferences.getString("Name", "");
        if (!name.equalsIgnoreCase("")) {
            UIDValidity = preferences.getLong("UIDValidity", -1);
            //Log.d(TAG, "UIDValidity got from shared_prefs:"+UIDValidity);
        }
        return UIDValidity;
    }

    static void DisconnectFromRemote() {
        Log.d(TAG, "DisconnectFromRemote");
        try {
            store.close();
        } catch (MessagingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    /*

     */
/**
 * @param uid ID of the message as created by the IMAP server
 * @param where TODO: what is this?
 * @param removeMinus TODO: Why?
 * @param nameDir Name of the account with which this message is associated, used to create the
 *                directory in which to store it.
 * @return A Java mail message object.
 *//*

    @Nullable
    public static Message ReadMailFromFile(@NonNull String uid,
                                           Where where,
                                           boolean removeMinus,
                                           @NonNull String nameDir) {
        File mailFile;
        Message message = null;
        mailFile = new File(nameDir, uid);

        switch (where) {
            case NEW:
                nameDir = nameDir + "/new";
                if (removeMinus) uid = uid.substring(1);
                break;
            case DELETED:
                nameDir = nameDir + "/deleted";
                break;
            case ROOT_AND_NEW:
                if (!mailFile.exists()) {
                    nameDir = nameDir + "/new";
                    if (removeMinus) uid = uid.substring(1);
                }
                break;
            default:
                throw new UnsupportedOperationException("Unrecognized value for where argument: " + where);
        }

        mailFile = new File(nameDir, uid);
        InputStream mailFileInputStream = null;
        try {
            mailFileInputStream = new FileInputStream(mailFile);
        } catch (FileNotFoundException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        try {
            Properties props = new Properties();
            Session session = Session.getDefaultInstance(props, null);
            message = new MimeMessage(session, mailFileInputStream);
        } catch (MessagingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return message;
    }
*/

    /**
     * @param uid         ID of the message as created by the IMAP server
     * @param newFilesDir Directory in which it is stored.
     * @return A Java mail message object.
     */
    @Nullable
    static Message ReadMailFromFileNew(@NonNull String uid,
                                       @NonNull File newFilesDir) {
        Log.d(TAG, "ReadMailFromFileNew");
        //File mailFile;
        //Message message = null;
        //mailFile = new File(nameDir, uid);
        return ReadMailFromFile(newFilesDir, uid);
    }

    /**
     * @param uid     ID of the message as created by the IMAP server
     * @param fileDir Name of the account with which this message is associated, used to find the
     *                directory in which it is stored.
     * @return A Java mail message object.
     */
    @Nullable
    public static Message ReadMailFromFileRootAndNew(@NonNull String uid,
                                                     @NonNull File fileDir) {
        Log.d(TAG, "ReadMailFromFileRootAndNew: " + fileDir.getPath() + " " + uid);

        // new or changed file
        if (uid.startsWith("-")) {
            uid = uid.substring(1);
            fileDir = new File(fileDir, "new");
        }
        File mailFile = new File(fileDir, uid);

        if (!mailFile.exists()) {
            Log.d(TAG, "ReadMailFromFileRootAndNew: file not found..");
            return null;
        }

        return ReadMailFromFile(fileDir, uid);
    }

// --Commented out by Inspection START (11/26/16 11:46 PM):
//    /**
//     * @param uid         ID of the message as created by the IMAP server
//     * @param nameDir     Name of the account with which this message is associated, used to find the
//     *                    directory in which it is stored.
//     * @return A Java mail message object.
//     */
//    @Nullable
//    public static Message ReadMailFromFileDeleted(@NonNull String uid,
//                                                  @NonNull String nameDir) {
//        return ReadMailFromFile(new File(nameDir, "deleted"), uid);
//    }
// --Commented out by Inspection STOP (11/26/16 11:46 PM)


    /**
     * @param uid     ID of the message as created by the IMAP server
     * @param nameDir Name of the account with which this message is associated, used to find the
     *                directory in which it is stored.
     * @return A Java mail message object.
     */
    @Nullable
    private static Message ReadMailFromFile(@NonNull File nameDir,
                                            @NonNull String uid) {
        Log.d(TAG, "ReadMailFromFile: " + nameDir.getPath() + " " + uid);
        File mailFile = new File(nameDir, uid);

        try (InputStream mailFileInputStream = new FileInputStream(mailFile)) {
            try {
                Properties props = new Properties();
                Session session = Session.getDefaultInstance(props, null);
                Message message = new MimeMessage(session, mailFileInputStream);
                mailFileInputStream.close();
                Log.d(TAG, "ReadMailFromFile return new MimeMessage.");
                return message;
            } catch (MessagingException e) {
                // TODO Auto-generated catch block
                Log.d(TAG, "Exception getting MimeMessage.");
                e.printStackTrace();
            } catch (Exception e2) {
                //TODO: handle this properly
                Log.d(TAG, "exception opening mailFile: ");
                e2.printStackTrace();
            }
        } catch (FileNotFoundException e1) {
            // TODO Auto-generated catch block
            Log.d(TAG, "File not found opening mailFile: " + mailFile.getAbsolutePath());
            e1.printStackTrace();
        } catch (IOException exIO) {
            //TODO: handle this properly
            Log.d(TAG, "IO exception opening mailFile: " + mailFile.getAbsolutePath());
            exIO.printStackTrace();
        }
        Log.d(TAG, "ReadMailFromFile return null.");
        return null;
    }

    static AppendUID[] sendMessageToRemote(@NonNull Message[] message) throws MessagingException {
        notesFolder = store.getFolder(sfolder);
        if (notesFolder.isOpen()) {
            if ((notesFolder.getMode() & Folder.READ_WRITE) != 0)
                notesFolder.open(Folder.READ_WRITE);
        } else {
            notesFolder.open(Folder.READ_WRITE);
        }
        return ((IMAPFolder) notesFolder).appendUIDMessages(message);
    }

// --Commented out by Inspection START (12/3/16 11:31 PM):
//    static void ClearHomeDir(@NonNull Account account, @NonNull Context ctx) {
//        File directory = new File(ctx.getFilesDir() + "/" + account.name);
//        try {
//            FileUtils.deleteDirectory(directory);
//        } catch (IOException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        }
//    }
// --Commented out by Inspection STOP (12/3/16 11:31 PM)

    /**
     * Do we really need the Context argument or could we call getApplicationContext instead?
     *
     * @param accountName        Name of the account as defined by the user, this is not the email address.
     * @param applicationContext Global context not an activity context.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void CreateLocalDirectories(@NonNull String accountName, @NonNull Context applicationContext) {
        Log.d(TAG, "CreateDirs(String: " + accountName);
        File dir = new File(applicationContext.getFilesDir(), accountName);
        //File directory = new File(stringDir);
        //directory.mkdirs();
        (new File(dir, "new")).mkdirs();
        (new File(dir, "deleted")).mkdirs();
    }


    /**
     * @param outfile      Name of local file in which to store the note.
     * @param notesMessage The note in the form of a mail message.
     */
    private static void SaveNote(@NonNull File outfile,
                                 @NonNull Message notesMessage) throws IOException, MessagingException {

        Log.d(TAG, "SaveNote: " + outfile.getCanonicalPath());
        try (OutputStream str = new FileOutputStream(outfile)) {
            notesMessage.writeTo(str);
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (MessagingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    private static void SaveNoteAndUpdateDatabase(@NonNull File outfile,
                                                  @NonNull Message notesMessage,
                                                  @NonNull NotesDb storedNotes,
                                                  @NonNull String accountName,
                                                  @NonNull String suid,
                                                  @NonNull String bgColor) throws IOException, MessagingException {
        Log.d(TAG, "SaveNoteAndUpdateDatabase: " + outfile.getCanonicalPath() + " " + accountName);

        SaveNote(outfile, notesMessage);

        // Now update or save the metadata about the message

        String title = null;
        String[] rawvalue = null;
        // Some servers (such as posteo.de) don't encode non us-ascii characters in subject
        // This is a workaround to handle them
        // "lä ö ë" subject should be stored as =?charset?encoding?encoded-text?=
        // either =?utf-8?B?bMOkIMO2IMOr?=  -> Quoted printable
        // or =?utf-8?Q?l=C3=A4 =C3=B6 =C3=AB?=  -> Base64
        try {
            rawvalue = notesMessage.getHeader("Subject");
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            title = notesMessage.getSubject();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (rawvalue[0].length() >= 2) {
            if (!(rawvalue[0].substring(0, 2).equals("=?"))) {
                try {
                    title = new String(title.getBytes("ISO-8859-1"));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } else {
            try {
                title = new String(title.getBytes("ISO-8859-1"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Get INTERNALDATE
        //String internaldate = null;
        Date MessageInternaldate = null;
        try {
            MessageInternaldate = notesMessage.getReceivedDate();
        } catch (MessagingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        //String DATE_FORMAT = "yyyy-MM-dd HH:MM:ss";
        //SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT, Locale.ROOT);
        String internaldate = Utilities.internalDateFormat.format(MessageInternaldate);

        OneNote aNote = new OneNote(
                title,
                internaldate,
                suid,
                accountName,
                bgColor);
        storedNotes.InsertANoteInDb(aNote);
    }

    static boolean handleRemoteNotes(@NonNull Context context,
                                     @NonNull javax.mail.Folder remoteIMAPNotesFolder,
                                     @NonNull NotesDb storedNotes,
                                     @NonNull String accountName,
                                     @NonNull Boolean useSticky)
            throws MessagingException, IOException {
        Log.d(TAG, "handleRemoteNotes: " + remoteIMAPNotesFolder.getFullName() + " " + accountName + " " + useSticky);

        Message notesMessage;
        boolean result = false;
        ArrayList<Long> uids = new ArrayList<>();
        ArrayList<String> localListOfNotes = new ArrayList<>();
        String remoteInternaldate;
        String localInternaldate;
        //Flags flags;

        if (remoteIMAPNotesFolder.isOpen()) {
            if ((remoteIMAPNotesFolder.getMode() & Folder.READ_ONLY) != 0)
                remoteIMAPNotesFolder.open(Folder.READ_WRITE);
        } else {
            remoteIMAPNotesFolder.open(Folder.READ_WRITE);
        }

        // Get local list of notes uids
        String rootString = context.getFilesDir() + "/" + accountName;
        File rootDir = new File(rootString);
        File[] files = rootDir.listFiles();
        for (File file : files) {
            if (file.isFile()) {
                localListOfNotes.add(file.getName());
            }
        }

        // Add to local device, new notes added to remote
        Message[] notesMessages = ((IMAPFolder) remoteIMAPNotesFolder).getMessagesByUID(1, UIDFolder.LASTUID);
        for (int index = notesMessages.length - 1; index >= 0; index--) {
            notesMessage = notesMessages[index];
            Long uid = ((IMAPFolder) remoteIMAPNotesFolder).getUID(notesMessage);
            // Get FLAGS
            //flags = notesMessage.getFlags();
            boolean deleted = notesMessage.isSet(Flags.Flag.DELETED);
            // Builds remote list while in the loop, but only if not deleted on remote
            if (!deleted) {
                uids.add(((IMAPFolder) remoteIMAPNotesFolder).getUID(notesMessage));
            }
            String suid = uid.toString();
            if (!(localListOfNotes.contains(suid))) {
                File outfile = new File(rootDir, suid);
                String bgColor;
                if (useSticky) {
                    bgColor = StickyNote.GetStickyFromMessage(notesMessage).color;
                } else {
                    bgColor = HtmlNote.GetNoteFromMessage(notesMessage).color;
                }
                SaveNoteAndUpdateDatabase(outfile, notesMessage, storedNotes, accountName, suid, bgColor);
                result = true;
            } else if (useSticky) {
                //Log.d (TAG,"MANAGE STICKY");
                remoteInternaldate = DateFormat.getDateInstance().format(notesMessage.getSentDate());
                localInternaldate = storedNotes.GetDate(suid, accountName);
                if (!(remoteInternaldate.equals(localInternaldate))) {
                    File outfile = new File(rootDir, suid);
                    SaveNote(outfile, notesMessage);
                    result = true;
                }
            }
        }

        // Remove from local device, notes removed from remote
        for (String suid : localListOfNotes) {
            Long uid = Long.valueOf(suid);
            if (!(uids.contains(uid))) {
                // remove file from deleted
                File toDelete = new File(rootDir, suid);
                //noinspection ResultOfMethodCallIgnored
                toDelete.delete();
                // Remove note from database
                storedNotes.DeleteANote(suid, accountName);
                result = true;
            }
        }

        return result;
    }

    static void RemoveAccount(@NonNull Context context, @NonNull Account account) {
        Log.d(TAG, "RemoveAccount: " + account.name);
        // remove Shared Preference file
        String rootString = context.getFilesDir().getParent() +
                File.separator + "shared_prefs";
        File rootDir = new File(rootString);
        File toDelete = new File(rootDir, account.name + ".xml");
        //noinspection ResultOfMethodCallIgnored
        toDelete.delete();
        // Remove all files and sub directories
        File filesDir = context.getFilesDir();
        File[] files = filesDir.listFiles();
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        // Delete account name entries in database
        NotesDb storedNotes = NotesDb.getInstance(context);
        storedNotes.ClearDb(account.name);
    }
}
