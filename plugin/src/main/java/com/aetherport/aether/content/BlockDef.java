package com.aetherport.aether.content;

import java.util.List;

/** Definition of an Aether block: carrier state, mining properties, drops. */
public record BlockDef(
        String id,
        String carrier,
        double hardness,
        double resistance,
        String sound,
        boolean requiresTool,
        String tool,
        int requiredTier,
        List<Drop> drops,
        boolean doubleDrops,
        String model,
        String langEn,
        String langRu) {

    public record Drop(String item, boolean vanilla, int min, int max, double chance,
                       java.util.List<Double> fortune, String require, boolean exclude, String bonus) {
    }
}
