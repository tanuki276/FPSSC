package com.tanuki276.fpssc;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent practice-profile selection shared by setup UI and the compact overlay. */
final class PracticeProfileStore {
    private static final String PREFS = "fscsr_practice";
    private static final String KEY_PROFILE = "selected_brawler_profile";

    private PracticeProfileStore() {}

    static BrawlerProfile selected(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return BrawlerProfile.byId(prefs.getString(KEY_PROFILE, BrawlerProfile.DEFAULT_ID));
    }

    static BrawlerProfile select(Context context, String id) {
        BrawlerProfile profile = BrawlerProfile.byId(id);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PROFILE, profile.id()).apply();
        return profile;
    }

    static BrawlerProfile step(Context context, int delta) {
        BrawlerProfile current = selected(context);
        int size = BrawlerProfile.all().size();
        int index = BrawlerProfile.indexOf(current.id());
        int next = ((index + delta) % size + size) % size;
        return select(context, BrawlerProfile.idAt(next));
    }

    static String compactLabel(Context context) {
        BrawlerProfile p = selected(context);
        String label = p.label();
        if (label.length() > 10) label = label.substring(0, 10);
        return "キャラ: " + label;
    }
}
