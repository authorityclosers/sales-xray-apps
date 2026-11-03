package com.authorityclosers.salesxray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which files a recordings pick hands the page (3 Nov 2026 regression, U1: ticking several files in the
 * picker added none). A multiple pick answers with its files as clip items and no single file, so
 * reading only the single file gave the page nothing. Plain Java, for JVM tests; RecordingPicker reads
 * the picker's answer.
 */
final class PickRules {

    private PickRules() {}

    /** The clip items when there are any (blanks left out), else the single file, else none. */
    static <T> List<T> files(List<T> clipItems, T single) {
        List<T> files = new ArrayList<>();
        if (clipItems != null) {
            for (T item : clipItems) if (item != null) files.add(item);
        }
        if (!files.isEmpty()) return files;
        return single == null ? Collections.<T>emptyList() : Collections.singletonList(single);
    }
}
