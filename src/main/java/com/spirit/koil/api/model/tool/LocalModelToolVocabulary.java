package com.spirit.koil.api.model.tool;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One-word intent vocabulary for local-model tool routing.
 *
 * <p>Entries in this class are deliberately single lexical tokens. Context is
 * composed by {@link LocalModelToolCatalog} from multiple independent words
 * instead of storing sentence fragments or phrase templates. This keeps prompt
 * matching cheap, makes slang/abbreviations easy to extend, and avoids a large
 * phrase table that small models can miss.</p>
 */
final class LocalModelToolVocabulary {
    static final String VERSION = "tool-word-vocabulary-v1";

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9_]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMESPACED_ID = Pattern.compile("(?:^|\\s)[a-z0-9_.-]+:[a-z0-9_./-]+(?:$|\\s)");
    private static final Pattern URL = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);

    static final Set<String> WALK = words(
            "walk", "walking", "walked", "walks", "step", "steps", "stepping", "stroll", "strolling",
            "pace", "pacing", "forward", "forwards", "ahead", "back", "backward", "backwards", "reverse",
            "left", "right", "strafe", "strafing", "sidestep", "sidestepping"
    );
    static final Set<String> NAVIGATE = words(
            "move", "moving", "navigate", "navigating", "navigation", "travel", "traveling", "travelling",
            "route", "routing", "path", "pathing", "pathfind", "pathfinding", "goto", "head", "reach",
            "approach", "approaching", "destination", "waypoint", "waypoints", "coordinate", "coordinates",
            "coord", "coords", "position", "location", "target"
    );
    static final Set<String> JUMP = words(
            "jump", "jumping", "jumped", "jumps", "hop", "hopping", "hopped", "leap", "leaping", "leapt",
            "vault", "vaulting", "vaulted", "bounce", "bouncing"
    );

    static final Set<String> TAP = words(
            "tap", "tapping", "tapped", "press", "pressing", "pressed", "click", "clicking", "clicked",
            "keystroke", "keystrokes", "hotkey", "hotkeys", "input", "inputs", "key", "keys", "button", "buttons"
    );
    static final Set<String> HOLD = words(
            "hold", "holding", "held", "sustain", "sustaining", "keep", "keeping"
    );
    static final Set<String> RELEASE = words(
            "release", "releasing", "released", "unpress", "unpressed", "keyup", "letgo"
    );
    static final Set<String> INPUT_KEYS = words(
            "w", "a", "s", "d", "e", "t", "q", "f", "r", "space", "shift", "ctrl", "control", "alt",
            "enter", "return", "tab", "escape", "esc", "backspace", "delete", "up", "down", "left", "right",
            "forward", "back", "backward", "jump", "sneak", "crouch", "sprint", "attack", "use", "inventory",
            "chat", "swap", "swap_hands", "drop", "pick", "pick_block", "perspective", "third_person", "camera",
            "leftclick", "rightclick", "middleclick", "left_click", "right_click", "middle_click",
            "left_shift", "left_ctrl", "left_arrow", "right_arrow", "page_up", "page_down",
            "lmb", "rmb", "mmb", "mouse1", "mouse2", "mouse3"
    );
    static final Set<String> MOUSE = words(
            "mouse", "cursor", "pointer", "lmb", "rmb", "mmb", "mouse1", "mouse2", "mouse3",
            "leftclick", "rightclick", "middleclick", "left_click", "right_click", "middle_click"
    );
    static final Set<String> CAMERA = words(
            "camera", "view", "look", "looking", "aim", "aiming", "yaw", "pitch", "delta", "rotate", "rotating",
            "turn", "turning", "pan", "panning", "angle", "angles", "orientation"
    );

    static final Set<String> LOOK = words(
            "look", "looking", "face", "facing", "aim", "aiming", "target", "targeting", "turn", "turning"
    );
    static final Set<String> INTERACT = words(
            "interact", "interacting", "interaction", "use", "using", "activate", "activating", "trigger", "triggering",
            "click", "clicking", "press", "pressing", "tap", "tapping", "push", "pushing", "pull", "pulling",
            "toggle", "toggling", "flip", "flipping", "switch", "switching", "rightclick", "rmb", "open", "opening"
    );
    static final Set<String> MOUNT = words(
            "mount", "mounting", "ride", "riding", "board", "boarding", "enter", "entering", "hopon"
    );
    static final Set<String> DISMOUNT = words(
            "dismount", "dismounting", "unmount", "unmounting", "exit", "exiting", "leave", "leaving", "getoff"
    );
    static final Set<String> BOAT = words(
            "boat", "boats", "raft", "rafts", "watercraft", "vessel"
    );
    static final Set<String> BOAT_DEPLOY = words(
            "deploy", "deploying", "place", "placing", "launch", "launching", "spawn", "spawning", "drop", "dropping", "use"
    );
    static final Set<String> ELYTRA = words(
            "elytra", "glide", "gliding", "fly", "flying", "flight", "airborne", "rocket", "rockets", "firework", "fireworks"
    );
    static final Set<String> SWIM = words(
            "swim", "swimming", "dive", "diving", "water", "aquatic"
    );
    static final Set<String> INSPECT = words(
            "inspect", "inspecting", "inspection", "scan", "scanning", "survey", "surveying", "observe", "observing",
            "check", "checking", "analyze", "analyse", "analyzing", "analysing"
    );
    static final Set<String> SURROUNDINGS = words(
            "surroundings", "surrounding", "nearby", "around", "area", "terrain", "hazard", "hazards", "environment",
            "vicinity", "local", "adjacent", "neighborhood", "neighbourhood"
    );

    static final Set<String> BLOCK = words(
            "block", "blocks", "stone", "dirt", "sand", "gravel", "ore", "ores", "log", "logs", "wood", "plank",
            "planks", "glass", "deepslate", "cobblestone", "netherrack", "obsidian", "bedrock", "leaves", "leaf"
    );
    static final Set<String> BLOCK_INTERACTIVE = words(
            "lever", "levers", "button", "buttons", "door", "doors", "trapdoor", "trapdoors", "gate", "gates",
            "chest", "chests", "barrel", "barrels", "shulker", "shulkers", "furnace", "furnaces", "hopper", "hoppers"
    );
    static final Set<String> MINE = words(
            "mine", "mining", "mined", "dig", "digging", "dug", "break", "breaking", "broke", "harvest", "harvesting",
            "excavate", "excavating", "quarry", "quarrying", "chop", "chopping", "destroy", "destroying", "remove"
    );
    static final Set<String> PLACE = words(
            "place", "placing", "placed", "put", "putting", "set", "setting", "deploy", "deploying", "position", "positioning"
    );
    static final Set<String> BUILD = words(
            "build", "building", "built", "construct", "constructing", "create", "creating", "make", "making", "assemble", "assembling"
    );
    static final Set<String> PATTERN = words(
            "line", "row", "column", "square", "rectangle", "rectangular", "perimeter", "platform", "bridge", "bridging",
            "wall", "walls", "floor", "flooring", "roof", "roofing", "tower", "grid", "pattern", "path", "runway", "outline", "filled"
    );
    static final Set<String> RELATIVE_BLOCK = words(
            "below", "under", "beneath", "above", "overhead", "head", "feet", "footing", "looking", "crosshair", "front", "ahead"
    );

    static final Set<String> ENTITY = words(
            "entity", "entities", "mob", "mobs", "creature", "creatures", "animal", "animals", "monster", "monsters",
            "npc", "npcs", "player", "players", "villager", "villagers", "sheep", "cow", "cows", "pig", "pigs", "horse",
            "horses", "zombie", "zombies", "skeleton", "skeletons", "creeper", "creepers", "spider", "spiders", "warden",
            "wardens", "dragon", "dragons"
    );
    static final Set<String> ATTACK = words(
            "attack", "attacking", "hit", "hitting", "strike", "striking", "fight", "fighting", "combat", "punch", "punching",
            "damage", "damaging", "shoot", "shooting"
    );
    static final Set<String> KILL = words(
            "kill", "killing", "killed", "slay", "slaying", "slain", "defeat", "defeating", "eliminate", "eliminating",
            "finish", "finishing", "execute", "executing"
    );

    static final Set<String> CONTAINER = words(
            "container", "containers", "chest", "chests", "barrel", "barrels", "shulker", "shulkers", "hopper", "hoppers",
            "furnace", "furnaces", "dispenser", "dispensers", "dropper", "droppers", "storage", "stash"
    );
    static final Set<String> OPEN = words(
            "open", "opening", "opened", "access", "accessing", "enter", "entering", "view", "viewing"
    );
    static final Set<String> TAKE = words(
            "take", "taking", "took", "get", "getting", "grab", "grabbing", "loot", "looting", "withdraw", "withdrawing",
            "retrieve", "retrieving", "extract", "extracting", "pull", "pulling"
    );
    static final Set<String> STORE = words(
            "store", "storing", "stored", "put", "putting", "deposit", "depositing", "insert", "inserting", "stash", "stashing",
            "save", "saving", "load", "loading", "transfer", "transferring", "push", "pushing"
    );
    static final Set<String> INVENTORY = words(
            "inventory", "inv", "hotbar", "slot", "slots", "hand", "offhand", "mainhand", "held", "holding", "item", "items"
    );
    static final Set<String> USE_ITEM = words(
            "use", "using", "activate", "activating", "equip", "equipping", "wield", "wielding", "consume", "consuming", "drink", "drinking"
    );
    static final Set<String> EAT = words(
            "eat", "eating", "ate", "consume", "consuming", "food", "foods", "hungry", "hunger", "snack", "snacking", "bite", "biting"
    );

    static final Set<String> TIME_ACTION = words(
            "set", "setting", "change", "changing", "make", "making", "switch", "switching", "advance", "advancing"
    );
    static final Set<String> TIME = words(
            "time", "day", "daytime", "night", "nighttime", "noon", "midnight", "sunrise", "sunset", "dawn", "dusk", "morning", "evening"
    );
    static final Set<String> ADVANCEMENT = words(
            "advancement", "advancements", "advancment", "advancments", "achievement", "achievements", "criterion", "criteria"
    );
    static final Set<String> ALL = words(
            "all", "every", "everything", "entire", "complete", "full"
    );
    static final Set<String> GRANT = words(
            "give", "giving", "grant", "granting", "award", "awarding", "unlock", "unlocking", "complete", "completing"
    );

    static final Set<String> COMMAND = words(
            "command", "commands", "cmd", "slash", "execute", "run", "syntax", "brigadier", "give", "clear", "title", "actionbar",
            "summon", "teleport", "tp", "gamemode", "gamerule", "difficulty", "weather", "locate", "seed", "kill", "effect", "enchant"
    );
    static final Set<String> REMOVE_ITEM = words(
            "remove", "removing", "clear", "clearing", "take", "taking", "delete", "deleting"
    );

    static final Set<String> RECIPE = words(
            "recipe", "recipes", "craft", "crafting", "crafted", "ingredient", "ingredients", "smelt", "smelting", "smelted",
            "cook", "cooking", "cooked", "furnace", "blastfurnace", "smoker"
    );
    static final Set<String> STRUCTURE = words(
            "structure", "structures", "fortress", "fortresses", "bastion", "bastions", "temple", "temples", "village", "villages",
            "stronghold", "strongholds", "monument", "monuments", "mansion", "mansions", "mineshaft", "mineshafts", "dungeon", "dungeons",
            "outpost", "outposts", "shipwreck", "shipwrecks", "ruin", "ruins", "city", "cities"
    );
    static final Set<String> REGISTRY = words(
            "registry", "registries", "registered", "identifier", "identifiers", "id", "ids", "namespace", "namespaced", "modded",
            "vanilla", "datapack", "datapacks", "tag", "tags", "exists", "existence", "lookup", "catalog"
    );
    static final Set<String> DIMENSION = words(
            "dimension", "dimensions", "nether", "overworld", "end", "enddimension"
    );
    static final Set<String> TARGET = words(
            "target", "targeted", "crosshair", "crosshairs", "looking", "aiming", "pointing", "focused", "focus"
    );
    static final Set<String> PLAYER = words(
            "player", "self", "me", "myself", "position", "coordinate", "coordinates", "coord", "coords", "biome", "dimension",
            "health", "hunger", "armor", "armour", "effect", "effects", "footing", "riding", "vehicle", "travel", "gamemode", "inventory"
    );
    static final Set<String> ITEM = words(
            "item", "items", "tool", "tools", "weapon", "weapons", "food", "foods", "stack", "stacks", "durability", "rarity", "enchantability"
    );
    static final Set<String> EFFECT = words(
            "effect", "effects", "potion", "potions", "status", "buff", "buffs", "debuff", "debuffs"
    );
    static final Set<String> ENCHANTMENT = words(
            "enchantment", "enchantments", "enchant", "enchanting", "enchanted", "curse", "curses", "level", "levels"
    );
    static final Set<String> NBT = words(
            "nbt", "snbt", "component", "components", "metadata", "tag", "tags", "compound", "data"
    );
    static final Set<String> INFO = words(
            "info", "information", "details", "detail", "about", "inspect", "inspection", "describe", "description", "properties", "property", "stats", "data"
    );

    static final Set<String> WORKSPACE = words(
            "workspace", "workspaces", "root", "roots", "file", "files", "folder", "folders", "directory", "directories", "dir", "repo", "repository",
            "project", "source", "code", "coding", "path", "paths", "class", "classes", "method", "methods", "function", "functions", "package", "packages",
            "import", "imports", "interface", "interfaces", "enum", "enums", "record", "records", "field", "fields",
            "constructor", "constructors", "mixin", "mixins", "config", "configs", "configuration", "script", "scripts"
    );
    static final Set<String> FILE_FORMAT = words(
            "java", "json", "json5", "yaml", "yml", "toml", "xml", "markdown", "md", "mcfunction", "mcmeta", "lang", "properties",
            "gradle", "gradlew", "groovy", "kt", "kts", "ktl", "js", "ts", "py", "python", "sh", "bash", "bat",
            "txt", "text", "csv", "ini", "cfg", "log", "logs", "datapack", "resourcepack"
    );
    static final Set<String> LIST_FILES = words(
            "list", "listing", "ls", "tree", "browse", "browsing", "enumerate", "enumerating"
    );
    static final Set<String> STAT = words(
            "stat", "stats", "metadata", "exists", "existence", "size", "bytes", "hash", "checksum", "sha", "sha1", "sha256",
            "digest", "revision", "modified", "mtime", "timestamp", "type"
    );
    static final Set<String> SEARCH_FILES = words(
            "search", "searching", "find", "finding", "grep", "rg", "ripgrep", "ag", "ack", "fd", "match", "matching",
            "keyword", "keywords", "occurrence", "occurrences", "locate"
    );
    static final Set<String> READ_FILES = words(
            "read", "reading", "reread", "view", "viewing", "inspect", "inspecting", "open", "opening", "cat", "print", "printing", "display", "contents", "content", "source"
    );
    static final Set<String> DIRECTORY = words(
            "folder", "folders", "directory", "directories", "dir"
    );
    static final Set<String> CREATE_FILES = words(
            "create", "creating", "new", "make", "making", "touch", "generate", "generating", "add", "adding"
    );
    static final Set<String> EDIT_FILES = words(
            "edit", "editing", "modify", "modifying", "change", "changing", "replace", "replacing", "patch", "patching", "fix", "fixing",
            "refactor", "refactoring", "update", "updating", "alter", "altering", "revise", "revising"
    );
    static final Set<String> WRITE_FILES = words(
            "write", "writing", "rewrite", "rewriting", "overwrite", "overwriting", "save", "saving", "persist", "persisting"
    );
    static final Set<String> DELETE_FILES = words(
            "delete", "deleting", "remove", "removing", "erase", "erasing", "rm", "unlink", "trash", "discard", "discarding"
    );
    static final Set<String> RESTORE_FILES = words(
            "restore", "restoring", "recover", "recovering", "undelete", "undeleting", "revive", "reviving"
    );
    static final Set<String> COPY_FILES = words(
            "copy", "copying", "duplicate", "duplicating", "clone", "cloning", "cp"
    );
    static final Set<String> MOVE_FILES = words(
            "move", "moving", "rename", "renaming", "relocate", "relocating", "mv"
    );

    static final Set<String> VALIDATE = words(
            "compile", "compiling", "compilation", "build", "building", "test", "tests", "testing", "proof", "proofs", "verify", "verifying",
            "validate", "validating", "validation", "lint", "linting", "check", "checking", "gradle", "gradlew", "javac", "junit",
            "checkstyle", "spotbugs", "pmd"
    );
    static final Set<String> PROJECT = words(
            "project", "repo", "repository", "source", "code", "java", "gradle", "gradlew"
    );
    static final Set<String> KTL = words(
            "ktl", "skill", "skills", "workflow", "workflows", "routine", "routines", "automation", "automate", "task", "tasks",
            "parkour", "follow", "following", "chase", "chasing", "orbit", "orbiting", "farm", "farming", "progression", "enderdragon"
    );
    static final Set<String> RUN_SKILL = words(
            "run", "running", "execute", "executing", "start", "starting", "perform", "performing", "use", "using"
    );
    static final Set<String> PLAN = words(
            "plan", "planning", "planner", "sequence", "strategy", "strategize", "outline"
    );
    static final Set<String> CANCEL = words(
            "cancel", "cancelling", "canceled", "cancelled", "stop", "stopping", "abort", "aborting", "halt", "halting", "terminate", "terminating", "interrupt", "interrupting", "quit"
    );

    static final Set<String> INTERNET = words(
            "internet", "web", "online", "browser", "website", "websites", "webpage", "webpages", "url", "urls", "uri", "http", "https",
            "google", "bing", "duckduckgo", "github", "gitlab", "huggingface", "hf", "reddit", "stackoverflow", "wikipedia",
            "modrinth", "curseforge", "maven", "fabricmc", "youtube", "documentation", "docs", "wiki", "article", "articles",
            "release", "releases", "changelog", "changelogs", "latest", "newest", "recent", "news", "public"
    );
    static final Set<String> INTERNET_SEARCH = words(
            "search", "searching", "find", "finding", "lookup", "discover", "discovering", "research", "researching", "browse", "browsing", "google"
    );
    static final Set<String> INTERNET_FETCH = words(
            "fetch", "fetching", "open", "opening", "visit", "visiting", "retrieve", "retrieving", "download", "downloading", "read", "reading", "page", "article", "url"
    );

    static final Set<String> QUESTION = words(
            "what", "which", "who", "where", "when", "why", "how", "explain", "describe", "tell", "help"
    );
    static final Set<String> GREETING = words(
            "hi", "hello", "hey", "yo", "thanks", "thank", "thx", "ty", "morning", "evening"
    );

    private LocalModelToolVocabulary() {
    }

    static PromptTerms parse(String prompt) {
        String raw = prompt == null ? "" : prompt;
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("([a-z0-9_])[-]([a-z0-9_])", "$1 $2")
                .replaceAll("\\s+", " ")
                .strip();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(normalized)) {
            if (!token.isBlank()) tokens.add(token);
        }
        return new PromptTerms(raw, normalized, Collections.unmodifiableSet(tokens), countTokens(normalized));
    }

    static boolean any(PromptTerms prompt, Set<String> candidates) {
        if (prompt == null || candidates == null || candidates.isEmpty()) return false;
        for (String word : candidates) if (prompt.words().contains(word)) return true;
        return false;
    }

    static boolean all(PromptTerms prompt, String... required) {
        if (prompt == null || required == null || required.length == 0) return false;
        for (String word : required) {
            if (word == null || word.isBlank() || !prompt.words().contains(word)) return false;
        }
        return true;
    }

    static boolean containsNamespacedId(PromptTerms prompt) {
        return prompt != null && NAMESPACED_ID.matcher(" " + prompt.normalized() + " ").find();
    }

    static boolean containsUrl(PromptTerms prompt) {
        return prompt != null && URL.matcher(prompt.raw()).find();
    }

    static boolean exactSingleWord(PromptTerms prompt, Set<String> candidates) {
        return prompt != null && prompt.tokenCount() == 1 && any(prompt, candidates);
    }

    private static int countTokens(String normalized) {
        if (normalized == null || normalized.isBlank()) return 0;
        return (int) Arrays.stream(TOKEN_SPLIT.split(normalized)).filter(value -> !value.isBlank()).count();
    }

    private static Set<String> words(String... values) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                String clean = value.strip().toLowerCase(Locale.ROOT);
                if (clean.isBlank()) continue;
                if (!clean.matches("[a-z0-9_]+")) {
                    throw new IllegalArgumentException("Tool vocabulary entries must be one token: " + value);
                }
                set.add(clean);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    record PromptTerms(String raw, String normalized, Set<String> words, int tokenCount) {
    }
}
