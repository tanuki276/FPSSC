package com.tanuki276.fpssc;

import java.util.List;

/**
 * Coarse combat tuning in screen-height units. These are intentionally gameplay-oriented
 * profiles rather than claims about exact live-game tile values, which can change by patch.
 */
record BrawlerProfile(
        String id,
        String label,
        AttackMode attackMode,
        float attackRange,
        float projectileSpeed,
        float attackWidth,
        float preferredMinRange,
        float preferredMaxRange,
        int ammoCapacity,
        float reloadSeconds,
        float superMinRange,
        float superRange,
        float fireThreshold,
        float bushMemorySeconds
) {
    enum AttackMode { MELEE, PROJECTILE, SPREAD, THROWER, DASH }

    static final String DEFAULT_ID = "balanced";

    private static final List<BrawlerProfile> PROFILES = List.of(
            new BrawlerProfile("balanced", "汎用・中距離", AttackMode.PROJECTILE,
                    .48f, 1.85f, .055f, .24f, .42f, 3, 1.65f, .16f, .50f, .50f, 1.55f),
            new BrawlerProfile("el_primo", "エル・プリモ", AttackMode.MELEE,
                    .19f, 0f, .105f, .055f, .15f, 3, .85f, .20f, .43f, .28f, 1.85f),
            new BrawlerProfile("shelly", "シェリー", AttackMode.SPREAD,
                    .34f, 2.05f, .115f, .13f, .27f, 3, 1.50f, .08f, .31f, .39f, 1.55f),
            new BrawlerProfile("colt", "コルト", AttackMode.PROJECTILE,
                    .53f, 2.55f, .045f, .30f, .47f, 3, 1.70f, .18f, .54f, .54f, 1.55f),
            new BrawlerProfile("bull", "ブル", AttackMode.SPREAD,
                    .26f, 1.95f, .125f, .08f, .20f, 3, 1.60f, .22f, .56f, .35f, 1.75f),
            new BrawlerProfile("brock", "ブロック", AttackMode.PROJECTILE,
                    .59f, 2.35f, .045f, .36f, .53f, 3, 2.05f, .18f, .59f, .55f, 1.45f),
            new BrawlerProfile("piper", "エリザベス/パイパー系", AttackMode.PROJECTILE,
                    .63f, 2.80f, .035f, .44f, .59f, 3, 2.25f, .10f, .42f, .60f, 1.35f),
            new BrawlerProfile("poco", "ポコ", AttackMode.SPREAD,
                    .43f, 2.00f, .130f, .19f, .36f, 3, 1.60f, .05f, .36f, .36f, 1.60f),
            new BrawlerProfile("mortis", "モーティス系ダッシュ", AttackMode.DASH,
                    .16f, 0f, .095f, .04f, .14f, 3, 2.25f, .04f, .24f, .28f, 1.65f),
            new BrawlerProfile("dynamike", "ダイナマイク系投擲", AttackMode.THROWER,
                    .50f, 1.15f, .075f, .30f, .46f, 3, 1.70f, .13f, .50f, .53f, 1.35f),
            new BrawlerProfile("melee", "汎用・近接", AttackMode.MELEE,
                    .18f, 0f, .10f, .05f, .15f, 3, 1.00f, .17f, .40f, .30f, 1.80f),
            new BrawlerProfile("shotgun", "汎用・ショットガン", AttackMode.SPREAD,
                    .30f, 1.90f, .12f, .10f, .23f, 3, 1.55f, .10f, .34f, .38f, 1.65f),
            new BrawlerProfile("long_range", "汎用・長距離", AttackMode.PROJECTILE,
                    .60f, 2.55f, .04f, .38f, .55f, 3, 2.00f, .18f, .60f, .56f, 1.40f),
            new BrawlerProfile("thrower", "汎用・投擲", AttackMode.THROWER,
                    .49f, 1.10f, .075f, .29f, .45f, 3, 1.75f, .12f, .49f, .53f, 1.35f)
    );

    BrawlerProfile {
        if (id == null || id.isBlank() || label == null || label.isBlank() || attackMode == null)
            throw new IllegalArgumentException("profile identity required");
        if (attackRange <= 0 || attackRange > .8f || attackWidth <= 0 || attackWidth > .25f)
            throw new IllegalArgumentException("invalid attack geometry");
        if (attackMode != AttackMode.MELEE && attackMode != AttackMode.DASH && projectileSpeed <= 0)
            throw new IllegalArgumentException("projectile profile requires speed");
        if (ammoCapacity < 1 || reloadSeconds <= 0 || preferredMinRange < 0
                || preferredMaxRange < preferredMinRange || superRange < superMinRange)
            throw new IllegalArgumentException("invalid combat tuning");
    }

    boolean meleeLike() { return attackMode == AttackMode.MELEE || attackMode == AttackMode.DASH; }
    boolean thrower() { return attackMode == AttackMode.THROWER; }

    static List<BrawlerProfile> all() { return PROFILES; }

    static BrawlerProfile byId(String id) {
        if (id != null) for (BrawlerProfile profile : PROFILES) if (profile.id().equals(id)) return profile;
        return PROFILES.get(0);
    }

    static String[] labels() {
        String[] out = new String[PROFILES.size()];
        for (int i = 0; i < out.length; i++) out[i] = PROFILES.get(i).label();
        return out;
    }

    static String idAt(int index) {
        return PROFILES.get(Math.max(0, Math.min(PROFILES.size() - 1, index))).id();
    }

    static int indexOf(String id) {
        for (int i = 0; i < PROFILES.size(); i++) if (PROFILES.get(i).id().equals(id)) return i;
        return 0;
    }
}
