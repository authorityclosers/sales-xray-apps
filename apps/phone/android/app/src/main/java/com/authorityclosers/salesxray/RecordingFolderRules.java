package com.authorityclosers.salesxray;

import java.util.List;
import java.util.Locale;

/**
 * The rules for a recordings folder the seller picked (S8, 23 Oct 2026): the
 * Play build has no All files access, so on Xiaomi and HyperOS, whose call
 * recordings never reach Android's media index, the seller picks the folder
 * once and Sales X-Ray reads it through that grant. Plain Java, for JVM tests.
 */
final class RecordingFolderRules {

    /** How far below the picked folder recordings are read (the folder's own files are 1). */
    static final int DEPTH = 3;

    private RecordingFolderRules() {}

    /** "primary:MIUI/sound_recorder/call_rec" is "MIUI/sound_recorder/call_rec"; other volumes keep their id. */
    static String path(String documentId) {
        if (documentId == null) return "";
        int colon = documentId.indexOf(':');
        if (colon < 0) return trim(documentId);
        String volume = documentId.substring(0, colon);
        String rest = trim(documentId.substring(colon + 1));
        return "primary".equals(volume) ? rest : volume + ":" + rest;
    }

    /** A picked folder covers a recorder folder when it is that folder, or holds it close enough to be read. */
    static boolean covers(String picked, String folder) {
        String outer = trim(picked).toLowerCase(Locale.ROOT);
        String inner = trim(folder).toLowerCase(Locale.ROOT);
        if (outer.isEmpty() || inner.isEmpty()) return false;
        if (outer.equals(inner)) return true;
        if (!inner.startsWith(outer + "/")) return false;
        return inner.substring(outer.length() + 1).split("/").length < DEPTH;
    }

    static boolean coversAny(List<String> picked, String[] folders) {
        if (picked == null || folders == null) return false;
        for (String one : picked) {
            for (String folder : folders) if (covers(one, folder)) return true;
        }
        return false;
    }

    /** A document inside the picked folder (by its provider id), no more than DEPTH levels down. */
    static boolean inside(String treeId, String documentId) {
        if (treeId == null || documentId == null || treeId.isEmpty()) return false;
        String root = treeId.endsWith("/") ? treeId : treeId + "/";
        if (!documentId.startsWith(root)) return false;
        String rest = documentId.substring(root.length());
        if (rest.isEmpty()) return false;
        String[] parts = rest.split("/", -1);
        if (parts.length > DEPTH) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    private static String trim(String path) {
        return path.replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
