package com.spirit.koil.api.registry.dev;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Generates the committed full-field fixture for the replacement Content registry format. */
public final class ContentTestDatapackGenerator {
    public static final String PACK_DIRECTORY = "koil-content-registry-test";
    public static final String NAMESPACE = "koil_registry_test";

    private ContentTestDatapackGenerator() {
    }

    public static void main(String[] arguments) throws Exception {
        Path parent = arguments.length == 0 ? Path.of("docs", "test-datapacks") : Path.of(arguments[0]);
        Path pack = generate(parent);
        System.out.println(pack.toAbsolutePath().normalize());
    }

    public static Path generate(Path datapacksDirectory) throws IOException {
        Path pack = datapacksDirectory.resolve(PACK_DIRECTORY);
        write(pack.resolve("pack.mcmeta"), packMetadata());
        write(pack.resolve("README.md"), readme());

        write(pack.resolve("data/" + NAMESPACE + "/registry/items/ruby.json"), normalItemDefinition());
        write(pack.resolve("data/" + NAMESPACE + "/registry/items/ruby_sword.json"), toolItemDefinition());
        write(pack.resolve("data/" + NAMESPACE + "/registry/blocks/ruby_block.json"), blockDefinition());

        write(pack.resolve("assets/" + NAMESPACE + "/lang/en_us.json"), language());
        write(pack.resolve("assets/" + NAMESPACE + "/models/item/ruby.json"), generatedItemModel("ruby"));
        write(pack.resolve("assets/" + NAMESPACE + "/models/item/ruby_sword.json"), handheldItemModel("ruby_sword"));
        write(pack.resolve("assets/" + NAMESPACE + "/models/item/ruby_block.json"), blockItemModel());
        write(pack.resolve("assets/" + NAMESPACE + "/models/block/ruby_block.json"), cubeBlockModel());
        write(pack.resolve("assets/" + NAMESPACE + "/blockstates/ruby_block.json"), blockstateAsset());

        write(pack.resolve("data/" + NAMESPACE + "/recipes/ruby_sword.json"), swordRecipe());
        write(pack.resolve("data/" + NAMESPACE + "/recipes/ruby_block.json"), blockRecipe());
        write(pack.resolve("data/" + NAMESPACE + "/loot_tables/blocks/ruby_block.json"), blockLootTable());
        write(pack.resolve("data/" + NAMESPACE + "/tags/items/rubies.json"), itemTag());
        write(pack.resolve("data/" + NAMESPACE + "/tags/blocks/ruby_blocks.json"), blockTag());
        write(pack.resolve("data/minecraft/tags/items/swords.json"), swordTag());
        write(pack.resolve("data/minecraft/tags/blocks/mineable/pickaxe.json"), pickaxeMineableTag());
        write(pack.resolve("data/minecraft/tags/blocks/needs_diamond_tool.json"), diamondToolTag());

        writeTexture(pack.resolve("assets/" + NAMESPACE + "/textures/item/ruby.png"), TextureKind.RUBY);
        writeTexture(pack.resolve("assets/" + NAMESPACE + "/textures/item/ruby_sword.png"), TextureKind.SWORD);
        writeTexture(pack.resolve("assets/" + NAMESPACE + "/textures/block/ruby_block.png"), TextureKind.BLOCK);
        return pack;
    }

    private static void write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                contents.stripIndent().stripLeading(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    private static void writeTexture(Path path, TextureKind kind) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, kind.color(x, y));
            }
        }
        if (!ImageIO.write(image, "png", path.toFile())) {
            throw new IOException("No PNG writer available for " + path);
        }
    }

    private static String packMetadata() {
        return """
                {
                  "pack": {
                    "pack_format": 15,
                    "description": "Koil Content Registry full-field test datapack"
                  },
                  "koil:registry": {
                    "name": "Koil Content Registry Test",
                    "version": "1.0.0",
                    "minecraft": {
                      "minimum": "1.20.1",
                      "maximum_exclusive": "1.21"
                    },
                    "dependencies": [],
                    "mods": ["koil"]
                  }
                }
                """;
    }

    private static String readme() {
        return """
                # Koil Content Registry Test Datapack

                Generated by `ContentTestDatapackGenerator`.

                Copy this folder into `<world>/datapacks/`, enable it for that world, and run a Content scan/reload.
                The three authoritative definitions are:

                - normal item: `koil_registry_test:ruby`
                - tool item: `koil_registry_test:ruby_sword`
                - block: `koil_registry_test:ruby_block`

                The bundled `assets/` tree is loaded in place by Koil's active-world resource bridge; vanilla does not load client assets from an ordinary server datapack by itself.
                Recipes, tags, and loot target the early-registered world-scoped Ruby holders. Adding a brand-new physical id after game startup still requires a restart.
                """;
    }

    private static String normalItemDefinition() {
        return """
                {
                  "schema_version": 1,
                  "id": "ruby",
                  "type": "item",
                  "namespace": "koil_registry_test",
                  "display": {
                    "name": "Ruby",
                    "lang_key": "item.koil_registry_test.ruby",
                    "lore": ["A full-field normal item fixture"]
                  },
                  "behavior": {
                    "profile": "normal",
                    "use_action": "none",
                    "cooldown_ticks": 0
                  },
                  "properties": {
                    "max_count": 64,
                    "durability": 0,
                    "rarity": "rare",
                    "fireproof": false,
                    "enchantability": 0,
                    "repair_ingredient": "minecraft:air",
                    "recipe_remainder": "minecraft:air"
                  },
                  "components": {
                    "minecraft:custom_data": {
                      "fixture": "normal_item",
                      "gem_quality": 100
                    }
                  },
                  "tags": ["koil_registry_test:rubies"],
                  "creative": {
                    "tab": "minecraft:ingredients",
                    "order": 10,
                    "hidden": false,
                    "developer_only": false,
                    "world_only": true
                  },
                  "assets": {
                    "model": "koil_registry_test:item/ruby",
                    "texture": "koil_registry_test:item/ruby"
                  },
                  "recipes": ["koil_registry_test:ruby_block"],
                  "loot": {},
                  "compatibility": {
                    "minecraft": {"minimum": "1.20.1", "maximum_exclusive": "1.21"},
                    "required_mods": ["koil"],
                    "optional_mods": []
                  },
                  "metadata": {
                    "author": "Koil",
                    "version": "1.0.0",
                    "test_fixture": true
                  },
                  "extensions": {
                    "koil:test": {"coverage": "all-common-item-fields"},
                    "examplemod:preserved": {"unknown_number": 42, "unknown_flag": true}
                  }
                }
                """;
    }

    private static String toolItemDefinition() {
        return """
                {
                  "schema_version": 1,
                  "id": "ruby_sword",
                  "type": "item",
                  "namespace": "koil_registry_test",
                  "display": {
                    "name": "Ruby Sword",
                    "lang_key": "item.koil_registry_test.ruby_sword",
                    "lore": ["A full-field tool and weapon fixture"]
                  },
                  "behavior": {
                    "profile": "sword",
                    "use_action": "block",
                    "cooldown_ticks": 8
                  },
                  "properties": {
                    "max_count": 1,
                    "durability": 2031,
                    "rarity": "epic",
                    "fireproof": true,
                    "enchantability": 22,
                    "repair_ingredient": "koil_registry_test:ruby",
                    "recipe_remainder": "minecraft:air",
                    "tool": {
                      "tier": "diamond",
                      "mining_speed": 9.0,
                      "mining_level": 3,
                      "effective_tags": ["minecraft:mineable/sword"]
                    },
                    "weapon": {
                      "attack_damage": 9.0,
                      "attack_speed": -2.2,
                      "knockback": 0.5
                    }
                  },
                  "components": {
                    "minecraft:custom_data": {
                      "fixture": "tool_item",
                      "charged": false
                    }
                  },
                  "tags": ["minecraft:swords", "minecraft:enchantable/sword"],
                  "creative": {
                    "tab": "minecraft:combat",
                    "order": 20,
                    "hidden": false,
                    "developer_only": false,
                    "world_only": true
                  },
                  "assets": {
                    "model": "koil_registry_test:item/ruby_sword",
                    "texture": "koil_registry_test:item/ruby_sword"
                  },
                  "recipes": ["koil_registry_test:ruby_sword"],
                  "compatibility": {
                    "minecraft": {"minimum": "1.20.1", "maximum_exclusive": "1.21"},
                    "required_mods": ["koil"],
                    "optional_mods": []
                  },
                  "metadata": {
                    "author": "Koil",
                    "version": "1.0.0",
                    "test_fixture": true
                  },
                  "extensions": {
                    "koil:test": {"coverage": "all-tool-and-weapon-fields"},
                    "examplemod:combat": {"combo_window_ticks": 12}
                  }
                }
                """;
    }

    private static String blockDefinition() {
        return """
                {
                  "schema_version": 1,
                  "id": "ruby_block",
                  "type": "block",
                  "namespace": "koil_registry_test",
                  "display": {
                    "name": "Ruby Block",
                    "lang_key": "block.koil_registry_test.ruby_block",
                    "lore": ["A full-field block and blockstate fixture"]
                  },
                  "behavior": {
                    "profile": "stateful_block",
                    "redstone": {
                      "emits_power": true,
                      "weak_power": 7,
                      "strong_power": 3
                    },
                    "state_interactions": {
                      "cycle_on_use": ["lit", "charge", "mode"]
                    },
                    "waterloggable": true,
                    "random_ticks": true,
                    "block_entity": "koil_registry_test:ruby_block_entity"
                  },
                  "properties": {
                    "copy_from": "minecraft:diamond_block",
                    "hardness": 7.5,
                    "resistance": 12.0,
                    "luminance": 7,
                    "slipperiness": 0.72,
                    "velocity_multiplier": 1.05,
                    "jump_velocity_multiplier": 1.1,
                    "collision": true,
                    "solid": true,
                    "full_cube": true,
                    "opaque": false,
                    "transparent": false,
                    "translucent": true,
                    "map_color": "red",
                    "instrument": "pling",
                    "sound_group": "amethyst_block",
                    "requires_tool": true,
                    "drops": "koil_registry_test:blocks/ruby_block",
                    "piston_behavior": "normal",
                    "burnable": false,
                    "replaceable": false
                  },
                  "blockstates": {
                    "properties": [
                      {"name": "lit", "type": "boolean", "allowed_values": [false, true], "default": false},
                      {"name": "facing", "type": "direction", "allowed_values": ["north", "east", "south", "west"], "default": "north"},
                      {"name": "charge", "type": "integer", "allowed_values": [0, 1, 2, 3], "minimum": 0, "maximum": 3, "default": 0},
                      {"name": "waterlogged", "type": "boolean", "allowed_values": [false, true], "default": false},
                      {"name": "powered", "type": "boolean", "allowed_values": [false, true], "default": false},
                      {"name": "mode", "type": "enum", "allowed_values": ["idle", "charged"], "default": "idle"},
                      {"name": "examplemod:phase", "type": "custom", "allowed_values": ["solid", "shifting"], "default": "solid"}
                    ],
                    "variants": {
                      "lit=false": {"model": "koil_registry_test:block/ruby_block"},
                      "lit=true": {"model": "koil_registry_test:block/ruby_block", "y": 90, "uvlock": true}
                    },
                    "multipart": [
                      {
                        "when": {"charge": "3"},
                        "apply": {"model": "koil_registry_test:block/ruby_block", "x": 90}
                      }
                    ],
                    "behavior_mapping": {
                      "lit=true": {"luminance": 15},
                      "charge=3": {"redstone.weak_power": 15}
                    },
                    "unknown_values": {"examplemod:preserved_state": "keep-me"}
                  },
                  "components": {
                    "minecraft:custom_data": {"fixture": "block", "stored_charge": 0}
                  },
                  "tags": ["minecraft:mineable/pickaxe", "minecraft:needs_diamond_tool", "koil_registry_test:ruby_blocks"],
                  "creative": {
                    "tab": "minecraft:building_blocks",
                    "order": 30,
                    "hidden": false,
                    "developer_only": false,
                    "world_only": true
                  },
                  "assets": {
                    "blockstate": "koil_registry_test:ruby_block",
                    "block_model": "koil_registry_test:block/ruby_block",
                    "item_model": "koil_registry_test:item/ruby_block",
                    "texture": "koil_registry_test:block/ruby_block"
                  },
                  "loot": {"table": "koil_registry_test:blocks/ruby_block"},
                  "recipes": ["koil_registry_test:ruby_block"],
                  "compatibility": {
                    "minecraft": {"minimum": "1.20.1", "maximum_exclusive": "1.21"},
                    "required_mods": ["koil"],
                    "optional_mods": []
                  },
                  "metadata": {
                    "author": "Koil",
                    "version": "1.0.0",
                    "test_fixture": true
                  },
                  "examplemod:root_preserved": {
                    "future_runtime_value": 73,
                    "nested": {"keep": true}
                  },
                  "extensions": {
                    "koil:test": {"coverage": "all-block-and-blockstate-fields"},
                    "examplemod:machine": {"energy_capacity": 10000}
                  }
                }
                """;
    }

    private static String language() {
        return """
                {
                  "item.koil_registry_test.ruby": "Ruby",
                  "item.koil_registry_test.ruby_sword": "Ruby Sword",
                  "block.koil_registry_test.ruby_block": "Ruby Block"
                }
                """;
    }

    private static String generatedItemModel(String name) {
        return """
                {
                  "parent": "minecraft:item/generated",
                  "textures": {"layer0": "koil_registry_test:item/%s"}
                }
                """.formatted(name);
    }

    private static String handheldItemModel(String name) {
        return """
                {
                  "parent": "minecraft:item/handheld",
                  "textures": {"layer0": "koil_registry_test:item/%s"}
                }
                """.formatted(name);
    }

    private static String blockItemModel() {
        return """
                {"parent": "koil_registry_test:block/ruby_block"}
                """;
    }

    private static String cubeBlockModel() {
        return """
                {
                  "parent": "minecraft:block/cube_all",
                  "textures": {"all": "koil_registry_test:block/ruby_block"}
                }
                """;
    }

    private static String blockstateAsset() {
        return """
                {
                  "multipart": [
                    {"apply": {"model": "koil_registry_test:block/ruby_block"}}
                  ]
                }
                """;
    }

    private static String swordRecipe() {
        return """
                {
                  "type": "minecraft:crafting_shaped",
                  "pattern": [" R ", " R ", " S "],
                  "key": {
                    "R": {"item": "koil_registry_test:ruby"},
                    "S": {"item": "minecraft:stick"}
                  },
                  "result": {"item": "koil_registry_test:ruby_sword", "count": 1}
                }
                """;
    }

    private static String blockRecipe() {
        return """
                {
                  "type": "minecraft:crafting_shaped",
                  "pattern": ["RRR", "RRR", "RRR"],
                  "key": {"R": {"item": "koil_registry_test:ruby"}},
                  "result": {"item": "koil_registry_test:ruby_block", "count": 1}
                }
                """;
    }

    private static String blockLootTable() {
        return """
                {
                  "type": "minecraft:block",
                  "pools": [{
                    "rolls": 1,
                    "entries": [{"type": "minecraft:item", "name": "koil_registry_test:ruby_block"}],
                    "conditions": [{"condition": "minecraft:survives_explosion"}]
                  }]
                }
                """;
    }

    private static String itemTag() {
        return """
                {"replace": false, "values": ["koil_registry_test:ruby"]}
                """;
    }

    private static String blockTag() {
        return """
                {"replace": false, "values": ["koil_registry_test:ruby_block"]}
                """;
    }

    private static String swordTag() {
        return """
                {"replace": false, "values": ["koil_registry_test:ruby_sword"]}
                """;
    }

    private static String pickaxeMineableTag() {
        return """
                {"replace": false, "values": ["koil_registry_test:ruby_block"]}
                """;
    }

    private static String diamondToolTag() {
        return """
                {"replace": false, "values": ["koil_registry_test:ruby_block"]}
                """;
    }

    private enum TextureKind {
        RUBY {
            @Override
            int color(int x, int y) {
                if (x < 3 || x > 12 || y < 2 || y > 13 || Math.abs(x - 7) + Math.abs(y - 7) > 10) return 0x00000000;
                return ((x + y) & 1) == 0 ? 0xFFFF315A : 0xFFB80F36;
            }
        },
        SWORD {
            @Override
            int color(int x, int y) {
                if (x + y >= 13 && x + y <= 16 && x >= 3 && y >= 1) return 0xFFFF315A;
                if (x + y >= 15 && x + y <= 18 && x <= 11 && y <= 12) return 0xFFC9A46C;
                return 0x00000000;
            }
        },
        BLOCK {
            @Override
            int color(int x, int y) {
                int base = ((x / 4) + (y / 4)) % 2 == 0 ? 0xFFB80F36 : 0xFFE62552;
                return (x == 0 || y == 0 || x == 15 || y == 15) ? 0xFF6F0922 : base;
            }
        };

        abstract int color(int x, int y);
    }
}
