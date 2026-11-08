package com.aetherport.aether.content;

/** Definition of an Aether item. */
public record ItemDef(
        String id,
        String kind,
        String model,
        Integer durability,
        int maxStack,
        String langEn,
        String langRu) {
}
