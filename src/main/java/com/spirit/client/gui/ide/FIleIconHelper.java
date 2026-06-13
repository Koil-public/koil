package com.spirit.client.gui.ide;
import net.minecraft.util.Identifier;
import static com.spirit.client.gui.ide.FileExplorerScreen.*;
public class FIleIconHelper {
    public static Identifier resolve(String fileName) {
        if (fileName == null || fileName.isBlank()) return FILE_ICON;

        String effectiveName = normalizeDisabledName(fileName);
        String name = effectiveName.toLowerCase();
        String extension = getFileExtension(effectiveName);
        // ===== CORE BUILD / TOOLING =====
        if (name.equals("dockerfile")) return CODE_ICON;
        if (name.equals("makefile")) return CODE_ICON;
        if (name.equals("cmakelists.txt")) return CODE_ICON;
        if (name.equals("gradlew") || name.equals("gradlew.bat")) return CODE_ICON;
        if (name.equals("build.gradle") || name.equals("settings.gradle")) return CODE_ICON;
        if (name.equals("pom.xml")) return CODE_ICON;
        if (name.equals("build.xml")) return CODE_ICON;

        // ===== LINT / FORMAT =====
        if (name.equals(".eslintrc") || name.startsWith(".eslintrc.")) return CODE_ICON;
        if (name.equals(".prettierrc") || name.startsWith(".prettierrc.")) return CODE_ICON;
        if (name.equals(".stylelintrc") || name.startsWith(".stylelintrc.")) return CODE_ICON;
        if (name.equals(".editorconfig")) return CODE_ICON;

        // ===== ENV / SYSTEM CONFIG =====
        if (name.equals(".env") || name.startsWith(".env.")) return ENVIRONMENT_ICON;
        if (name.equals(".gitignore")) return ENVIRONMENT_ICON;
        if (name.equals(".gitattributes")) return ENVIRONMENT_ICON;
        if (name.equals(".gitmodules")) return ENVIRONMENT_ICON;
        if (name.equals(".npmrc")) return ENVIRONMENT_ICON;
        if (name.equals(".yarnrc") || name.equals(".yarnrc.yml")) return ENVIRONMENT_ICON;
        if (name.equals(".bashrc") || name.equals(".zshrc") || name.equals(".profile")) return ENVIRONMENT_ICON;

        // ===== VERSION CONTROL / DOCS =====
        if (name.equals("readme") || name.startsWith("readme.")) return TEXT_ICON;
        if (name.equals("license") || name.startsWith("license.")) return TEXT_ICON;
        if (name.equals("copying")) return TEXT_ICON;
        if (name.equals("changelog") || name.startsWith("changelog.")) return TEXT_ICON;
        if (name.equals("authors")) return TEXT_ICON;
        if (name.equals("contributors")) return TEXT_ICON;

        // ===== CI / DEVOPS =====
        if (name.equals("docker-compose.yml") || name.equals("docker-compose.yaml")) return CODE_ICON;
        if (name.equals("jenkinsfile")) return CODE_ICON;
        if (name.equals(".travis.yml")) return CODE_ICON;
        if (name.equals("azure-pipelines.yml")) return CODE_ICON;
        if (name.equals("bitbucket-pipelines.yml")) return CODE_ICON;
        if (name.equals("cloudbuild.yaml")) return CODE_ICON;

        // ===== PYTHON =====
        if (name.equals("requirements.txt")) return PYTHON_ICON;
        if (name.equals("pyproject.toml")) return PYTHON_ICON;
        if (name.equals("setup.py")) return PYTHON_ICON;
        if (name.equals("setup.cfg")) return PYTHON_ICON;
        if (name.equals("pipfile")) return PYTHON_ICON;
        if (name.equals("pipfile.lock")) return PYTHON_ICON;

        // ===== JAVA / SPRING =====
        if (name.equals("application.properties")) return JAVA_ICON;
        if (name.equals("application.yml")) return JAVA_ICON;
        if (name.equals("log4j.properties")) return JAVA_ICON;
        if (name.equals("logback.xml")) return JAVA_ICON;

        // ===== GITHUB / GITLAB =====
        if (name.equals(".gitlab-ci.yml")) return GITHUB_ICON;
        if (name.equals("build.yml")) return GITHUB_ICON;

        // ===== DATABASE =====
        if (name.equals("schema.sql")) return CODE_ICON;
        if (name.equals("data.sql")) return CODE_ICON;
        if (name.equals("migrations.sql")) return CODE_ICON;

        // ===== MODDING =====
        // - fabric
        if (name.equals("fabric.mod.json")) return FABRIC_FILE_ICON;

        // - quilt
        if (name.equals("quilt.mod.json")) return QUILT_FILE_ICON;

        // ===== SHADERS / GRAPHICS =====
        if (name.equals("shader.json")) return SHADER_ICON;
        if (name.equals("shaders.properties")) return SHADER_ICON;

        // ===== SECURITY / KEYS =====
        if (name.equals("id_rsa") || name.equals("id_dsa")) return SECURITY_FILE_ICON;
        if (name.equals("known_hosts")) return SECURITY_FILE_ICON;
        if (name.equals("authorized_keys")) return SECURITY_FILE_ICON;

        // ===== SYSTEM FILES =====
        if (name.equals("hosts")) return EXE_ICON;
        if (name.equals("fstab")) return EXE_ICON;
        if (name.equals("crontab")) return EXE_ICON;

        // ===== EDITOR / IDE =====
        if (name.equals(".idea")) return FILE_ICON;
        if (name.equals(".vscode")) return FILE_ICON;
        if (name.equals("workspace.json")) return CODE_ICON;

        // ===== KOIL =====
        if (name.equals("package.json")) return KOIL_FILE_ICON;

        String lower = extension.toLowerCase();
        return switch (lower) {
            // ===== CODE / PROGRAMMING =====
            case "toml", "kwds",
                 "xml", "yml", "yaml", "ini", "config", "conf",
                 "css", "scss", "sass", "less",
                 "js", "mjs", "cjs", "ts",
                 "htm",
                 "cc", "cxx",
                 "h", "hpp", "hh",
                 "kt", "kts",
                 "rb",
                 "php",
                 "go",
                 "rs",
                 "swift",
                 "scala",
                 "dart",
                 "lua",
                 "zig",
                 "nim",
                 "v",
                 "graphql",
                 "gql",
                 "sh", "bash", "zsh", "fish",
                 "bat", "cmd",
                 "ps1",
                 "gradle", "groovy",
                 "make", "mk",
                 "cmake",
                 "dockerfile",
                 "asm", "s" -> CODE_ICON;

            // ===== FONTS =====
            case "ttf", "otf", "woff", "woff2" -> FONT_ICON;

            // ===== JAVA =====
            case "class", "jar", "java" -> JAVA_ICON;

            // ===== CPP =====
            case "cpp" -> CPP_ICON;

            // ===== C =====
            case "c" -> C_ICON;

            // ===== C# =====
            case "cs" -> CS_ICON;

            // ===== PYTHON =====
            case "py", "pyw" -> PYTHON_ICON;

            // ===== JSON =====
            case "json" -> JSON_ICON;

            // ===== JSON5 =====
            case "json5" -> JSON5_ICON;

            // ===== PROPERTIES =====
            case "properties" -> PROPERTIES_ICON;

            // ===== GITHUB =====
            case "github" -> GITHUB_ICON;

            // ===== SHADER =====
            case "glsl", "vert", "frag", "geom", "comp" -> SHADER_ICON;
            case "vsh" -> VSH_ICON;
            case "fsh" -> FSH_ICON;
            case "placebo" -> PLACEBO_ICON;

            // ===== MARKDOWN =====
            case "md", "markdown" -> MARKDOWN_ICON;

            // ===== DATA / DATABASE =====
            case "db", "sqlite", "sqlite3", "sql",
                 "csv", "tsv",
                 "parquet",
                 "feather" -> DATABASE_ICON;

            // ===== WEB / FRONTEND =====
            case "vue", "svelte",
                 "jsx", "tsx",
                 "wasm", "html" -> WEB_FILE_ICON;

            // ===== CERT / SECURITY =====
            case "pem", "crt", "cer", "key", "p12", "pfx" -> SECURITY_FILE_ICON;

            // ===== ENV =====
            case "env", "ds_store" -> ENVIRONMENT_ICON;

            // ===== EXECUTABLE / SYSTEM =====
            case "exe", "dll", "so", "dylib", "bin", "appimage", "msi", "apk", "ipa" -> EXE_ICON;

            // ===== GITHUB / GITLAB =====
            case ".gitlab-ci.yml" -> GITHUB_ICON;

            // ===== TEXT / DOCUMENT =====
            case "txt","rst", "rtf", "doc", "docx", "odt", "pdf", "tex", "epub" -> TEXT_ICON;

            // ===== LOG =====
            case "log", "out", "err", "trace", "stacktrace" -> LOG_ICON;

            // ===== KOIL =====
            case "koil", "kpkg", "ktl" -> KOIL_FILE_ICON;

            // ===== MINECRAFT / MODDING =====
            case "mcmeta", "mcfunction", "mcpack", "mctemplate", "nbt", "datapack", "lang", "bbmodel" -> MCMETA_ICON;

            // ===== ARCHIVES =====
            case "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "lz", "lzma", "war", "ear" -> ZIP_ICON;

            // ===== IMAGE =====
            case "png", "jpeg", "jpg", "gif", "bmp", "tiff", "tif", "webp", "ico", "svg", "dds", "tga", "psd", "kra" ->
                    IMAGE_ICON;

            // ===== VIDEO =====
            case "mov", "mp4", "avi", "mkv", "wmv", "flv", "webm", "m4v", "3gp" -> VIDEO_ICON;

            // ===== AUDIO =====
            case "wav", "ogg", "mp3", "flac", "aac", "m4a", "wma" -> AUDIO_ICON;
            default -> FILE_ICON;
        };
    }

    private static String normalizeDisabledName(String fileName) {
        String normalized = fileName == null ? "" : fileName;
        if (normalized.toLowerCase().endsWith(".disabled")) {
            return normalized.substring(0, normalized.length() - ".disabled".length());
        }
        return normalized;
    }
    private static String getFileExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) return "";
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1 || lastDot == fileName.length() - 1) return "";
        return fileName.substring(lastDot + 1);
    }
}
