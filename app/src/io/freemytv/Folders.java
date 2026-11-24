package io.freemytv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The folders of All Apps: a name and the packages in it (an app is in one folder at most), kept in Prefs. */
final class Folders {
    private Folders() {}

    static final class Folder {
        String name;
        final List<String> pkgs = new ArrayList<>();
    }

    static List<Folder> load() {
        List<Folder> out = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(Prefs.folders());
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                Folder f = new Folder();
                f.name = o.getString("name");
                JSONArray apps = o.getJSONArray("apps");
                for (int j = 0; j < apps.length(); j++) f.pkgs.add(apps.getString(j));
                out.add(f);
            }
        } catch (JSONException ignored) {
            // nothing saved yet (or unreadable): no folders
        }
        return out;
    }

    private static void save(List<Folder> folders) {
        JSONArray all = new JSONArray();
        try {
            for (Folder f : folders) {
                if (f.pkgs.isEmpty()) continue;   // a folder with nothing in it goes away
                all.put(new JSONObject().put("name", f.name).put("apps", new JSONArray(f.pkgs)));
            }
        } catch (JSONException ignored) {
        }
        Prefs.setFolders(all.toString());
    }

    static Folder find(List<Folder> folders, String name) {
        for (Folder f : folders) if (f.name.equals(name)) return f;
        return null;
    }

    static String folderOf(String pkg) {
        for (Folder f : load()) if (f.pkgs.contains(pkg)) return f.name;
        return null;
    }

    /** Puts the app in the folder (made if it isn't there), out of any other. */
    static void put(String pkg, String name) {
        List<Folder> folders = load();
        for (Folder f : folders) f.pkgs.remove(pkg);
        Folder target = find(folders, name);
        if (target == null) {
            target = new Folder();
            target.name = name;
            folders.add(target);
        }
        target.pkgs.add(pkg);
        save(folders);
    }

    static void take(String pkg) {
        List<Folder> folders = load();
        for (Folder f : folders) f.pkgs.remove(pkg);
        save(folders);
    }

    /** False if another folder already has the name. */
    static boolean rename(String from, String to) {
        List<Folder> folders = load();
        if (!from.equals(to) && find(folders, to) != null) return false;
        Folder f = find(folders, from);
        if (f != null) f.name = to;
        save(folders);
        return true;
    }

    static void delete(String name) {
        List<Folder> folders = load();
        Folder f = find(folders, name);
        if (f != null) f.pkgs.clear();
        save(folders);
    }
}
