package com.spirit.client.gui.ide;

import com.spirit.koil.api.util.file.audio.AudioManager;
import com.spirit.koil.api.util.file.media.video.VideoService;
import com.spirit.koil.api.util.file.image.ExternalImageLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.io.File;

public final class TextFileViewSupport {
    private TextFileViewSupport() {
    }

    public static FileType classifyFileType(File file) {
        if (file == null) {
            return FileType.FILE;
        }
        String fileName = normalizeSpecialFileName(file.getName()).toLowerCase();
        if (ExternalImageLoader.isSupportedImageFile(file)) {
            return FileType.IMAGE;
        }
        if (VideoService.isRecognizedVideoFile(file)) {
            return FileType.VIDEO;
        }
        if (AudioManager.isRecognizedAudioFile(file)) {
            return FileType.AUDIO;
        }
        if (fileName.endsWith(".zip") || fileName.endsWith(".rar") || fileName.endsWith(".7z") || fileName.endsWith(".tar")
                || fileName.endsWith(".gz") || fileName.endsWith(".bz2") || fileName.endsWith(".xz") || fileName.endsWith(".jar")
                || fileName.endsWith(".war") || fileName.endsWith(".ear")) {
            return FileType.ZIP;
        }
        if (fileName.endsWith(".mcmeta") || fileName.endsWith(".mcfunction") || fileName.endsWith(".mcpack")
                || fileName.endsWith(".mctemplate")) {
            return FileType.MCMETA;
        }
        return FileType.FILE;
    }

    public static boolean isEditableTextType(FileType fileType) {
        return fileType == FileType.FILE || fileType == FileType.MCMETA;
    }

    public static boolean isTextFile(File file) {
        if (file == null) {
            return false;
        }
        String fileName = normalizeSpecialFileName(file.getName()).toLowerCase();
        return fileName.endsWith(".txt") || fileName.endsWith(".json") || fileName.endsWith(".json5") || fileName.endsWith(".log") || fileName.endsWith(".kwds")
                || fileName.endsWith(".properties") || fileName.endsWith(".db") || fileName.endsWith(".dat") || fileName.endsWith(".dat_old") || fileName.endsWith(".class")
                || fileName.endsWith(".mcmeta") || fileName.endsWith(".toml") || fileName.endsWith(".mcfunction") || fileName.endsWith(".mcpack") || fileName.endsWith(".mctemplate")
                || fileName.endsWith(".xml") || fileName.endsWith(".yml") || fileName.endsWith(".yaml") || fileName.endsWith(".ini")
                || fileName.endsWith(".config") || fileName.endsWith(".conf") || fileName.endsWith(".css") || fileName.endsWith(".js")
                || fileName.endsWith(".html") || fileName.endsWith(".c") || fileName.endsWith(".cpp") || fileName.endsWith(".h")
                || fileName.endsWith(".hpp") || fileName.endsWith(".java") || fileName.endsWith(".py") || fileName.endsWith(".rb")
                || fileName.endsWith(".php") || fileName.endsWith(".sql") || fileName.endsWith(".sh") || fileName.endsWith(".bat")
                || fileName.endsWith(".ps1") || fileName.endsWith(".md") || fileName.endsWith(".rtf") || fileName.endsWith(".doc")
                || fileName.endsWith(".docx") || fileName.endsWith(".odt") || fileName.endsWith(".pdf") || fileName.endsWith(".tex")
                || fileName.endsWith(".placebo") || fileName.endsWith(".glsl") || fileName.endsWith(".vert") || fileName.endsWith(".frag")
                || fileName.endsWith(".geom") || fileName.endsWith(".comp") || fileName.endsWith(".vsh") || fileName.endsWith(".fsh")
                || fileName.endsWith(".ktl");
    }

    public static boolean isEditableTextFile(File file) {
        return file != null && file.isFile() && isEditableTextType(classifyFileType(file)) && isTextFile(file);
    }

    public static boolean openReadOnlyEditor(Screen parent, File file) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || file == null || !isEditableTextFile(file)) {
            return false;
        }
        client.setScreen(new FileEditorScreen(parent, file, new FileItem(file.getName(), classifyFileType(file), file), -1, true));
        return true;
    }

    private static String normalizeSpecialFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        if (fileName.toLowerCase().endsWith(".disabled")) {
            return fileName.substring(0, fileName.length() - ".disabled".length());
        }
        return fileName;
    }
}
