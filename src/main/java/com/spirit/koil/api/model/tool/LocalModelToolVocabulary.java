package com.spirit.koil.api.model.tool;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic lexical intelligence for local-model tool routing.
 *
 * <p>This class intentionally remains much cheaper than an embedding model,
 * classifier model, or parser. The model-facing tool catalog composes meaning
 * from independent lexical signals; this vocabulary's job is to make those
 * signals reliable across inflection, common shorthand, Minecraft terminology,
 * developer vocabulary, punctuation, ids, coordinates, paths, URLs, and a
 * bounded set of common misspellings.</p>
 *
 * <p>Compatibility rule: the historical vocabulary constants and the original
 * {@link PromptTerms#raw()}, {@link PromptTerms#normalized()},
 * {@link PromptTerms#words()}, and {@link PromptTerms#tokenCount()} accessors
 * remain intact. New helpers add information without requiring existing
 * routing code to change.</p>
 *
 * <p>Important design constraints:</p>
 * <ul>
 *     <li>Vocabulary entries are still one lexical token. Phrase meaning is
 *     composed by callers instead of storing sentence templates.</li>
 *     <li>Parsing is deterministic, allocation-bounded, provider-neutral, and
 *     has no game/world side effects.</li>
 *     <li>Aliases are conservative. They normalize spelling/shorthand rather
 *     than attempting open-ended fuzzy matching.</li>
 *     <li>Namespaced ids and URLs are preserved as structured tokens instead
 *     of being destroyed by punctuation normalization.</li>
 *     <li>Token count measures the user's lexical input, not aliases added by
 *     normalization, so existing short-prompt heuristics remain meaningful.</li>
 * </ul>
 */
final class LocalModelToolVocabulary {
    static final String VERSION = "tool-word-vocabulary-v5-lexical-intelligence";

    private static final int MAXIMUM_RAW_CHARACTERS = 16_384;
    private static final int MAXIMUM_TOKENS = 512;
    private static final int MAXIMUM_STRUCTURED_VALUES = 64;

    private static final Pattern TOKEN = Pattern.compile(
            "https?://[^\\s<>\"']+"
                    + "|#?[a-z0-9_.-]+:[a-z0-9_./-]+"
                    + "|(?:[a-zA-Z]:)?(?:[/\\\\][^\\s<>:\"']+)+"
                    + "|[-+]?\\d+(?:\\.\\d+)?"
                    + "|[a-z0-9_]+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "^#?[a-z0-9_.-]+:[a-z0-9_./-]+$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern URL = Pattern.compile(
            "^https?://",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(?:\\d+\\.\\d+|\\d+)");
    private static final String COORDINATE_COMPONENT =
            "(?:[~^](?:-?\\d+(?:\\.\\d+)?)?|[-+]?\\d+(?:\\.\\d+)?)";
    private static final Pattern COORDINATE_TRIPLE = Pattern.compile(
            "(?<![a-z0-9_])(" + COORDINATE_COMPONENT + ")\\s*[ ,]+\\s*"
                    + "(" + COORDINATE_COMPONENT + ")\\s*[ ,]+\\s*"
                    + "(" + COORDINATE_COMPONENT + ")(?![a-z0-9_])",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "^(?:[a-zA-Z]:[\\\\/]|/|\\\\\\\\).+"
    );
    private static final Pattern RELATIVE_PATH = Pattern.compile(
            "^(?:\\.{1,2}[\\\\/]|[a-z0-9_.-]+[\\\\/]).+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOOL_ID = Pattern.compile(
            "^[a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOOL_ID_SEARCH = Pattern.compile(
            "(?<![a-z0-9_-])([a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+)(?![a-z0-9_-])",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> TOOL_DOMAINS = Set.of(
            "automation", "block", "container", "development", "entity",
            "context", "input", "internet", "inventory", "koil", "minecraft",
            "movement", "player", "project", "transport", "workspace", "world"
    );

    /*
     * Canonical spellings and tightly bounded typo repair. These are not fuzzy
     * guesses. Each alias is intentionally reviewed because aliases affect tool
     * exposure and must not silently turn arbitrary prose into an action.
     */
    private static final Map<String, String> CANONICAL = canonicalAliases();

    /*
     * Compound spellings that users commonly type as one token. Expansions are
     * added in addition to the original token, allowing old exact-token rules
     * and newer compositional rules to work at the same time.
     */
    private static final Map<String, Set<String>> EXPANSIONS = compoundExpansions();

    /* ---------- Movement and camera ---------- */

    static final Set<String> WALK = words(
            "walk", "walking", "walked", "walks", "step", "steps", "stepping",
            "stroll", "strolling", "pace", "pacing", "forward", "forwards",
            "ahead", "back", "backward", "backwards", "reverse", "left",
            "right", "strafe", "strafing", "sidestep", "sidestepping",
            "retreat", "retreating"
    );

    static final Set<String> NAVIGATE = words(
            "move", "moving", "navigate", "navigating", "navigation", "travel",
            "traveling", "travelling", "route", "routing", "path", "pathing",
            "pathfind", "pathfinding", "goto", "head", "reach", "approach",
            "approaching", "destination", "waypoint", "waypoints",
            "coordinate", "coordinates", "coord", "coords", "position",
            "location", "target", "destination", "journey", "traverse"
    );

    static final Set<String> JUMP = words(
            "jump", "jumping", "jumped", "jumps", "hop", "hopping", "hopped",
            "leap", "leaping", "leapt", "vault", "vaulting", "vaulted",
            "bounce", "bouncing"
    );

    static final Set<String> TAP = words(
            "tap", "tapping", "tapped", "press", "pressing", "pressed", "click",
            "clicking", "clicked", "keystroke", "keystrokes", "hotkey",
            "hotkeys", "input", "inputs", "key", "keys", "button", "buttons"
    );

    static final Set<String> HOLD = words(
            "hold", "holding", "held", "sustain", "sustaining", "keep",
            "keeping", "maintain", "maintaining"
    );

    static final Set<String> RELEASE = words(
            "release", "releasing", "released", "unpress", "unpressed",
            "keyup", "letgo", "lift", "lifting"
    );

    static final Set<String> INPUT_KEYS = words(
            "w", "a", "s", "d", "e", "t", "q", "f", "r", "space", "shift",
            "ctrl", "control", "alt", "enter", "return", "tab", "escape",
            "esc", "backspace", "delete", "up", "down", "left", "right",
            "forward", "back", "backward", "jump", "sneak", "crouch",
            "sprint", "attack", "use", "inventory", "chat", "swap",
            "swap_hands", "drop", "pick", "pick_block", "perspective",
            "third_person", "camera", "leftclick", "rightclick", "middleclick",
            "left_click", "right_click", "middle_click", "left_shift",
            "left_ctrl", "left_arrow", "right_arrow", "page_up", "page_down",
            "lmb", "rmb", "mmb", "mouse1", "mouse2", "mouse3"
    );

    static final Set<String> MOUSE = words(
            "mouse", "cursor", "pointer", "lmb", "rmb", "mmb", "mouse1",
            "mouse2", "mouse3", "leftclick", "rightclick", "middleclick",
            "left_click", "right_click", "middle_click", "scroll", "wheel"
    );

    static final Set<String> CAMERA = words(
            "camera", "view", "look", "looking", "aim", "aiming", "yaw",
            "pitch", "delta", "rotate", "rotating", "turn", "turning", "pan",
            "panning", "angle", "angles", "orientation", "perspective"
    );

    static final Set<String> LOOK = words(
            "look", "looking", "face", "facing", "aim", "aiming", "target",
            "targeting", "turn", "turning", "point", "pointing", "orient",
            "orienting"
    );

    /* ---------- Interaction and transport ---------- */

    static final Set<String> INTERACT = words(
            "interact", "interacting", "interaction", "use", "using",
            "activate", "activating", "trigger", "triggering", "click",
            "clicking", "press", "pressing", "tap", "tapping", "push",
            "pushing", "pull", "pulling", "toggle", "toggling", "flip",
            "flipping", "switch", "switching", "rightclick", "rmb", "open",
            "opening"
    );

    static final Set<String> MOUNT = words(
            "mount", "mounting", "ride", "riding", "board", "boarding",
            "enter", "entering", "hopon"
    );

    static final Set<String> DISMOUNT = words(
            "dismount", "dismounting", "unmount", "unmounting", "exit",
            "exiting", "leave", "leaving", "getoff"
    );

    static final Set<String> BOAT = words(
            "boat", "boats", "raft", "rafts", "watercraft", "vessel"
    );

    static final Set<String> BOAT_DEPLOY = words(
            "deploy", "deploying", "place", "placing", "launch", "launching",
            "spawn", "spawning", "drop", "dropping", "use"
    );

    static final Set<String> ELYTRA = words(
            "elytra", "glide", "gliding", "fly", "flying", "flight",
            "airborne", "rocket", "rockets", "firework", "fireworks"
    );

    static final Set<String> SWIM = words(
            "swim", "swimming", "dive", "diving", "water", "aquatic",
            "submerge", "submerged", "underwater"
    );

    static final Set<String> INSPECT = words(
            "inspect", "inspecting", "inspection", "scan", "scanning",
            "survey", "surveying", "observe", "observing", "check", "checking",
            "analyze", "analyse", "analyzing", "analysing", "examine",
            "examining", "detect", "detecting"
    );

    static final Set<String> SURROUNDINGS = words(
            "surroundings", "surrounding", "nearby", "around", "area",
            "terrain", "hazard", "hazards", "environment", "vicinity", "local",
            "adjacent", "neighborhood", "neighbourhood", "proximity"
    );

    /* ---------- Blocks, building, world objects ---------- */

    static final Set<String> BLOCK = words(
            "block", "blocks", "stone", "dirt", "sand", "gravel", "ore",
            "ores", "log", "logs", "wood", "plank", "planks", "glass",
            "deepslate", "cobblestone", "netherrack", "obsidian", "bedrock",
            "leaves", "leaf", "torch", "torches", "redstone", "rail",
            "rails", "slab", "slabs", "stair", "stairs", "wall", "walls",
            "fence", "fences"
    );

    static final Set<String> BLOCK_INTERACTIVE = words(
            "lever", "levers", "button", "buttons", "door", "doors",
            "trapdoor", "trapdoors", "gate", "gates", "chest", "chests",
            "barrel", "barrels", "shulker", "shulkers", "furnace",
            "furnaces", "hopper", "hoppers", "dispenser", "dispensers",
            "dropper", "droppers", "anvil", "anvils", "beacon", "beacons",
            "bed", "beds", "bell", "bells"
    );

    static final Set<String> MINE = words(
            "mine", "mining", "mined", "dig", "digging", "dug", "break",
            "breaking", "broke", "harvest", "harvesting", "excavate",
            "excavating", "quarry", "quarrying", "chop", "chopping",
            "destroy", "destroying", "remove"
    );

    static final Set<String> PLACE = words(
            "place", "placing", "placed", "put", "putting", "set", "setting",
            "deploy", "deploying", "position", "positioning"
    );

    static final Set<String> BUILD = words(
            "build", "building", "built", "construct", "constructing",
            "create", "creating", "make", "making", "assemble", "assembling"
    );

    static final Set<String> PATTERN = words(
            "line", "row", "column", "square", "rectangle", "rectangular",
            "perimeter", "platform", "bridge", "bridging", "wall", "walls",
            "floor", "flooring", "roof", "roofing", "tower", "grid",
            "pattern", "path", "runway", "outline", "filled", "circle",
            "sphere", "cube", "cuboid"
    );

    static final Set<String> RELATIVE_BLOCK = words(
            "below", "under", "beneath", "above", "overhead", "head", "feet",
            "footing", "looking", "crosshair", "front", "ahead", "behind",
            "beside", "adjacent"
    );

    /* ---------- Entities and combat ---------- */

    static final Set<String> ENTITY = words(
            "entity", "entities", "mob", "mobs", "creature", "creatures",
            "animal", "animals", "monster", "monsters", "npc", "npcs",
            "player", "players", "villager", "villagers", "sheep", "cow",
            "cows", "pig", "pigs", "horse", "horses", "zombie", "zombies",
            "skeleton", "skeletons", "creeper", "creepers", "spider",
            "spiders", "warden", "wardens", "dragon", "dragons", "enderman",
            "endermen", "slime", "slimes", "bee", "bees"
    );

    static final Set<String> ATTACK = words(
            "attack", "attacking", "hit", "hitting", "strike", "striking",
            "fight", "fighting", "combat", "punch", "punching", "damage",
            "damaging", "shoot", "shooting", "fire", "firing"
    );

    static final Set<String> KILL = words(
            "kill", "killing", "killed", "slay", "slaying", "slain",
            "defeat", "defeating", "eliminate", "eliminating", "finish",
            "finishing", "execute", "executing"
    );

    /* ---------- Containers and inventory ---------- */

    static final Set<String> CONTAINER = words(
            "container", "containers", "chest", "chests", "barrel", "barrels",
            "shulker", "shulkers", "hopper", "hoppers", "furnace",
            "furnaces", "dispenser", "dispensers", "dropper", "droppers",
            "storage", "stash", "inventory"
    );

    static final Set<String> OPEN = words(
            "open", "opening", "opened", "access", "accessing", "enter",
            "entering", "view", "viewing"
    );

    static final Set<String> TAKE = words(
            "take", "taking", "took", "get", "getting", "grab", "grabbing",
            "loot", "looting", "withdraw", "withdrawing", "retrieve",
            "retrieving", "extract", "extracting", "pull", "pulling",
            "collect", "collecting"
    );

    static final Set<String> STORE = words(
            "store", "storing", "stored", "put", "putting", "deposit",
            "depositing", "insert", "inserting", "stash", "stashing", "save",
            "saving", "load", "loading", "transfer", "transferring", "push",
            "pushing"
    );

    static final Set<String> INVENTORY = words(
            "inventory", "inv", "hotbar", "slot", "slots", "hand", "offhand",
            "mainhand", "held", "holding", "item", "items", "equipment",
            "armor", "armour"
    );

    static final Set<String> USE_ITEM = words(
            "use", "using", "activate", "activating", "equip", "equipping",
            "wield", "wielding", "consume", "consuming", "drink", "drinking",
            "throw", "throwing", "fire", "firing"
    );

    static final Set<String> EAT = words(
            "eat", "eating", "ate", "consume", "consuming", "food", "foods",
            "hungry", "hunger", "snack", "snacking", "bite", "biting"
    );

    /* ---------- World state and progression ---------- */

    static final Set<String> TIME_ACTION = words(
            "set", "setting", "change", "changing", "make", "making",
            "switch", "switching", "advance", "advancing"
    );

    static final Set<String> TIME = words(
            "time", "day", "daytime", "night", "nighttime", "noon",
            "midnight", "sunrise", "sunset", "dawn", "dusk", "morning",
            "evening"
    );

    static final Set<String> ADVANCEMENT = words(
            "advancement", "advancements", "advancment", "advancments",
            "achievement", "achievements", "criterion", "criteria",
            "progression", "progress"
    );

    static final Set<String> ALL = words(
            "all", "every", "everything", "entire", "complete", "full",
            "whole", "each"
    );

    static final Set<String> GRANT = words(
            "give", "giving", "grant", "granting", "award", "awarding",
            "unlock", "unlocking", "complete", "completing"
    );

    /* ---------- Minecraft knowledge ---------- */

    static final Set<String> COMMAND = words(
            "command", "commands", "cmd", "slash", "execute", "run", "syntax",
            "brigadier", "give", "clear", "title", "actionbar", "summon",
            "teleport", "tp", "gamemode", "gamerule", "difficulty", "weather",
            "locate", "seed", "kill", "effect", "enchant", "scoreboard",
            "bossbar", "function", "execute"
    );

    static final Set<String> REMOVE_ITEM = words(
            "remove", "removing", "clear", "clearing", "take", "taking",
            "delete", "deleting"
    );

    static final Set<String> RECIPE = words(
            "recipe", "recipes", "craft", "crafting", "crafted", "ingredient",
            "ingredients", "smelt", "smelting", "smelted", "cook", "cooking",
            "cooked", "furnace", "blastfurnace", "smoker", "smithing",
            "stonecutting", "campfire"
    );

    static final Set<String> STRUCTURE = words(
            "structure", "structures", "fortress", "fortresses", "bastion",
            "bastions", "temple", "temples", "village", "villages",
            "stronghold", "strongholds", "monument", "monuments", "mansion",
            "mansions", "mineshaft", "mineshafts", "dungeon", "dungeons",
            "outpost", "outposts", "shipwreck", "shipwrecks", "ruin",
            "ruins", "city", "cities", "trailruins", "trialchamber"
    );

    static final Set<String> REGISTRY = words(
            "registry", "registries", "registered", "identifier",
            "identifiers", "id", "ids", "namespace", "namespaced", "modded",
            "vanilla", "datapack", "datapacks", "tag", "tags", "exists",
            "existence", "lookup", "catalog", "entry", "entries"
    );

    static final Set<String> DIMENSION = words(
            "dimension", "dimensions", "nether", "overworld", "end",
            "enddimension"
    );

    static final Set<String> TARGET = words(
            "target", "targeted", "crosshair", "crosshairs", "looking",
            "aiming", "pointing", "focused", "focus"
    );

    static final Set<String> PLAYER = words(
            "player", "self", "me", "myself", "position", "coordinate",
            "coordinates", "coord", "coords", "biome", "dimension", "health",
            "hunger", "armor", "armour", "effect", "effects", "footing",
            "riding", "vehicle", "travel", "gamemode", "inventory", "xp",
            "experience", "level"
    );

    static final Set<String> ITEM = words(
            "item", "items", "tool", "tools", "weapon", "weapons", "food",
            "foods", "stack", "stacks", "durability", "rarity",
            "enchantability", "material", "materials"
    );

    static final Set<String> EFFECT = words(
            "effect", "effects", "potion", "potions", "status", "buff",
            "buffs", "debuff", "debuffs"
    );

    static final Set<String> ENCHANTMENT = words(
            "enchantment", "enchantments", "enchant", "enchants", "enchanting",
            "enchanted", "curse", "curses", "level", "levels"
    );

    static final Set<String> NBT = words(
            "nbt", "snbt", "component", "components", "metadata", "tag",
            "tags", "compound", "data"
    );

    static final Set<String> INFO = words(
            "info", "information", "details", "detail", "about", "inspect",
            "inspection", "describe", "description", "properties", "property",
            "stats", "data", "explain", "explanation", "knowledge"
    );

    /* Additional knowledge graph vocabulary for minecraft.knowledge. */

    static final Set<String> KNOWLEDGE = words(
            "knowledge", "explain", "explanation", "understand", "meaning",
            "relationship", "relationships", "relation", "relations",
            "connected", "connection", "connections", "related", "link",
            "links", "linked", "dependency", "dependencies", "graph"
    );

    static final Set<String> SOURCE_RELATION = words(
            "source", "sources", "produce", "produces", "produced",
            "producer", "producers", "obtain", "obtained", "acquire",
            "acquired", "made", "make", "recipe", "recipes", "craft",
            "crafted", "crafting"
    );

    static final Set<String> USE_RELATION = words(
            "use", "uses", "used", "usage", "consume", "consumes",
            "consumed", "ingredient", "ingredients", "part", "parts",
            "component", "components", "needed", "requires", "requirement"
    );

    static final Set<String> BIOME = words(
            "biome", "biomes", "plains", "desert", "forest", "jungle",
            "swamp", "savanna", "taiga", "badlands", "meadow", "grove",
            "deepdark", "mushroom", "ocean"
    );

    static final Set<String> SOUND = words(
            "sound", "sounds", "audio", "soundevent", "soundevents",
            "ambient", "music"
    );

    static final Set<String> PARTICLE = words(
            "particle", "particles", "fx", "effectparticle"
    );

    static final Set<String> FLUID = words(
            "fluid", "fluids", "water", "lava"
    );

    static final Set<String> LOOT = words(
            "loot", "loottable", "loottables", "drop", "drops", "droptable",
            "droptables"
    );

    static final Set<String> FUNCTION = words(
            "function", "functions", "mcfunction", "mcfunctions"
    );

    static final Set<String> PREDICATE = words(
            "predicate", "predicates", "condition", "conditions"
    );

    static final Set<String> RESOURCE = words(
            "resource", "resources", "resourcepack", "resourcepacks", "asset",
            "assets", "json", "model", "models", "texture", "textures",
            "lang", "translation", "translations"
    );

    static final Set<String> MOD = words(
            "mod", "mods", "modded", "fabric", "loader", "modid", "modids"
    );

    /* ---------- Workspace and development ---------- */

    static final Set<String> WORKSPACE = words(
            "workspace", "workspaces", "root", "roots", "file", "files",
            "folder", "folders", "directory", "directories", "dir", "repo",
            "repository", "project", "source", "code", "coding", "path",
            "paths", "class", "classes", "method", "methods", "function",
            "functions", "package", "packages", "import", "imports",
            "interface", "interfaces", "enum", "enums", "record", "records",
            "field", "fields", "constructor", "constructors", "mixin",
            "mixins", "config", "configs", "configuration", "script",
            "scripts"
    );

    static final Set<String> CODE_INTELLIGENCE = words(
            "architecture", "structure", "symbol", "symbols", "caller",
            "callers", "callee", "callees", "trace", "reference", "references",
            "dependency", "dependencies", "impact", "blast", "graph",
            "semantic", "implementation", "implementations", "type", "types",
            "dead", "unused", "route", "routes", "flow", "hotspot",
            "hotspots", "cluster", "clusters", "schema", "snippet", "query",
            "usage", "usages", "hierarchy", "inheritance", "override",
            "overrides"
    );

    static final Set<String> FILE_FORMAT = words(
            "java", "json", "json5", "yaml", "yml", "toml", "xml",
            "markdown", "md", "mcfunction", "mcmeta", "lang", "properties",
            "gradle", "gradlew", "groovy", "kt", "kts", "ktl", "js", "ts",
            "py", "python", "sh", "bash", "bat", "txt", "text", "csv", "ini",
            "cfg", "log", "logs", "datapack", "resourcepack", "zip", "jar"
    );

    static final Set<String> LIST_FILES = words(
            "list", "listing", "ls", "tree", "browse", "browsing", "enumerate",
            "enumerating"
    );

    static final Set<String> STAT = words(
            "stat", "stats", "metadata", "exists", "existence", "size",
            "bytes", "hash", "checksum", "sha", "sha1", "sha256", "digest",
            "revision", "modified", "mtime", "timestamp", "type"
    );

    static final Set<String> SEARCH_FILES = words(
            "search", "searching", "find", "finding", "grep", "rg", "ripgrep",
            "ag", "ack", "fd", "match", "matching", "keyword", "keywords",
            "occurrence", "occurrences", "locate", "lookup"
    );

    static final Set<String> READ_FILES = words(
            "read", "reading", "reread", "view", "viewing", "inspect",
            "inspecting", "open", "opening", "cat", "print", "printing",
            "display", "contents", "content", "source", "show"
    );

    static final Set<String> DIRECTORY = words(
            "folder", "folders", "directory", "directories", "dir"
    );

    static final Set<String> CREATE_FILES = words(
            "create", "creating", "new", "make", "making", "touch",
            "generate", "generating", "add", "adding"
    );

    static final Set<String> EDIT_FILES = words(
            "edit", "editing", "modify", "modifying", "change", "changing",
            "replace", "replacing", "patch", "patching", "fix", "fixing",
            "refactor", "refactoring", "update", "updating", "alter",
            "altering", "revise", "revising"
    );

    static final Set<String> WRITE_FILES = words(
            "write", "writing", "rewrite", "rewriting", "overwrite",
            "overwriting", "save", "saving", "persist", "persisting"
    );

    static final Set<String> APPEND_FILES = words(
            "append", "appending", "appended", "add", "adding", "extend",
            "extending", "insert", "inserting"
    );

    static final Set<String> DELETE_FILES = words(
            "delete", "deleting", "remove", "removing", "erase", "erasing",
            "rm", "unlink", "trash", "discard", "discarding"
    );

    static final Set<String> RESTORE_FILES = words(
            "restore", "restoring", "recover", "recovering", "undelete",
            "undeleting", "revive", "reviving"
    );

    static final Set<String> COPY_FILES = words(
            "copy", "copying", "duplicate", "duplicating", "clone", "cloning",
            "cp"
    );

    static final Set<String> MOVE_FILES = words(
            "move", "moving", "rename", "renaming", "relocate", "relocating",
            "mv"
    );

    static final Set<String> VALIDATE = words(
            "compile", "compiling", "compilation", "build", "building", "test",
            "tests", "testing", "proof", "proofs", "verify", "verifying",
            "validate", "validating", "validation", "lint", "linting", "check",
            "checking", "gradle", "gradlew", "javac", "junit", "checkstyle",
            "spotbugs", "pmd", "assert", "assertion", "assertions"
    );

    static final Set<String> PROJECT = words(
            "project", "repo", "repository", "source", "code", "java",
            "gradle", "gradlew", "module", "modules"
    );

    /* ---------- KTL and automation ---------- */

    static final Set<String> KTL = words(
            "ktl", "skill", "skills", "workflow", "workflows", "routine",
            "routines", "automation", "automate", "task", "tasks", "parkour",
            "follow", "following", "chase", "chasing", "orbit", "orbiting",
            "farm", "farming", "progression", "enderdragon", "macro",
            "macros", "executor"
    );

    static final Set<String> RUN_SKILL = words(
            "run", "running", "execute", "executing", "start", "starting",
            "perform", "performing", "use", "using", "invoke", "invoking"
    );

    static final Set<String> PLAN = words(
            "plan", "planning", "planner", "sequence", "strategy",
            "strategize", "outline", "workflow", "steps", "roadmap"
    );

    static final Set<String> CANCEL = words(
            "cancel", "cancelling", "canceled", "cancelled", "stop",
            "stopping", "abort", "aborting", "halt", "halting", "terminate",
            "terminating", "interrupt", "interrupting", "quit"
    );

    /* ---------- Internet ---------- */

    static final Set<String> INTERNET = words(
            "internet", "web", "online", "browser", "website", "websites",
            "webpage", "webpages", "url", "urls", "uri", "http", "https",
            "google", "bing", "duckduckgo", "github", "gitlab", "huggingface",
            "hf", "reddit", "stackoverflow", "wikipedia", "modrinth",
            "curseforge", "maven", "fabricmc", "youtube", "documentation",
            "docs", "wiki", "article", "articles", "release", "releases",
            "changelog", "changelogs", "latest", "newest", "recent", "news",
            "public"
    );

    static final Set<String> INTERNET_SEARCH = words(
            "search", "searching", "find", "finding", "lookup", "discover",
            "discovering", "research", "researching", "browse", "browsing",
            "google"
    );

    static final Set<String> INTERNET_FETCH = words(
            "fetch", "fetching", "open", "opening", "visit", "visiting",
            "retrieve", "retrieving", "download", "downloading", "read",
            "reading", "page", "article", "url"
    );

    static final Set<String> INTERNET_SCRAPE = words(
            "scrape", "scraper", "scraping", "extract", "extraction",
            "selector", "selectors", "css", "rendered", "dynamic"
    );

    static final Set<String> INTERNET_CRAWL = words(
            "crawl", "crawler", "crawling", "traverse", "traversal"
    );

    /* ---------- Koil self-knowledge ---------- */

    static final Set<String> KOIL_SELF = words(
            "koil", "model", "executor", "automation", "automate", "ktl",
            "kms", "kes", "kts", "koro"
    );

    static final Set<String> SELF_DOCUMENTATION = words(
            "documentation", "docs", "doc", "manual", "guide", "guidance",
            "capability", "capabilities", "ability", "abilities", "tool",
            "tools", "system", "systems", "workflow", "workflows", "mode",
            "modes", "permission", "permissions", "self", "yourself", "works",
            "work", "operate", "operates", "operating", "architecture"
    );

    static final Set<String> SELF_REFERENCE = words(
            "you", "your", "yours", "yourself", "model", "executor", "koil"
    );

    /* ---------- Conversation and control semantics ---------- */

    static final Set<String> QUESTION = words(
            "what", "which", "who", "where", "when", "why", "how", "explain",
            "describe", "tell", "help", "can", "could", "would", "should",
            "does", "do", "is", "are"
    );

    static final Set<String> GREETING = words(
            "hi", "hello", "hey", "yo", "thanks", "thank", "thx", "ty",
            "morning", "evening", "afternoon"
    );

    static final Set<String> NEGATION = words(
            "no", "not", "never", "dont", "doesnt", "didnt", "cant",
            "cannot", "wont", "without", "avoid", "exclude", "except",
            "neither", "nor"
    );

    static final Set<String> CONDITIONAL = words(
            "if", "unless", "when", "whenever", "until", "while", "provided",
            "assuming", "otherwise"
    );

    static final Set<String> SEQUENCE = words(
            "then", "after", "before", "next", "finally", "first", "second",
            "third", "last", "subsequently", "afterward", "afterwards"
    );

    static final Set<String> QUANTITY = words(
            "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "once", "twice", "thrice", "several", "many",
            "few", "some", "all", "every", "each"
    );

    static final Set<String> OPTIMIZE = words(
            "best", "nearest", "closest", "fastest", "safest", "shortest",
            "optimal", "optimize", "optimise", "efficient", "efficiently",
            "prefer", "recommended", "recommend"
    );

    static final Set<String> CURRENT = words(
            "current", "currently", "live", "latest", "newest", "recent",
            "today", "now", "active"
    );

    static final Set<String> EXACT = words(
            "exact", "exactly", "specific", "specifically", "literal",
            "precise", "precisely", "verbatim", "identifier", "id", "path",
            "coordinate", "coordinates"
    );

    private LocalModelToolVocabulary() {
    }

    /**
     * Parses one user objective into a deterministic lexical view.
     *
     * <p>The returned word set includes original normalized words plus safe
     * canonical aliases and compound expansions. {@code tokenCount} counts only
     * the original lexical units, preserving old routing thresholds.</p>
     */
    static PromptTerms parse(String prompt) {
        String raw = boundRaw(prompt);
        String normalized = normalizeSurface(raw);

        LinkedHashSet<String> words = new LinkedHashSet<>();
        List<String> ordered = new ArrayList<>();

        Matcher matcher = TOKEN.matcher(normalized);
        while (matcher.find() && ordered.size() < MAXIMUM_TOKENS) {
            String token = cleanToken(matcher.group());
            if (token.isBlank()) {
                continue;
            }

            ordered.add(token);
            addLexicalForms(words, token);
        }

        return new PromptTerms(
                raw,
                normalized,
                Collections.unmodifiableSet(words),
                ordered.size()
        );
    }

    static boolean any(PromptTerms prompt, Set<String> candidates) {
        if (prompt == null || candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (String word : candidates) {
            if (prompt.words().contains(word)) {
                return true;
            }
        }
        return false;
    }

    static boolean all(PromptTerms prompt, String... required) {
        if (prompt == null || required == null || required.length == 0) {
            return false;
        }
        for (String word : required) {
            if (word == null || word.isBlank()) {
                return false;
            }
            String canonical = canonical(word);
            if (!prompt.words().contains(canonical)
                    && !prompt.words().contains(word.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    static boolean containsNamespacedId(PromptTerms prompt) {
        return prompt != null && !prompt.namespacedIds().isEmpty();
    }

    static boolean containsUrl(PromptTerms prompt) {
        return prompt != null && !prompt.urls().isEmpty();
    }

    static boolean exactSingleWord(PromptTerms prompt, Set<String> candidates) {
        return prompt != null
                && prompt.tokenCount() == 1
                && any(prompt, candidates);
    }

    /**
     * Returns true when a lexical signal is negated within a small preceding
     * window. This is intentionally local and deterministic, not a grammar
     * parser. It is useful for avoiding routes such as "do not attack".
     */
    static boolean negated(PromptTerms prompt, Set<String> targetWords) {
        if (prompt == null || targetWords == null || targetWords.isEmpty()) {
            return false;
        }

        List<String> ordered = prompt.orderedWords();
        for (int index = 0; index < ordered.size(); index++) {
            String token = canonical(ordered.get(index));
            if (!targetWords.contains(token)) {
                continue;
            }
            int start = Math.max(0, index - 3);
            for (int scan = start; scan < index; scan++) {
                if (NEGATION.contains(canonical(ordered.get(scan)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns all canonical numbers found as numeric literals or simple number
     * words. This does not assign semantic meaning to the number.
     */
    static List<String> quantities(PromptTerms prompt) {
        if (prompt == null) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : prompt.orderedWords()) {
            String canonical = canonical(token);
            if (DECIMAL.matcher(canonical).matches()) {
                values.add(canonical);
                continue;
            }
            String number = NUMBER_WORDS.get(canonical);
            if (number != null) {
                values.add(number);
            }
            if (values.size() >= MAXIMUM_STRUCTURED_VALUES) {
                break;
            }
        }
        return List.copyOf(values);
    }

    /**
     * Extracts x/y/z-like coordinate triples without interpreting whether they
     * are relative, local, or absolute Minecraft coordinates.
     */
    static List<CoordinateTriple> coordinateTriples(PromptTerms prompt) {
        if (prompt == null || prompt.raw().isBlank()) {
            return List.of();
        }
        List<CoordinateTriple> values = new ArrayList<>();
        Matcher matcher = COORDINATE_TRIPLE.matcher(prompt.raw());
        while (matcher.find() && values.size() < 16) {
            values.add(new CoordinateTriple(
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(3)
            ));
        }
        return List.copyOf(values);
    }

    /**
     * Finds tokens that look like explicit registered model tool ids such as
     * minecraft.knowledge or workspace.read.
     */
    static Set<String> explicitToolIds(PromptTerms prompt) {
        if (prompt == null || prompt.normalized().isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher matcher = TOOL_ID_SEARCH.matcher(prompt.normalized());
        while (matcher.find() && ids.size() < MAXIMUM_STRUCTURED_VALUES) {
            String candidate = matcher.group(1).toLowerCase(Locale.ROOT);
            int dot = candidate.indexOf('.');
            String domain = dot <= 0 ? "" : candidate.substring(0, dot);
            if (TOOL_DOMAINS.contains(domain) && TOOL_ID.matcher(candidate).matches()) {
                ids.add(candidate);
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    private static final Map<String, String> NUMBER_WORDS = Map.ofEntries(
            Map.entry("zero", "0"),
            Map.entry("one", "1"),
            Map.entry("once", "1"),
            Map.entry("two", "2"),
            Map.entry("twice", "2"),
            Map.entry("three", "3"),
            Map.entry("thrice", "3"),
            Map.entry("four", "4"),
            Map.entry("five", "5"),
            Map.entry("six", "6"),
            Map.entry("seven", "7"),
            Map.entry("eight", "8"),
            Map.entry("nine", "9"),
            Map.entry("ten", "10")
    );

    private static void addLexicalForms(Set<String> target, String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        target.add(token);

        String canonical = canonical(token);
        target.add(canonical);

        Set<String> expansion = EXPANSIONS.get(token);
        if (expansion == null) {
            expansion = EXPANSIONS.get(canonical);
        }
        if (expansion != null) {
            target.addAll(expansion);
        }

        String conservativeStem = conservativeStem(canonical);
        if (!conservativeStem.equals(canonical)) {
            target.add(conservativeStem);
        }
    }

    private static String canonical(String token) {
        if (token == null) {
            return "";
        }
        String clean = token.toLowerCase(Locale.ROOT).strip();
        return CANONICAL.getOrDefault(clean, clean);
    }

    /**
     * A deliberately small morphology pass. It only strips endings when the
     * resulting token is long enough and obviously lexical. The original token
     * remains in the set, so this can only add routing evidence.
     */
    private static String conservativeStem(String token) {
        if (token == null || token.length() < 5
                || NAMESPACED_ID.matcher(token).matches()
                || URL.matcher(token).find()
                || DECIMAL.matcher(token).matches()
                || token.indexOf('/') >= 0
                || token.indexOf('.') >= 0) {
            return token == null ? "" : token;
        }

        if (token.endsWith("ies") && token.length() > 5) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.endsWith("ing") && token.length() > 6) {
            String stem = token.substring(0, token.length() - 3);
            if (stem.length() >= 4 && stem.charAt(stem.length() - 1) == stem.charAt(stem.length() - 2)) {
                stem = stem.substring(0, stem.length() - 1);
            }
            return stem;
        }
        if (token.endsWith("ed") && token.length() > 5) {
            String stem = token.substring(0, token.length() - 2);
            if (stem.length() >= 4 && stem.charAt(stem.length() - 1) == stem.charAt(stem.length() - 2)) {
                stem = stem.substring(0, stem.length() - 1);
            }
            return stem;
        }
        if (token.endsWith("es") && token.length() > 5) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && !token.endsWith("ss") && token.length() > 4) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static String boundRaw(String prompt) {
        if (prompt == null) {
            return "";
        }
        String safe = prompt.replace('\u0000', ' ');
        if (safe.length() <= MAXIMUM_RAW_CHARACTERS) {
            return safe;
        }
        return safe.substring(0, MAXIMUM_RAW_CHARACTERS);
    }

    /**
     * Surface normalization preserves meaningful Minecraft punctuation. Hyphens
     * are converted to spaces only for ordinary lexical words; namespaced ids,
     * URLs, negative numbers, and file/path tokens remain intact.
     */
    private static String normalizeSurface(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201c', '"')
                .replace('\u201d', '"')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace("'", "")
                .replaceAll("\\s+", " ")
                .strip();

        StringBuilder out = new StringBuilder(normalized.length());
        for (String segment : normalized.split(" ")) {
            if (segment.isBlank()) {
                continue;
            }
            String clean = trimOuterPunctuation(segment);
            if (clean.isBlank()) {
                continue;
            }

            boolean protectedToken = URL.matcher(clean).find()
                    || NAMESPACED_ID.matcher(clean).matches()
                    || DECIMAL.matcher(clean).matches()
                    || ABSOLUTE_PATH.matcher(clean).matches()
                    || RELATIVE_PATH.matcher(clean).matches();

            if (!protectedToken) {
                clean = clean.replace('-', ' ');
            }

            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(clean);
        }
        return out.toString().replaceAll("\\s+", " ").strip();
    }

    private static String cleanToken(String value) {
        if (value == null) {
            return "";
        }
        String clean = trimOuterPunctuation(value.toLowerCase(Locale.ROOT));
        if (clean.endsWith(".") && URL.matcher(clean).find()) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private static String trimOuterPunctuation(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && isTrimmableOuter(value.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmableOuter(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isTrimmableOuter(char value) {
        return value == ',' || value == ';' || value == '!' || value == '?'
                || value == '(' || value == ')' || value == '[' || value == ']'
                || value == '{' || value == '}' || value == '"' || value == '\''
                || value == '`';
    }

    private static Set<String> words(String... values) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                String clean = value.strip().toLowerCase(Locale.ROOT);
                if (clean.isBlank()) {
                    continue;
                }
                if (!clean.matches("[a-z0-9_]+")) {
                    throw new IllegalArgumentException(
                            "Tool vocabulary entries must be one token: " + value
                    );
                }
                set.add(clean);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    private static Map<String, String> canonicalAliases() {
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();

        alias(aliases, "advancment", "advancement");
        alias(aliases, "advancments", "advancements");
        alias(aliases, "achievment", "achievement");
        alias(aliases, "achievments", "achievements");
        alias(aliases, "recepie", "recipe");
        alias(aliases, "recipie", "recipe");
        alias(aliases, "recpie", "recipe");
        alias(aliases, "ingredent", "ingredient");
        alias(aliases, "ingredents", "ingredients");
        alias(aliases, "strucure", "structure");
        alias(aliases, "structre", "structure");
        alias(aliases, "dimenson", "dimension");
        alias(aliases, "dimention", "dimension");
        alias(aliases, "enchantement", "enchantment");
        alias(aliases, "enchament", "enchantment");
        alias(aliases, "enchants", "enchantments");
        alias(aliases, "entitiy", "entity");
        alias(aliases, "entites", "entities");
        alias(aliases, "registy", "registry");
        alias(aliases, "registery", "registry");
        alias(aliases, "identifer", "identifier");
        alias(aliases, "identifers", "identifiers");
        alias(aliases, "biom", "biome");
        alias(aliases, "bioms", "biomes");
        alias(aliases, "coor", "coord");
        alias(aliases, "coors", "coords");
        alias(aliases, "cord", "coord");
        alias(aliases, "cords", "coords");
        alias(aliases, "cordinate", "coordinate");
        alias(aliases, "cordinates", "coordinates");
        alias(aliases, "surounding", "surrounding");
        alias(aliases, "suroundings", "surroundings");
        alias(aliases, "invintory", "inventory");
        alias(aliases, "inventroy", "inventory");
        alias(aliases, "comand", "command");
        alias(aliases, "comands", "commands");
        alias(aliases, "sytnax", "syntax");
        alias(aliases, "synatx", "syntax");
        alias(aliases, "funtion", "function");
        alias(aliases, "funtions", "functions");
        alias(aliases, "dependancy", "dependency");
        alias(aliases, "dependancies", "dependencies");
        alias(aliases, "refrence", "reference");
        alias(aliases, "refrences", "references");
        alias(aliases, "resoruce", "resource");
        alias(aliases, "resorces", "resources");
        alias(aliases, "resouce", "resource");
        alias(aliases, "resouces", "resources");
        alias(aliases, "modifiy", "modify");
        alias(aliases, "upate", "update");
        alias(aliases, "udpate", "update");
        alias(aliases, "delte", "delete");
        alias(aliases, "serach", "search");
        alias(aliases, "seach", "search");
        alias(aliases, "exectue", "execute");
        alias(aliases, "excute", "execute");
        alias(aliases, "automatation", "automation");
        alias(aliases, "automtion", "automation");
        alias(aliases, "wrkflow", "workflow");
        alias(aliases, "planing", "planning");
        alias(aliases, "verfy", "verify");
        alias(aliases, "varify", "verify");
        alias(aliases, "valdiate", "validate");
        alias(aliases, "cataloge", "catalogue");

        alias(aliases, "dont", "dont");
        alias(aliases, "doesnt", "doesnt");
        alias(aliases, "didnt", "didnt");
        alias(aliases, "cant", "cant");
        alias(aliases, "wont", "wont");

        return Collections.unmodifiableMap(aliases);
    }

    private static Map<String, Set<String>> compoundExpansions() {
        LinkedHashMap<String, Set<String>> expansions = new LinkedHashMap<>();

        expand(expansions, "rightclick", "rightclick", "right", "click");
        expand(expansions, "leftclick", "leftclick", "left", "click");
        expand(expansions, "middleclick", "middleclick", "middle", "click");
        expand(expansions, "right_click", "right_click", "right", "click");
        expand(expansions, "left_click", "left_click", "left", "click");
        expand(expansions, "middle_click", "middle_click", "middle", "click");
        expand(expansions, "pathfind", "pathfind", "path", "find", "navigate");
        expand(expansions, "pathfinding", "pathfinding", "path", "find", "navigate");
        expand(expansions, "goto", "goto", "go", "to", "navigate");
        expand(expansions, "letgo", "letgo", "let", "go", "release");
        expand(expansions, "hopon", "hopon", "hop", "on", "mount");
        expand(expansions, "getoff", "getoff", "get", "off", "dismount");
        expand(expansions, "blastfurnace", "blastfurnace", "blast", "furnace");
        expand(expansions, "enderdragon", "enderdragon", "ender", "dragon");
        expand(expansions, "enddimension", "enddimension", "end", "dimension");
        expand(expansions, "deepdark", "deepdark", "deep", "dark", "biome");
        expand(expansions, "resourcepack", "resourcepack", "resource", "pack");
        expand(expansions, "resourcepacks", "resourcepacks", "resource", "packs");
        expand(expansions, "datapack", "datapack", "data", "pack");
        expand(expansions, "datapacks", "datapacks", "data", "packs");
        expand(expansions, "mainhand", "mainhand", "main", "hand");
        expand(expansions, "offhand", "offhand", "off", "hand");
        expand(expansions, "leftshift", "leftshift", "left", "shift");
        expand(expansions, "leftctrl", "leftctrl", "left", "ctrl");
        expand(expansions, "pickblock", "pickblock", "pick", "block");
        expand(expansions, "thirdperson", "thirdperson", "third", "person");
        expand(expansions, "loottable", "loottable", "loot", "table");
        expand(expansions, "loottables", "loottables", "loot", "tables");
        expand(expansions, "soundevent", "soundevent", "sound", "event");
        expand(expansions, "soundevents", "soundevents", "sound", "events");
        expand(expansions, "mcfunction", "mcfunction", "function");
        expand(expansions, "mcfunctions", "mcfunctions", "functions");

        return Collections.unmodifiableMap(expansions);
    }

    private static void alias(Map<String, String> aliases, String from, String to) {
        if (from == null || to == null) {
            return;
        }
        String source = from.strip().toLowerCase(Locale.ROOT);
        String target = to.strip().toLowerCase(Locale.ROOT);
        if (!source.isBlank() && !target.isBlank()) {
            aliases.put(source, target);
        }
    }

    private static void expand(
            Map<String, Set<String>> expansions,
            String source,
            String... values
    ) {
        if (source == null || source.isBlank()) {
            return;
        }
        expansions.put(
                source.toLowerCase(Locale.ROOT),
                words(values)
        );
    }

    /**
     * Original accessors remain record components. Richer structured helpers
     * are computed lazily from the same immutable lexical representation.
     */
    record PromptTerms(
            String raw,
            String normalized,
            Set<String> words,
            int tokenCount
    ) {
        PromptTerms {
            raw = raw == null ? "" : raw;
            normalized = normalized == null ? "" : normalized;
            words = words == null ? Set.of() : Set.copyOf(words);
            tokenCount = Math.max(0, tokenCount);
        }

        List<String> orderedWords() {
            if (normalized.isBlank()) {
                return List.of();
            }

            List<String> values = new ArrayList<>();
            Matcher matcher = TOKEN.matcher(normalized);
            while (matcher.find() && values.size() < MAXIMUM_TOKENS) {
                String token = cleanToken(matcher.group());
                if (!token.isBlank()) {
                    values.add(token);
                }
            }
            return List.copyOf(values);
        }

        Set<String> namespacedIds() {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (String token : orderedWords()) {
                if (NAMESPACED_ID.matcher(token).matches()) {
                    ids.add(token.startsWith("#") ? token.substring(1) : token);
                }
                if (ids.size() >= MAXIMUM_STRUCTURED_VALUES) {
                    break;
                }
            }
            return Collections.unmodifiableSet(ids);
        }

        Set<String> tagIds() {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (String token : orderedWords()) {
                if (token.startsWith("#") && NAMESPACED_ID.matcher(token).matches()) {
                    ids.add(token.substring(1));
                }
                if (ids.size() >= MAXIMUM_STRUCTURED_VALUES) {
                    break;
                }
            }
            return Collections.unmodifiableSet(ids);
        }

        Set<String> urls() {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (String token : orderedWords()) {
                if (URL.matcher(token).find()) {
                    values.add(token);
                }
                if (values.size() >= MAXIMUM_STRUCTURED_VALUES) {
                    break;
                }
            }
            return Collections.unmodifiableSet(values);
        }

        Set<String> paths() {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (String token : orderedWords()) {
                if (!URL.matcher(token).find()
                        && (ABSOLUTE_PATH.matcher(token).matches()
                        || RELATIVE_PATH.matcher(token).matches())) {
                    values.add(token);
                }
                if (values.size() >= MAXIMUM_STRUCTURED_VALUES) {
                    break;
                }
            }
            return Collections.unmodifiableSet(values);
        }

        List<String> numericLiterals() {
            List<String> values = new ArrayList<>();
            for (String token : orderedWords()) {
                if (DECIMAL.matcher(token).matches()) {
                    values.add(token);
                }
                if (values.size() >= MAXIMUM_STRUCTURED_VALUES) {
                    break;
                }
            }
            return List.copyOf(values);
        }

        boolean questionLike() {
            return raw.indexOf('?') >= 0 || any(this, QUESTION);
        }

        boolean greetingLike() {
            return tokenCount <= 4 && any(this, GREETING);
        }

        boolean conditional() {
            return any(this, CONDITIONAL);
        }

        boolean sequenced() {
            return any(this, SEQUENCE);
        }

        boolean containsNegation() {
            return any(this, NEGATION);
        }

        boolean asksForOptimization() {
            return any(this, OPTIMIZE);
        }

        boolean requestsCurrentState() {
            return any(this, CURRENT);
        }

        boolean requestsExactness() {
            return any(this, EXACT);
        }

        boolean hasQuantity() {
            return !quantities(this).isEmpty();
        }

        boolean hasCoordinates() {
            return !coordinateTriples(this).isEmpty();
        }

        boolean hasPath() {
            return !paths().isEmpty();
        }

        boolean hasNamespacedId() {
            return !namespacedIds().isEmpty();
        }

        boolean hasUrl() {
            return !urls().isEmpty();
        }
    }

    record CoordinateTriple(String x, String y, String z) {
        CoordinateTriple {
            x = x == null ? "" : x;
            y = y == null ? "" : y;
            z = z == null ? "" : z;
        }

        boolean relative() {
            return x.startsWith("~") || y.startsWith("~") || z.startsWith("~");
        }

        boolean local() {
            return x.startsWith("^") || y.startsWith("^") || z.startsWith("^");
        }
    }
}
