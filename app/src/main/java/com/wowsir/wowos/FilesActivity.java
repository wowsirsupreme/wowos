package com.wowsir.wowos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** WOW Files: a simple Google Files–style browser for TV remotes. */
public class FilesActivity extends Activity {

    private static final int REQ_STORAGE = 7;

    private static final Set<String> VIDEO = set("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "3gp", "flv", "wmv", "mpg", "mpeg");
    private static final Set<String> AUDIO = set("mp3", "aac", "m4a", "wav", "flac", "ogg", "opus", "wma", "amr");
    private static final Set<String> IMAGE = set("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic");
    private static final Set<String> DOC = set("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt", "csv", "odt", "rtf", "md");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LayoutInflater inflater;

    private LinearLayout placesView;
    private TextView titleView;
    private TextView pathView;
    private TextView emptyView;
    private ListView list;
    private View pasteButton;
    private TextView pasteText;
    private final FileAdapter adapter = new FileAdapter();

    /** Folder being shown, and the storage root it belongs to (Up stops there). */
    private File current;
    private Place currentPlace;
    private final List<Place> places = new ArrayList<>();
    private int loadGeneration;

    /** Copy / cut clipboard. */
    private final List<File> clip = new ArrayList<>();
    private boolean clipIsCut;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);
        inflater = LayoutInflater.from(this);

        placesView = findViewById(R.id.places);
        titleView = findViewById(R.id.title);
        pathView = findViewById(R.id.path);
        emptyView = findViewById(R.id.empty);
        list = findViewById(R.id.list);
        pasteButton = findViewById(R.id.btn_paste);
        pasteText = findViewById(R.id.paste_text);

        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Item it = adapter.getItem(pos);
                if (it.dir) open(it.file, currentPlace); else openFile(it.file, false);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                showFileOptions(adapter.getItem(pos));
                return true;
            }
        });

        findViewById(R.id.btn_up).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goUp(); }
        });
        findViewById(R.id.btn_new).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askNewFolder(); }
        });
        pasteButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { paste(); }
        });
        for (int id : new int[]{R.id.btn_up, R.id.btn_new, R.id.btn_paste}) {
            findViewById(id).setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
        }

        IntentFilter media = new IntentFilter();
        media.addAction(Intent.ACTION_MEDIA_MOUNTED);
        media.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        media.addAction(Intent.ACTION_MEDIA_REMOVED);
        media.addAction(Intent.ACTION_MEDIA_EJECT);
        media.addDataScheme("file");
        registerReceiver(mediaReceiver, media);

        if (hasStorageAccess()) start(); else askStorageAccess();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (current == null && hasStorageAccess() && places.isEmpty()) start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(mediaReceiver); } catch (Exception ignored) { }
    }

    private void start() {
        buildPlaces();
        if (current == null && !places.isEmpty()) open(places.get(0).dir, places.get(0));
    }

    @Override
    public void onBackPressed() {
        if (current != null && currentPlace != null && !same(current, currentPlace.root)) {
            goUp();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && list.hasFocus()) {
            Object o = list.getSelectedItem();
            if (o instanceof Item) { showFileOptions((Item) o); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ permissions

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try { startActivity(i); } catch (Exception e) { toast("Allow file access for WOW OS in Settings"); }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_STORAGE) return;
        if (hasStorageAccess()) start();
        else emptyView.setText("WOW Files needs storage access to show your files.");
    }

    // ------------------------------------------------------------------ places (sidebar)

    private static final class Place {
        final String name;
        final File dir;
        final File root;
        final int icon;
        final boolean volume;

        Place(String name, File dir, File root, int icon, boolean volume) {
            this.name = name;
            this.dir = dir;
            this.root = root;
            this.icon = icon;
            this.volume = volume;
        }
    }

    private void buildPlaces() {
        places.clear();
        File internal = Environment.getExternalStorageDirectory();
        places.add(new Place("Internal storage", internal, internal, R.drawable.ic_phone_storage, true));

        for (File usb : usbRoots()) {
            String label = "USB disk";
            try {
                StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
                StorageVolume v = sm == null ? null : sm.getStorageVolume(usb);
                if (v != null && v.getDescription(this) != null) label = v.getDescription(this);
            } catch (Exception ignored) { }
            places.add(new Place(label, usb, usb, R.drawable.ic_usb, true));
        }

        addShortcut("Downloads", Environment.DIRECTORY_DOWNLOADS, R.drawable.ic_download, internal);
        addShortcut("Movies", Environment.DIRECTORY_MOVIES, R.drawable.ic_video, internal);
        addShortcut("Music", Environment.DIRECTORY_MUSIC, R.drawable.ic_music, internal);
        addShortcut("Pictures", Environment.DIRECTORY_PICTURES, R.drawable.ic_image, internal);
        addShortcut("Camera", Environment.DIRECTORY_DCIM, R.drawable.ic_image, internal);
        renderPlaces();
    }

    private void addShortcut(String name, String type, int icon, File root) {
        File d = Environment.getExternalStoragePublicDirectory(type);
        if (d != null && d.isDirectory()) places.add(new Place(name, d, root, icon, false));
    }

    /** Mounted removable volumes (USB disks, SD cards). */
    private List<File> usbRoots() {
        List<File> out = new ArrayList<>();
        try {
            StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (sm == null) return out;
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable()) continue;
                String st = v.getState();
                if (!Environment.MEDIA_MOUNTED.equals(st) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(st)) continue;
                File dir = volumeDir(v);
                if (dir != null && dir.isDirectory()) out.add(dir);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static File volumeDir(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        try {
            Method m = StorageVolume.class.getMethod("getPathFile");
            return (File) m.invoke(v);
        } catch (Exception ignored) { }
        try {
            String uuid = v.getUuid();
            if (uuid != null) return new File("/storage/" + uuid);
        } catch (Exception ignored) { }
        return null;
    }

    private void renderPlaces() {
        placesView.removeAllViews();
        for (final Place p : places) {
            View row = inflater.inflate(R.layout.item_place, placesView, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(p.icon);
            ((TextView) row.findViewById(R.id.name)).setText(p.name);
            TextView meta = row.findViewById(R.id.meta);
            ProgressBar bar = row.findViewById(R.id.bar);
            if (p.volume) {
                try {
                    StatFs fs = new StatFs(p.dir.getPath());
                    long total = fs.getTotalBytes();
                    long free = fs.getAvailableBytes();
                    String txt = Formatter.formatShortFileSize(this, free) + " free of "
                            + Formatter.formatShortFileSize(this, total);
                    if (!p.dir.canWrite()) txt += " · read-only";
                    meta.setText(txt);
                    bar.setVisibility(View.VISIBLE);
                    bar.setProgress(total > 0 ? (int) ((total - free) * 1000 / total) : 0);
                } catch (Exception e) {
                    meta.setText(p.dir.getPath());
                }
            } else {
                meta.setVisibility(View.GONE);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    open(p.dir, p);
                    list.requestFocus();
                }
            });
            placesView.addView(row);
        }
    }

    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!hasStorageAccess()) return;
                    buildPlaces();
                    if (current != null && !current.isDirectory()) open(places.get(0).dir, places.get(0));
                }
            }, 600);
        }
    };

    // ------------------------------------------------------------------ browsing

    private static final class Item {
        final File file;
        final String name;
        final boolean dir;
        final long size;
        final long modified;
        final int children;

        Item(File f) {
            file = f;
            name = f.getName();
            dir = f.isDirectory();
            size = dir ? 0 : f.length();
            modified = f.lastModified();
            String[] kids = dir ? f.list() : null;
            children = kids == null ? 0 : kids.length;
        }
    }

    private void open(final File dir, Place place) {
        current = dir;
        currentPlace = place;
        titleView.setText(same(dir, place.root) ? place.name : dir.getName());
        pathView.setText(dir.getAbsolutePath());
        emptyView.setText("Loading…");
        emptyView.setVisibility(View.VISIBLE);
        adapter.set(new ArrayList<Item>());

        final int gen = ++loadGeneration;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File[] files = dir.listFiles();
                final List<Item> items = new ArrayList<>();
                if (files != null) {
                    for (File f : files) if (!f.getName().startsWith(".")) items.add(new Item(f));
                }
                Collections.sort(items, new Comparator<Item>() {
                    @Override
                    public int compare(Item a, Item b) {
                        if (a.dir != b.dir) return a.dir ? -1 : 1;
                        return a.name.compareToIgnoreCase(b.name);
                    }
                });
                final boolean unreadable = files == null;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != loadGeneration) return;
                        adapter.set(items);
                        if (unreadable) emptyView.setText("This folder can't be opened");
                        else emptyView.setText("Empty folder");
                        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                        if (!items.isEmpty()) list.setSelection(0);
                    }
                });
            }
        }, "wowfiles-list").start();
    }

    private void goUp() {
        if (current == null || currentPlace == null) return;
        if (same(current, currentPlace.root)) {
            toast("Top of " + currentPlace.name);
            return;
        }
        final File child = current;
        File parent = current.getParentFile();
        if (parent == null) return;
        open(parent, currentPlace);
        // Put focus back on the folder we came out of.
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < adapter.getCount(); i++) {
                    if (same(adapter.getItem(i).file, child)) { list.setSelection(i); break; }
                }
            }
        }, 300);
    }

    private void openFile(File f, boolean chooser) {
        String mime = FileShare.mimeOf(f);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileShare.uriFor(this, f), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mime.equals("application/vnd.android.package-archive") && Build.VERSION.SDK_INT >= 26
                && !getPackageManager().canRequestPackageInstalls()) {
            toast("Allow WOW OS to install apps, then open the APK again");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        try {
            if (chooser || i.resolveActivity(getPackageManager()) == null) {
                Intent any = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(FileShare.uriFor(this, f), mime.startsWith("application/octet") ? "*/*" : mime.split("/")[0] + "/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(any, "Open " + f.getName() + " with"));
            } else {
                startActivity(i);
            }
        } catch (Exception e) {
            toast("No app on this box can open " + f.getName());
        }
    }

    // ------------------------------------------------------------------ file actions

    private void showFileOptions(final Item it) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (!it.dir) {
            labels.add("Open with…");
            actions.add(new Runnable() { @Override public void run() { openFile(it.file, true); } });
        }
        labels.add("Copy");
        actions.add(new Runnable() { @Override public void run() { setClip(it.file, false); } });
        labels.add("Move");
        actions.add(new Runnable() { @Override public void run() { setClip(it.file, true); } });
        labels.add("Rename");
        actions.add(new Runnable() { @Override public void run() { askRename(it.file); } });
        labels.add("Delete");
        actions.add(new Runnable() { @Override public void run() { askDelete(it.file); } });
        labels.add("Details");
        actions.add(new Runnable() { @Override public void run() { showDetails(it); } });

        showGlass(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(it.name)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { actions.get(which).run(); }
                }).create());
    }

    private void setClip(File f, boolean cut) {
        clip.clear();
        clip.add(f);
        clipIsCut = cut;
        pasteText.setText(cut ? "Move here" : "Paste here");
        pasteButton.setVisibility(View.VISIBLE);
        toast((cut ? "Moving " : "Copying ") + f.getName() + ". Open the destination folder and press "
                + (cut ? "Move here." : "Paste here."));
    }

    private void paste() {
        if (clip.isEmpty() || current == null) return;
        final File destDir = current;
        if (!destDir.canWrite()) {
            toast(readOnlyMessage());
            return;
        }
        for (File f : clip) {
            if (f.isDirectory() && (destDir.getAbsolutePath() + "/").startsWith(f.getAbsolutePath() + "/")) {
                toast("Can't put a folder inside itself");
                return;
            }
        }
        final List<File> sources = new ArrayList<>(clip);
        final boolean cut = clipIsCut;
        clip.clear();
        pasteButton.setVisibility(View.GONE);
        runJob(cut ? "Moving…" : "Copying…", new Job() {
            @Override
            public String run(Progress p) throws IOException {
                for (File src : sources) {
                    File dst = freeName(destDir, src.getName());
                    if (cut && src.renameTo(dst)) continue;
                    copy(src, dst, p);
                    if (cut && !deleteAll(src)) return "Copied, but couldn't remove the original";
                }
                return cut ? "Moved" : "Copied";
            }
        });
    }

    private void askNewFolder() {
        if (current == null) return;
        if (!current.canWrite()) { toast(readOnlyMessage()); return; }
        askText("New folder", "Folder name", "", new TextResult() {
            @Override public void done(String name) {
                File f = new File(current, name);
                if (f.exists()) toast("That name is already used");
                else if (f.mkdirs()) open(current, currentPlace);
                else toast("Couldn't create the folder");
            }
        });
    }

    private void askRename(final File f) {
        askText("Rename", "New name", f.getName(), new TextResult() {
            @Override public void done(String name) {
                File to = new File(f.getParentFile(), name);
                if (to.exists()) toast("That name is already used");
                else if (f.renameTo(to)) open(current, currentPlace);
                else toast(f.getParentFile() != null && !f.getParentFile().canWrite() ? readOnlyMessage() : "Couldn't rename");
            }
        });
    }

    private void askDelete(final File f) {
        showGlass(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Delete " + f.getName() + "?")
                .setMessage(f.isDirectory() ? "The folder and everything inside it will be removed." : "This can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        runJob("Deleting…", new Job() {
                            @Override public String run(Progress p) {
                                return deleteAll(f) ? "Deleted" : (f.getParentFile() != null && !f.getParentFile().canWrite()
                                        ? readOnlyMessage() : "Some files couldn't be deleted");
                            }
                        });
                    }
                }).create());
    }

    private void showDetails(Item it) {
        String msg = "Location: " + it.file.getParent()
                + "\nModified: " + DateFormat.getMediumDateFormat(this).format(new Date(it.modified))
                + " " + DateFormat.getTimeFormat(this).format(new Date(it.modified))
                + (it.dir ? "\nItems: " + it.children : "\nSize: " + Formatter.formatFileSize(this, it.size)
                + "\nType: " + FileShare.mimeOf(it.file));
        showGlass(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(it.name).setMessage(msg).setPositiveButton("OK", null).create());
    }

    private String readOnlyMessage() {
        return "Apps can only read this disk on Android 9. Copy files to Internal storage to change them.";
    }

    // ------------------------------------------------------------------ background jobs

    private interface Progress { void update(String text); }
    private interface Job { String run(Progress p) throws IOException; }
    private interface TextResult { void done(String text); }

    private void runJob(String title, final Job job) {
        final AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title).setMessage("Starting…").setCancelable(false).create();
        showGlass(d);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    result = job.run(new Progress() {
                        @Override public void update(final String text) {
                            ui.post(new Runnable() { @Override public void run() { d.setMessage(text); } });
                        }
                    });
                } catch (IOException e) {
                    result = "Failed: " + e.getMessage();
                }
                final String done = result;
                ui.post(new Runnable() {
                    @Override public void run() {
                        try { d.dismiss(); } catch (Exception ignored) { }
                        toast(done);
                        if (current != null) open(current, currentPlace);
                        renderPlaces();
                    }
                });
            }
        }, "wowfiles-job").start();
    }

    private static File freeName(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; ; n++) {
            f = new File(dir, base + " (" + n + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private void copy(File src, File dst, Progress p) throws IOException {
        if (src.isDirectory()) {
            if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("can't create " + dst.getName());
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copy(k, new File(dst, k.getName()), p);
            return;
        }
        long total = src.length();
        p.update(src.getName() + "\n0 of " + Formatter.formatShortFileSize(this, total));
        byte[] buf = new byte[256 * 1024];
        long done = 0;
        long lastUi = 0;
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 400) {
                        lastUi = now;
                        p.update(src.getName() + "\n" + Formatter.formatShortFileSize(this, done)
                                + " of " + Formatter.formatShortFileSize(this, total));
                    }
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        dst.setLastModified(src.lastModified());
    }

    private static boolean deleteAll(File f) {
        boolean ok = true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) ok &= deleteAll(k);
        }
        return f.delete() && ok;
    }

    // ------------------------------------------------------------------ UI helpers

    private void askText(String title, String hint, String initial, final TextResult result) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(hint);
        input.setText(initial);
        int dot = initial.lastIndexOf('.');
        input.setSelection(0, dot > 0 ? dot : initial.length());
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        showGlass(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title).setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty() || name.contains("/")) toast("Enter a valid name");
                        else result.done(name);
                    }
                }).create());
    }

    private void showGlass(AlertDialog d) {
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(R.drawable.dialog_glass);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static boolean same(File a, File b) {
        return a != null && b != null && a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    private static int iconFor(Item it) {
        if (it.dir) return R.drawable.ic_folder;
        String n = it.name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        if (VIDEO.contains(ext)) return R.drawable.ic_video;
        if (AUDIO.contains(ext)) return R.drawable.ic_music;
        if (IMAGE.contains(ext)) return R.drawable.ic_image;
        if (DOC.contains(ext)) return R.drawable.ic_doc;
        if (ext.equals("apk")) return R.drawable.ic_apk;
        return R.drawable.ic_file;
    }

    private final class FileAdapter extends BaseAdapter {
        private final List<Item> items = new ArrayList<>();

        void set(List<Item> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Item getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = inflater.inflate(R.layout.item_file, parent, false);
            Item it = items.get(pos);
            ((ImageView) v.findViewById(R.id.icon)).setImageResource(iconFor(it));
            ((TextView) v.findViewById(R.id.name)).setText(it.name);
            String date = DateFormat.getMediumDateFormat(FilesActivity.this).format(new Date(it.modified));
            String meta = it.dir
                    ? (it.children == 1 ? "1 item" : it.children + " items") + " · " + date
                    : Formatter.formatShortFileSize(FilesActivity.this, it.size) + " · " + date;
            ((TextView) v.findViewById(R.id.meta)).setText(meta);
            return v;
        }
    }
}
